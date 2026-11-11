#!/usr/bin/env python3
"""Build metadata and publish immutable APKs for this fork's personal update channel."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parent.parent


def run(*args):
    return subprocess.check_output(args, cwd=ROOT, text=True).strip()


def channel():
    values = {}
    for line in (ROOT / "kotlin-tv/distribution/channel.properties").read_text().splitlines():
        if line and not line.startswith("#"):
            key, value = line.split("=", 1)
            values[key] = value
    assert re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*/[A-Za-z0-9][A-Za-z0-9_.-]*", values["repository"])
    assert re.fullmatch(r"[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*)+", values["applicationId"])
    assert re.fullmatch(r"[a-f0-9]{64}", values["signerSha256"])
    assert re.fullmatch(r"\d+\.\d+\.\d+", values["versionName"])
    return values


def version(config, run_number):
    assert run_number > 0
    code = int(config["versionCodeBase"]) + run_number
    assert 53 < code <= 2147483647
    return code, f'{config["versionName"]}+{run_number}', f'kotlin-v{config["versionName"]}-build.{code}'


def digest(path):
    result = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(65536), b""):
            result.update(chunk)
    return result.hexdigest()


def verify_badging(config, badging, certificate, code, name):
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.M)
    sdk = re.search(r"^sdkVersion:'(\d+)'", badging, re.M)
    signers = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]+)$", certificate, re.M)
    assert package and package.groups() == (config["applicationId"], str(code), name + "-playbackfix"), "APK package or version mismatch"
    assert sdk and sdk[1] == config["minSdk"], "APK minSdk mismatch"
    assert "application-debuggable" not in badging, "Distribution APK must not be debuggable"
    assert [value.lower() for value in signers] == [config["signerSha256"]], "APK signer mismatch"
    assert "android.permission.REQUEST_INSTALL_PACKAGES" in badging, "Updater install permission missing"


def prepare(apk, run_number):
    config = channel()
    code, name, tag = version(config, run_number)
    assert not run("git", "status", "--porcelain", "--untracked-files=no"), "Commit source before packaging"
    tools = Path(os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"]) / "build-tools/36.0.0"
    badging = run(str(tools / "aapt"), "dump", "badging", str(apk))
    certificate = run(str(tools / "apksigner"), "verify", "--print-certs", str(apk))
    verify_badging(config, badging, certificate, code, name)
    out = ROOT / "outputs/releases" / tag
    out.mkdir(parents=True, exist_ok=False)
    destination = out / f'hongguotv-kotlin-{config["versionName"]}-build.{code}-android8.apk'
    shutil.copyfile(apk, destination)
    commit = run("git", "rev-parse", "HEAD")
    manifest = {"schema": 1, "packageName": config["applicationId"], "versionCode": code,
                "versionName": name + "-playbackfix", "minSdk": int(config["minSdk"]), "tag": tag,
                "sourceCommit": commit, "certificateSha256": config["signerSha256"],
                "apk": {"name": destination.name, "size": destination.stat().st_size, "sha256": digest(destination)},
                "notes": "个人分发版：保留本机片单、观看记录与设置；支持应用内检查、下载和系统确认覆盖更新。"}
    reviewed_pr = os.environ["REVIEWED_PULL_REQUEST"]
    assert reviewed_pr.isdigit(), "A reviewed PR is required"
    manifest["reviewedPullRequest"] = f'https://github.com/{config["repository"]}/pull/{reviewed_pr}'
    metadata = out / "update.json"
    metadata.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    source = out / f"hongguotv-{tag}-source.zip"
    paths = ["kotlin-tv", "LICENSE", "AGENTS.md", "scripts/tv-update.py", "scripts/test-tv-update.py",
             "scripts/upstream-sync.py", "scripts/test-upstream-sync.py", ".github/upstream-sync", ".github/CODEOWNERS",
             ".github/workflows/tv-release.yml", ".github/workflows/upstream-sync.yml", "server/vendor/hongguo.cjs", "server/vendor/LICENSE.drpys"]
    tracked = run("git", "ls-tree", "-r", "--name-only", "HEAD", "--", *paths).splitlines()
    for path in tracked:
        assert not any(part in {"build", ".gradle", ".kotlin", "outputs"} for part in Path(path).parts)
        assert not path.endswith((".jks", ".keystore", "keystore.properties", "local.properties"))
    run("git", "archive", "--format=zip", "--prefix=hongguotv-source/", "--output", str(source), "HEAD", "--", *paths)
    sums = out / "SHA256SUMS"
    sums.write_text("".join(f"{digest(path)}  {path.name}\n" for path in [destination, source, metadata]))
    (out / "release-notes.md").write_text(f'''个人分发版 {manifest["versionName"]}，构建 {code}，Android 8.0+。

包名 `{config["applicationId"]}`。沿用当前个人开发签名以支持保留数据覆盖升级，分发构建关闭调试；不是上游正式签名包。
电视自动检查并下载，核对 SHA-256、实际包名、版本及签名后，交由系统确认安装。

源码提交：`{commit}`。自动单元测试和 lint 通过后构建；不代表实体电视验收。
附件提供 APK、对应原生源码和构建说明、update.json、SHA256SUMS。
复现时设置 HONGGUOTV_VERSION_CODE={code}、HONGGUOTV_VERSION_NAME={name}，运行 :app:assembleDistribution。
''')
    print(metadata.relative_to(ROOT))


def asset_matches(asset, path):
    return asset["state"] == "uploaded" and asset["size"] == path.stat().st_size and asset.get("digest") == "sha256:" + digest(path)


def promote(code, latest_tag):
    if latest_tag is None:
        return True
    match = re.fullmatch(r"kotlin-v\d+\.\d+\.\d+-build\.(\d+)", latest_tag)
    assert match, "Latest belongs to another release scheme; refusing to replace it"
    return code > int(match[1])


def publish(metadata):
    config = channel()
    repository = config["repository"]
    assert os.environ.get("GITHUB_REPOSITORY") == repository, "Release only from the configured fork"
    assert os.environ.get("GITHUB_REF") == "refs/heads/main", "Release only from main"
    manifest = json.loads(metadata.read_text())
    assert manifest["sourceCommit"] == run("git", "rev-parse", "HEAD")
    assert manifest["packageName"] == config["applicationId"]
    assert manifest["certificateSha256"] == config["signerSha256"]
    review = run("python3", "scripts/upstream-sync.py", "release-check", "--sha", manifest["sourceCommit"])
    decision = dict(line.split("=", 1) for line in review.splitlines())
    assert decision.get("approved") == "true", "Human review was not confirmed; no release"
    assert manifest["reviewedPullRequest"] == f'https://github.com/{config["repository"]}/pull/{decision["pull_request"]}', "Release review provenance mismatch"
    tag = manifest["tag"]
    out = metadata.parent
    paths = [out / manifest["apk"]["name"], out / f"hongguotv-{tag}-source.zip", metadata, out / "SHA256SUMS"]
    assert digest(paths[0]) == manifest["apk"]["sha256"] and paths[0].stat().st_size == manifest["apk"]["size"]
    existing = subprocess.run(["gh", "release", "view", tag, "--repo", repository, "--json", "isDraft,assets"], capture_output=True, text=True)
    info = json.loads(existing.stdout) if existing.returncode == 0 else None
    if info and not info["isDraft"]:
        assert all(any(asset["name"] == path.name and asset_matches(asset, path) for asset in info["assets"]) for path in paths), "Published assets are immutable"
        print("Already published; assets and latest left unchanged.")
        return
    if info is None:
        run("gh", "release", "create", tag, "--repo", repository, "--target", manifest["sourceCommit"], "--draft", "--title", f'红果 TV {manifest["versionName"]}', "--notes-file", str(out / "release-notes.md"))
        info = {"assets": []}
    for path in paths:
        asset = next((value for value in info["assets"] if value["name"] == path.name), None)
        if asset:
            assert asset_matches(asset, path), "Existing draft asset differs; refusing overwrite"
        else:
            run("gh", "release", "upload", tag, str(path), "--repo", repository)
    info = json.loads(run("gh", "release", "view", tag, "--repo", repository, "--json", "assets"))
    assert all(any(asset["name"] == path.name and asset_matches(asset, path) for asset in info["assets"]) for path in paths)
    latest = subprocess.run(["gh", "release", "view", "--repo", repository, "--json", "tagName"], capture_output=True, text=True)
    latest_tag = json.loads(latest.stdout)["tagName"] if latest.returncode == 0 else None
    make_latest = promote(manifest["versionCode"], latest_tag)
    run("gh", "release", "edit", tag, "--repo", repository, "--draft=false", "--latest=" + str(make_latest).lower(), "--notes-file", str(out / "release-notes.md"))
    print(json.dumps({"repository": repository, "tag": tag, "versionCode": manifest["versionCode"], "latest": make_latest}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("version")
    build = commands.add_parser("prepare")
    build.add_argument("--apk", type=Path, required=True)
    release = commands.add_parser("publish")
    release.add_argument("--manifest", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "version":
        code, name, _ = version(channel(), int(os.environ["GITHUB_RUN_NUMBER"]))
        print(f"HONGGUOTV_VERSION_CODE={code}\nHONGGUOTV_VERSION_NAME={name}")
    elif args.command == "prepare":
        prepare(args.apk.resolve(), int(os.environ["GITHUB_RUN_NUMBER"]))
    else:
        publish(args.manifest.resolve())


if __name__ == "__main__":
    main()
