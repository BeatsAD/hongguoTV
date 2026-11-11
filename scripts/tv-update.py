#!/usr/bin/env python3
"""Validate and publish APK-only releases for the personal hongguo channel."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parent.parent
VERSION = r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)"
MARKER = "<!-- hongguo-update:"


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
    assert re.fullmatch(VERSION, values["versionName"])
    assert 0 < int(values["versionCodeBase"]) <= int(values["versionCodeFloor"]) < 2147483647
    return values


def version(config, run_number, previous_codes=()):
    assert run_number > 0
    floor = max([int(config["versionCodeFloor"]), *previous_codes])
    code = max(max(int(config["versionCodeBase"]), int(config["versionCodeFloor"])) + run_number, floor + 1)
    assert floor < code <= 2147483647
    return code, config["versionName"], "v" + config["versionName"]


def release_metadata(release):
    body = release.get("body") or ""
    assert body.count(MARKER) == 1, "Release metadata is missing or ambiguous"
    encoded, end, _ = body.split(MARKER, 1)[1].partition("-->")
    assert end, "Release metadata is incomplete"
    metadata = json.loads(encoded.strip())
    assert metadata["schema"] == 2
    assert re.fullmatch(VERSION, metadata["versionName"])
    assert release["tag_name"] == "v" + metadata["versionName"]
    assert type(metadata["versionCode"]) is int and 0 < metadata["versionCode"] <= 2147483647
    return metadata


def release_code(release, package):
    if MARKER in (release.get("body") or ""):
        metadata = release_metadata(release)
        assert metadata["packageName"] == package, "Release belongs to another package"
        return metadata["versionCode"]
    match = re.fullmatch(r"kotlin-v\d+\.\d+\.\d+-build\.(\d+)", release["tag_name"])
    if match:
        return int(match[1])
    return None


def history(config):
    pages = json.loads(run("gh", "api", "--paginate", "--slurp", f'repos/{config["repository"]}/releases?per_page=100'))
    return [release for page in pages for release in page]


def allocate(config, run_number, releases, commit):
    tag = "v" + config["versionName"]
    current = next((r for r in releases if r["tag_name"] == tag), None)
    assert not current or current["draft"], "This version is already public; increase versionName instead of replacing it"
    codes = [code for r in releases if r is not current for code in [release_code(r, config["applicationId"])] if code is not None]
    if current:
        metadata = release_metadata(current)
        assert metadata["packageName"] == config["applicationId"] and metadata["sourceCommit"] == commit, "Existing draft has different source or package"
        assert metadata["versionCode"] > max([int(config["versionCodeFloor"]), *codes]), "Draft build number is stale"
        return metadata["versionCode"], config["versionName"], tag
    return version(config, run_number, codes)


def digest(path):
    result = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(65536), b""):
            result.update(chunk)
    return result.hexdigest()


def android_sdk():
    properties = ROOT / "kotlin-tv/local.properties"
    if properties.is_file():
        for line in properties.read_text().splitlines():
            if line.startswith("sdk.dir="):
                return Path(line.split("=", 1)[1].strip().replace("\\:", ":").replace("\\ ", " ").replace("\\\\", "\\"))
    return Path(os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"])


def verify_badging(config, badging, certificate, code, name):
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.M)
    sdk = re.search(r"^sdkVersion:'(\d+)'", badging, re.M)
    signers = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]+)$", certificate, re.M)
    assert package and package.groups() == (config["applicationId"], str(code), name), "APK package or version mismatch"
    assert sdk and sdk[1] == config["minSdk"], "APK minSdk mismatch"
    assert "application-debuggable" not in badging, "Distribution APK must not be debuggable"
    assert [value.lower() for value in signers] == [config["signerSha256"]], "APK signer mismatch"
    assert "android.permission.REQUEST_INSTALL_PACKAGES" in badging, "Updater install permission missing"
    assert re.search(r"^application-label:'hongguo'$", badging, re.M), "APK display name must be hongguo"


def release_notes(config, record):
    path = ROOT / "kotlin-tv/distribution/notes" / (record["versionName"] + ".md")
    notes = path.read_text().strip()
    assert re.findall(r"^## (.+)$", notes, re.M) == ["新增功能", "问题修复"], "Release notes require the two user-facing sections"
    for section in re.split(r"^## .+$", notes, flags=re.M)[1:]:
        assert re.search(r"^- \S", section, re.M), "Each release-note section needs a change or an explicit none entry"
    assert MARKER not in notes and len(notes) <= 6000
    public = (notes + f'\n\n---\nAndroid 8.0+。使用相同包名和签名覆盖升级，保留本机记录。'
              f'\n[对应源码与构建说明](https://github.com/{config["repository"]}/tree/{record["tag"]})。\n')
    metadata = {key: record[key] for key in ["schema", "packageName", "versionCode", "versionName", "minSdk", "sourceCommit"]}
    metadata["reviewedPullRequest"] = record.get("reviewedPullRequest")
    return public + "\n" + MARKER + json.dumps(metadata, ensure_ascii=False, separators=(",", ":")) + " -->\n"


def prepare(apk):
    config = channel()
    code = int(os.environ["HONGGUOTV_VERSION_CODE"])
    name = os.environ["HONGGUOTV_VERSION_NAME"]
    assert name == config["versionName"] and int(config["versionCodeFloor"]) < code <= 2147483647
    tag = "v" + name
    assert not run("git", "status", "--porcelain", "--untracked-files=no"), "Commit source before packaging"
    tools = android_sdk() / "build-tools/36.0.0"
    verify_badging(config, run(str(tools / "aapt"), "dump", "badging", str(apk)),
                   run(str(tools / "apksigner"), "verify", "--print-certs", str(apk)), code, name)
    out = ROOT / "outputs/releases" / tag
    out.mkdir(parents=True, exist_ok=False)
    destination = out / f"hongguo-{name}.apk"
    shutil.copyfile(apk, destination)
    record = {"schema": 2, "packageName": config["applicationId"], "versionCode": code,
              "versionName": name, "minSdk": int(config["minSdk"]), "tag": tag,
              "sourceCommit": run("git", "rev-parse", "HEAD"), "certificateSha256": config["signerSha256"],
              "apk": {"name": destination.name, "size": destination.stat().st_size, "sha256": digest(destination)}}
    reviewed_pr = os.environ.get("REVIEWED_PULL_REQUEST")
    if os.environ.get("GITHUB_ACTIONS") == "true":
        assert reviewed_pr and reviewed_pr.isdigit(), "A reviewed PR is required for CI packaging"
    if reviewed_pr:
        assert reviewed_pr.isdigit(), "Reviewed PR must be numeric"
    record["reviewedPullRequest"] = f'https://github.com/{config["repository"]}/pull/{reviewed_pr}' if reviewed_pr else None
    path = out / "release-record.json"
    path.write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n")
    (out / "release-notes.md").write_text(release_notes(config, record))
    print(path.relative_to(ROOT))


def asset_matches(asset, path):
    return asset["state"] == "uploaded" and asset["size"] == path.stat().st_size and asset.get("digest") == "sha256:" + digest(path)


def get_release(repository, tag=None):
    suffix = "latest" if tag is None else "tags/" + tag
    result = subprocess.run(["gh", "api", f"repos/{repository}/releases/{suffix}"], capture_output=True, text=True)
    if result.returncode == 0:
        return json.loads(result.stdout)
    assert "HTTP 404" in result.stderr, "Cannot read release state; refusing to publish"
    return None


def promote(record, latest):
    if latest is None:
        return True
    code = release_code(latest, record["packageName"])
    assert code is not None, "Latest belongs to another release scheme; refusing to replace it"
    old_name = release_metadata(latest)["versionName"] if MARKER in (latest.get("body") or "") else re.fullmatch(r"kotlin-v(\d+\.\d+\.\d+)-build\.\d+", latest["tag_name"])[1]
    return record["versionCode"] > code and tuple(map(int, record["versionName"].split("."))) > tuple(map(int, old_name.split(".")))


def publish(path):
    config = channel()
    repository = config["repository"]
    assert os.environ.get("GITHUB_REPOSITORY") == repository, "Release only from the configured fork"
    assert os.environ.get("GITHUB_REF") == "refs/heads/main", "Release only from main"
    record = json.loads(path.read_text())
    assert record["sourceCommit"] == run("git", "rev-parse", "HEAD")
    review = run("python3", "scripts/upstream-sync.py", "release-check", "--sha", record["sourceCommit"])
    decision = dict(line.split("=", 1) for line in review.splitlines())
    assert decision.get("approved") == "true", "Human review was not confirmed; no release"
    assert record.get("reviewedPullRequest") == f'https://github.com/{config["repository"]}/pull/{decision["pull_request"]}', "Release review provenance mismatch"
    assert record["schema"] == 2
    assert record["packageName"] == config["applicationId"] and record["certificateSha256"] == config["signerSha256"]
    assert record["versionName"] == config["versionName"] and record["tag"] == "v" + record["versionName"]
    assert type(record["versionCode"]) is int and int(config["versionCodeFloor"]) < record["versionCode"] <= 2147483647
    assert record["minSdk"] == int(config["minSdk"]) and record["apk"]["name"] == f'hongguo-{record["versionName"]}.apk'
    out = path.parent
    apk = out / record["apk"]["name"]
    assert 0 < apk.stat().st_size <= 80 * 1024 * 1024
    assert digest(apk) == record["apk"]["sha256"] and apk.stat().st_size == record["apk"]["size"]
    notes = out / "release-notes.md"
    expected_notes = release_notes(config, record)
    assert notes.read_text() == expected_notes, "Release notes differ from the reviewed source"
    tag = record["tag"]
    info = get_release(repository, tag)
    if info:
        assert release_metadata(info) == release_metadata({"tag_name": tag, "body": expected_notes}), "Existing release has different build metadata"
        assert info["body"].strip() == expected_notes.strip(), "Existing release notes differ"
        assert all(asset["name"] == apk.name for asset in info["assets"]), "Release must contain only the APK; existing attachments will not be deleted"
        if not info["draft"]:
            assert len(info["assets"]) == 1 and asset_matches(info["assets"][0], apk), "Published APK is immutable"
            print("Already published; APK and latest left unchanged.")
            return
    else:
        run("gh", "release", "create", tag, "--repo", repository, "--target", record["sourceCommit"], "--draft",
            "--title", "hongguo " + record["versionName"], "--notes-file", str(notes))
        info = {"assets": []}
    if info["assets"]:
        assert len(info["assets"]) == 1 and asset_matches(info["assets"][0], apk), "Existing draft APK differs; refusing overwrite"
    else:
        run("gh", "release", "upload", tag, str(apk), "--repo", repository)
    info = get_release(repository, tag)
    assert info and len(info["assets"]) == 1 and asset_matches(info["assets"][0], apk), "Uploaded APK failed digest/size verification"
    make_latest = promote(record, get_release(repository))
    run("gh", "release", "edit", tag, "--repo", repository, "--draft=false", "--latest=" + str(make_latest).lower(), "--notes-file", str(notes))
    print(json.dumps({"repository": repository, "tag": tag, "versionCode": record["versionCode"], "latest": make_latest}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("version")
    build = commands.add_parser("prepare")
    build.add_argument("--apk", type=Path, required=True)
    release = commands.add_parser("publish")
    release.add_argument("--record", "--manifest", dest="record", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "version":
        config = channel()
        releases = history(config)
        # Local reservations also advance between un-published builds. CI has serialized runs and an independent run-number floor.
        import fcntl
        state = ROOT / "outputs/version-code-reservation.txt"
        state.parent.mkdir(parents=True, exist_ok=True)
        with state.open("a+") as ledger:
            fcntl.flock(ledger, fcntl.LOCK_EX)
            ledger.seek(0)
            previous = ledger.read().strip()
            if previous:
                releases.append({"tag_name": f'kotlin-v0.0.0-build.{int(previous)}', "body": "", "draft": True})
            code, name, _ = allocate(config, int(os.environ.get("GITHUB_RUN_NUMBER", "1")), releases, run("git", "rev-parse", "HEAD"))
            ledger.seek(0)
            ledger.truncate()
            ledger.write(str(code) + "\n")
        print(f"HONGGUOTV_VERSION_CODE={code}\nHONGGUOTV_VERSION_NAME={name}")
    elif args.command == "prepare":
        prepare(args.apk.resolve())
    else:
        publish(args.record.resolve())


if __name__ == "__main__":
    main()
