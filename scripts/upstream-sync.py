#!/usr/bin/env python3
"""Preserve local customizations, propose upstream core patches, require human review."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
from urllib.parse import quote

ROOT = Path(__file__).resolve().parent.parent
POLICY = ".github/upstream-sync/policy.json"
STATE = ".github/upstream-sync/state.json"
UPSTREAM_REF = "refs/remotes/sync-upstream/main"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def command(*args, cwd=ROOT):
    return subprocess.check_output(args, cwd=cwd, text=True).strip()


def api(path, method="GET", data=None):
    args = ["gh", "api", "--method", method, path]
    if data is not None:
        args += ["--input", "-"]
    result = subprocess.run(args, input=json.dumps(data) if data is not None else None,
                            text=True, capture_output=True, check=True)
    return json.loads(result.stdout) if result.stdout.strip() else None


def all_reviews(repository, number):
    # Decisions are ordered oldest first; never miss a later dismissal on another page.
    pages = json.loads(command("gh", "api", "--paginate", "--slurp", f"repos/{repository}/pulls/{number}/reviews?per_page=100"))
    return [review for page in pages for review in page]


class Repo:
    def __init__(self, root=ROOT):
        self.root = Path(root)

    def git(self, *args):
        return command("git", *args, cwd=self.root)

    def blob(self, ref, path):
        mode = self.git("ls-tree", ref, "--", path)
        if not mode:
            return None
        require(mode.startswith("100644 blob "), f"Only regular source files are supported: {path}")
        return subprocess.check_output(["git", "show", f"{ref}:{path}"], cwd=self.root)

    def read_json(self, ref, path):
        return json.loads(self.blob(ref, path))

    def ancestor(self, older, newer):
        return subprocess.run(["git", "merge-base", "--is-ancestor", older, newer], cwd=self.root).returncode == 0

    def changed(self, base, head):
        raw = subprocess.check_output(["git", "diff", "--name-only", "-z", base, head], cwd=self.root)
        return [path.decode() for path in raw.split(b"\0") if path]


def policy(repo, ref="HEAD"):
    value = repo.read_json(ref, POLICY)
    require(value["schema"] == 1, "Unsupported sync policy")
    require(value["repository"] == "BeatsAD/hongguoTV" and value["upstream"] == "N3urda/hongguoTV", "Unexpected repositories")
    for path in value["automaticPaths"]:
        require(re.fullmatch(r"kotlin-tv/core/src/main/kotlin/com/hongguotv/core/[A-Za-z][A-Za-z0-9]*\.kt", path), "Unsafe source path")
    return value


def fetch_upstream(repo, config):
    repo.git("fetch", "--no-tags", f'https://github.com/{config["upstream"]}.git', f'main:{UPSTREAM_REF}')
    return repo.git("rev-parse", UPSTREAM_REF)


def merge_source(ours, base, theirs, directory):
    if theirs is None:
        return ours, "upstream-deletion"
    if any(value is not None and (b"\0" in value or len(value) > 1024 * 1024) for value in [ours, base, theirs]):
        return ours, "binary-or-oversized"
    try:
        for value in [ours, base, theirs]:
            if value is not None:
                value.decode("utf-8")
    except UnicodeDecodeError:
        return ours, "invalid-text-encoding"
    if ours is None:
        return (theirs, None) if base is None else (None, "locally-removed")
    if base is None:
        return (ours, None) if ours == theirs else (ours, "added-on-both-sides")
    paths = []
    for name, data in [("ours", ours), ("base", base), ("theirs", theirs)]:
        path = directory / name
        path.write_bytes(data)
        paths.append(str(path))
    result = subprocess.run(["git", "merge-file", "-p", *paths], capture_output=True)
    if result.returncode:
        return ours, "overlapping-changes"
    return result.stdout, None


def plan(repo, config, base_ref, target, work):
    state = repo.read_json(base_ref, STATE)
    require(state["schema"] == 1 and set(state["files"]) == set(config["automaticPaths"]), "Sync state does not match policy")
    require(re.fullmatch(r"[a-f0-9]{40}", target), "Pin an immutable upstream commit")
    require(repo.ancestor(state["observed"], target), "Upstream history changed; manual migration required")
    updated = {"schema": 1, "observed": target, "files": {}, "unresolved": {}}
    patches = {}
    for index, path in enumerate(config["automaticPaths"]):
        anchor = state["files"][path]
        require(repo.ancestor(anchor, target), f"Invalid path baseline: {path}")
        ours, old, new = [repo.blob(ref, path) for ref in [base_ref, anchor, target]]
        if old == new:
            updated["files"][path] = target
            continue
        directory = work / str(index)
        directory.mkdir(parents=True, exist_ok=True)
        merged, conflict = merge_source(ours, old, new, directory)
        if conflict:
            updated["files"][path] = anchor
            updated["unresolved"][path] = {"upstream": target, "reason": conflict}
        else:
            updated["files"][path] = target
            if merged != ours:
                patches[path] = merged
    all_changes = repo.changed(state["observed"], target)
    excluded = [path for path in all_changes if path.startswith("kotlin-tv/") and path not in config["automaticPaths"]]
    return updated, patches, excluded


def work_directory(repo):
    out = repo.root / "outputs"
    out.mkdir(exist_ok=True)
    # Deliberately retained. No recursive or bulk deletion under workspace policy.
    return Path(tempfile.mkdtemp(prefix="upstream-sync-", dir=out))


def verify_candidate(repo, config, base, head, branch, upstream_ref=UPSTREAM_REF):
    if not branch.startswith(config["candidatePrefix"]):
        return {"syncCandidate": False, "message": "Normal PR: human review and TV validation still required"}
    require(repo.ancestor(base, head), "Sync branch must include current main before approval")
    changed = repo.changed(base, head)
    require(set(changed) <= set(config["automaticPaths"]) | {STATE}, "UI, features, existing tests, signing and update policy must remain unchanged")
    for path in changed:
        require(repo.blob(head, path) is not None, f"Deletion is not allowed: {path}")
    state = repo.read_json(head, STATE)
    require(repo.ancestor(state["observed"], upstream_ref), "Candidate target is not from canonical upstream history")
    expected, patches, _ = plan(repo, config, base, state["observed"], work_directory(repo))
    require(state == expected, "Do not advance unresolved baselines or edit generated state by hand")
    require(set(changed) == set(patches) | {STATE}, "Unexpected or missing sync changes")
    for path, data in patches.items():
        require(repo.blob(head, path) == data, f"Three-way merge does not preserve local changes: {path}")
    require(bool(patches), "No compatible source patch; keep this proposal as a draft for manual migration")
    return {"syncCandidate": True, "changedSources": list(patches), "unresolved": state["unresolved"], "protectedFilesUnchanged": True}


def approved(reviews, head_sha, reviewer):
    decisions = {}
    for review in sorted(reviews, key=lambda value: value.get("submitted_at") or ""):
        if review["state"] in {"APPROVED", "CHANGES_REQUESTED", "DISMISSED"}:
            decisions[review["user"]["login"].casefold()] = review
    value = decisions.get(reviewer.casefold())
    return bool(value and value["state"] == "APPROVED" and value["commit_id"] == head_sha and not value["user"]["login"].endswith("[bot]"))


def checks_pass(checks, sha, required):
    latest = {}
    for check in checks:
        if check["head_sha"] == sha and check.get("app", {}).get("slug") == "github-actions":
            if check["name"] not in latest or check["id"] > latest[check["name"]]["id"]:
                latest[check["name"]] = check
    return all(name in latest and latest[name]["status"] == "completed" and latest[name]["conclusion"] == "success" for name in required)


def runtime_changed(paths):
    return any(path.startswith(("kotlin-tv/app/src/main/", "kotlin-tv/core/src/main/", "kotlin-tv/gradle/")) or path in {
        "kotlin-tv/app/build.gradle.kts", "kotlin-tv/core/build.gradle.kts", "kotlin-tv/build.gradle.kts",
        "kotlin-tv/settings.gradle.kts", "kotlin-tv/distribution/channel.properties"} for path in paths)


def review_gate(prs, reviews_by_number, sha, reviewer, changed_paths):
    if not runtime_changed(changed_paths):
        return None
    for pr in prs:
        if pr.get("merged_at") and pr["merge_commit_sha"] == sha and pr["base"]["ref"] == "main" and not pr.get("draft"):
            if approved(reviews_by_number.get(pr["number"], []), pr["head"]["sha"], reviewer):
                return pr["number"]
    return None


def reviewed_release(repo, config, sha):
    require(re.fullmatch(r"[a-f0-9]{40}", sha), "Invalid release commit")
    parents = repo.git("rev-list", "--parents", "-n", "1", sha).split()
    if len(parents) < 2:
        return None
    paths = repo.changed(parents[1], sha)
    if not runtime_changed(paths):
        return None
    prs = api(f'repos/{config["repository"]}/commits/{sha}/pulls?per_page=100')
    reviews = {pr["number"]: all_reviews(config["repository"], pr["number"]) for pr in prs if pr.get("merged_at")}
    return review_gate(prs, reviews, sha, config["reviewer"], paths)


def assert_repository(config):
    require(os.environ.get("GITHUB_REPOSITORY") == config["repository"], "Run automation only in the configured fork")


def ensure_verification(config, branch, sha):
    runs = api(f'repos/{config["repository"]}/actions/workflows/tv-release.yml/runs?head_sha={sha}&per_page=100')["workflow_runs"]
    events = {"push", "workflow_dispatch"} if branch == "main" else {"workflow_dispatch"}
    runs = [run for run in runs if run["head_sha"] == sha and run["event"] in events]
    if not runs:
        api(f'repos/{config["repository"]}/actions/workflows/tv-release.yml/dispatches', "POST", {"ref": branch})
        return True
    return False


def propose_local(config, branch):
    """Let a single repository owner review a bot-authored manual migration PR."""
    assert_repository(config)
    require(os.environ.get("GITHUB_ACTOR", "").casefold() == config["reviewer"].casefold(), "Only the owner may propose a local development branch")
    require(re.fullmatch(r"codex/[A-Za-z0-9][A-Za-z0-9._/-]*", branch) and ".." not in branch and not branch.startswith(config["candidatePrefix"]), "Use a separate codex/ development branch")
    head = api(f'repos/{config["repository"]}/branches/{quote(branch, safe="")}')["commit"]["sha"]
    existing = api(f'repos/{config["repository"]}/pulls?state=open&head={config["repository"].split("/")[0]}:{branch}&per_page=100')
    if existing:
        pr = existing[0]
    else:
        pr = api(f'repos/{config["repository"]}/pulls', "POST", {"title": f"审核本地改动：{branch}", "head": branch, "base": "main", "body": f"## 问题与结果\n\n由仓库所有者指定开发分支，机器人创建 PR 以便单人仓库的 @{config['reviewer']} 审核。\n\n## 主要修改\n\n请根据本分支的实际代码差异补充功能、兼容性及数据保留影响。\n\n## 验证\n\n等待 TV verification 与 Upstream preservation 通过，之后由所有者审核并手动合并。应用代码变更合并后自动签名发布；配置或说明变更不产生 APK。设备验收范围需按实际填写。\n\n## 提交前确认\n\n- [ ] 已核对功能、界面和兼容性。\n- [ ] 无密钥或私人数据，来源与许可证完整。\n- [ ] 已说明升级和数据保留影响。"})
    ensure_verification(config, branch, head)
    print(pr["html_url"])


def detect(repo, config, upstream_target=None):
    assert_repository(config)
    target = upstream_target or fetch_upstream(repo, config)
    state = repo.read_json("HEAD", STATE)
    work = work_directory(repo)
    native_changes = [path for path in repo.changed(state["observed"], target) if path.startswith("kotlin-tv/")]
    audit = {"upstream": target, "nativeChanges": native_changes, "manualReviewPaths": [path for path in native_changes if path not in config["automaticPaths"]]}
    (work / "report.json").write_text(json.dumps(audit, indent=2))
    if audit["manualReviewPaths"]:
        print(f'::notice::Detected {len(audit["manualReviewPaths"])} native paths outside automatic scope; see detection artifact. Local files retained.')
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as summary:
            summary.write(f'检测上游 `{target}`：原生变更 {len(native_changes)} 项，其中 {len(audit["manualReviewPaths"])} 项需要人工决定是否移植；清单保存在工作流附件。\n')
    existing = api(f'repos/{config["repository"]}/pulls?state=open&per_page=100')
    for pr in existing:
        if (pr["head"].get("repo") or {}).get("full_name") == config["repository"] and pr["head"]["ref"].startswith(config["candidatePrefix"]):
            if not pr["draft"]:
                ensure_verification(config, pr["head"]["ref"], pr["head"]["sha"])
            print(f'Existing proposal retained without overwriting human changes: {pr["html_url"]}')
            return
    if all(repo.blob(state["observed"], path) == repo.blob(target, path) for path in config["automaticPaths"]):
        print("No new changes in automatically synchronized core paths.")
        return
    updated, patches, excluded = plan(repo, config, "HEAD", target, work)
    fingerprints = {path: hashlib.sha256(repo.blob(target, path) or b"<missing>").hexdigest() for path in config["automaticPaths"]}
    signature = hashlib.sha256(json.dumps(fingerprints, sort_keys=True).encode()).hexdigest()[:16]
    branch = config["candidatePrefix"] + signature
    previous = api(f'repos/{config["repository"]}/pulls?state=all&head={config["repository"].split("/")[0]}:{branch}&per_page=100')
    if previous:
        print(f'Proposal already exists or was closed; not recreating it: {previous[0]["html_url"]}')
        return
    require(not repo.git("status", "--porcelain"), "Start from a clean main checkout")
    repo.git("switch", "-c", branch)
    for path, data in patches.items():
        destination = repo.root / path
        require(not destination.is_symlink(), "Do not follow source symlinks")
        require(destination.resolve().is_relative_to(repo.root.resolve()), "Do not follow directories outside the checkout")
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(data)
    (repo.root / STATE).write_text(json.dumps(updated, indent=2) + "\n")
    repo.git("add", "--", *patches.keys(), STATE)
    repo.git("config", "user.name", "github-actions[bot]")
    repo.git("config", "user.email", "41898282+github-actions[bot]@users.noreply.github.com")
    repo.git("commit", "-m", f'同步上游核心修复 {target[:12]}，保留本地界面与功能')
    sha = repo.git("rev-parse", "HEAD")
    repo.git("config", "credential.https://github.com.helper", "!gh auth git-credential")
    repo.git("push", f'https://github.com/{config["repository"]}.git', f'HEAD:refs/heads/{branch}')
    clean = "\n".join(f"- `{path}`" for path in patches) or "- 暂无可直接合并的源码，需要人工移植。"
    unresolved = "\n".join(f'- `{path}`：{value["reason"]}；原文件保留，未推进该文件的同步基线。' for path, value in updated["unresolved"].items()) or "- 无。"
    body = f'''## 问题与结果

自动检测到上游核心改动，目标提交：[N3urda/hongguoTV@{target[:12]}](https://github.com/{config["upstream"]}/commit/{target})。

## 主要修改

可兼容合并的源码：
{clean}

保留的重叠冲突：
{unresolved}

界面、遥控交互、现有功能测试、包名、签名、更新渠道和依赖配置均保持 main 原样。使用逐文件三方合并；未整体覆盖上游文件。

本次另有 {len(excluded)} 个上游原生路径未自动引入，包括界面、新功能、依赖和测试。完整清单见本次检测工作流附件，需要移植时另行审核。

## 验证

自动化检查：等待本 PR 的 **TV verification** 与 **Upstream preservation** 完成。未操作模拟器或实体电视。

必须先通过 **TV verification** 与 **Upstream preservation**，再由 @{config["reviewer"]} 审核当前提交。机器人不会代替人审批；审核前不合并、不发布。审核通过后控制工作流自动合并并触发签名发布。

本 PR 只同步上面列出的兼容部分；待移植项没有被标记为已修复。自动测试不代表实体电视验收。

## 提交前确认

- [x] 修改范围集中，未引入上游界面、新功能或依赖更新。
- [x] 未提交密钥、设备身份、私人备份、APK 或调试日志。
- [x] 保留 GPL 来源与许可证声明。
- [x] 包名、签名、数据格式与覆盖升级策略保持不变。
- [x] 本 PR 为普通兼容性修复，不披露未修复的安全漏洞。
'''
    (work / "proposal.md").write_text(body)
    (work / "report.json").write_text(json.dumps(audit | {"candidate": sha, "applied": list(patches), "unresolved": updated["unresolved"], "excluded": excluded}, indent=2))
    pr = api(f'repos/{config["repository"]}/pulls', "POST", {"title": f'同步上游兼容核心修复 {target[:12]}，保留定制界面', "head": branch, "base": "main", "body": body, "draft": not bool(patches)})
    api(f'repos/{config["repository"]}/issues/{pr["number"]}/labels', "POST", {"labels": ["upstream-sync"]})
    if patches:
        ensure_verification(config, branch, sha)
    print(pr["html_url"])


def reconcile(repo, config):
    assert_repository(config)
    branch = api(f'repos/{config["repository"]}/branches/main')
    require(branch["protected"], "Protect main before enabling automatic merges")
    prs = api(f'repos/{config["repository"]}/pulls?state=open&per_page=100')
    for item in prs:
        if item["draft"] or (item["head"].get("repo") or {}).get("full_name") != config["repository"] or not item["head"]["ref"].startswith(config["candidatePrefix"]):
            continue
        pr = api(f'repos/{config["repository"]}/pulls/{item["number"]}')
        reviews = all_reviews(config["repository"], pr["number"])
        if not approved(reviews, pr["head"]["sha"], config["reviewer"]):
            print(f'PR #{pr["number"]}: waiting for human approval of current SHA.')
            continue
        checks = api(f'repos/{config["repository"]}/commits/{pr["head"]["sha"]}/check-runs?per_page=100')["check_runs"]
        if not checks_pass(checks, pr["head"]["sha"], config["requiredChecks"]):
            print(f'PR #{pr["number"]}: waiting for required tests.')
            continue
        repo.git("fetch", "origin", f'refs/heads/{pr["head"]["ref"]}')
        fetch_upstream(repo, config)
        verify_candidate(repo, config, "HEAD", pr["head"]["sha"], pr["head"]["ref"])
        result = api(f'repos/{config["repository"]}/pulls/{pr["number"]}/merge', "PUT", {"sha": pr["head"]["sha"], "merge_method": "merge"})
        require(result["merged"], "Server did not merge the reviewed candidate")
        print(f'Merged reviewed PR #{pr["number"]}; scheduling release explicitly.')
    # A token-created merge does not trigger push workflows. Reconcile even if a prior dispatch failed.
    repo.git("fetch", "origin", "main")
    main_sha = repo.git("rev-parse", "FETCH_HEAD")
    number = reviewed_release(repo, config, main_sha)
    if number and ensure_verification(config, "main", main_sha):
        print(f'Dispatched verification and release for reviewed PR #{number}.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["detect", "reconcile", "verify", "release-check", "plan", "propose-local"])
    parser.add_argument("--base", default="origin/main")
    parser.add_argument("--head", default="HEAD")
    parser.add_argument("--branch", default="")
    parser.add_argument("--sha")
    args = parser.parse_args()
    repo = Repo()
    config = policy(repo)
    if args.command == "propose-local":
        propose_local(config, args.branch)
    elif args.command == "detect":
        detect(repo, config)
    elif args.command == "reconcile":
        reconcile(repo, config)
    elif args.command == "verify":
        target = fetch_upstream(repo, config)
        head = repo.git("rev-parse", args.head)
        print(json.dumps(verify_candidate(repo, config, args.base, head, args.branch), ensure_ascii=False))
    elif args.command == "release-check":
        number = reviewed_release(repo, config, args.sha or repo.git("rev-parse", "HEAD"))
        print("approved=" + str(bool(number)).lower())
        print("pull_request=" + str(number or ""))
    else:
        target = fetch_upstream(repo, config)
        state, patches, excluded = plan(repo, config, "HEAD", target, work_directory(repo))
        print(json.dumps({"upstream": target, "applied": list(patches), "unresolved": state["unresolved"], "excludedCount": len(excluded)}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
