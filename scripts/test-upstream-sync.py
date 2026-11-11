import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("upstream_sync", Path(__file__).with_name("upstream-sync.py"))
sync = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sync)
SOURCE = "kotlin-tv/core/src/main/kotlin/com/hongguotv/core/Bytes.kt"
UI = "kotlin-tv/app/src/main/kotlin/com/hongguotv/nativeapp/MainActivity.kt"
TEST = "kotlin-tv/core/src/test/kotlin/com/hongguotv/core/ExistingFeatureTest.kt"
CONFIG = {"schema": 1, "repository": "BeatsAD/hongguoTV", "upstream": "N3urda/hongguoTV", "reviewer": "BeatsAD",
          "automaticPaths": [SOURCE], "candidatePrefix": "codex/upstream-sync/", "requiredChecks": ["TV verification", "Upstream preservation"]}


class Fixture:
    def __init__(self, base_source=None, local_source=None, upstream_source=None, upstream_delete=False):
        # Retained under outputs. Fixtures create Git objects without checking out/deleting files.
        out = sync.ROOT / "outputs"
        out.mkdir(exist_ok=True)
        self.root = Path(tempfile.mkdtemp(prefix="sync-fixture-", dir=out))
        subprocess.run(["git", "init", "--bare", "--quiet", str(self.root)], check=True)
        self.repo = sync.Repo(self.root)
        self.sequence = 0
        self.files = {UI: b"our interface\n", TEST: b"our feature contract\n"}
        if base_source is not None:
            self.files[SOURCE] = base_source
        self.base = self.commit(self.files, "common ancestor")
        self.local = dict(self.files)
        self.local[SOURCE] = local_source
        self.state = {"schema": 1, "observed": self.base, "files": {SOURCE: self.base}, "unresolved": {}}
        self.local[sync.STATE] = self.json(self.state)
        self.local[sync.POLICY] = self.json(CONFIG)
        self.main = self.commit(self.local, "our main", self.base)
        upstream = dict(self.files)
        if not upstream_delete:
            upstream[SOURCE] = upstream_source
        else:
            upstream = {key: value for key, value in upstream.items() if key != SOURCE}
        self.target = self.commit(upstream, "upstream patch", self.base)
        self.repo.git("update-ref", sync.UPSTREAM_REF, self.target)
        self.work = self.root / "retained-merge-evidence"
        self.work.mkdir()

    @staticmethod
    def json(value):
        return (json.dumps(value, indent=2) + "\n").encode()

    def commit(self, files, message, parent=None):
        self.sequence += 1
        env = dict(os.environ, GIT_INDEX_FILE=str(self.root / f"retained-index-{self.sequence}"),
                   GIT_AUTHOR_NAME="Sync tests", GIT_AUTHOR_EMAIL="sync@example.invalid",
                   GIT_COMMITTER_NAME="Sync tests", GIT_COMMITTER_EMAIL="sync@example.invalid")
        def git(*args, data=None):
            return subprocess.check_output(["git", *args], cwd=self.root, env=env, input=data).decode().strip()
        git("read-tree", "--empty")
        for name, value in files.items():
            if value is None:
                continue
            mode, content = value if isinstance(value, tuple) else ("100644", value)
            digest = git("hash-object", "-w", "--stdin", data=content)
            git("update-index", "--add", "--cacheinfo", mode, digest, name)
        tree = git("write-tree")
        return git("commit-tree", tree, *(["-p", parent] if parent else []), "-m", message)

    def candidate(self):
        state, patches, excluded = sync.plan(self.repo, CONFIG, self.main, self.target, self.work)
        files = self.local | patches | {sync.STATE: self.json(state)}
        return state, patches, files


class MergeTests(unittest.TestCase):
    def fixture(self):
        lines = [f"line {i}\n" for i in range(20)]
        old = "".join(lines).encode()
        ours = old.replace(b"line 1\n", b"our local feature\n")
        theirs = old.replace(b"line 18\n", b"upstream repair\n")
        return Fixture(old, ours, theirs)

    def test_non_overlapping_upstream_patch_keeps_our_feature(self):
        f = self.fixture()
        state, patches, files = f.candidate()
        self.assertIn(b"our local feature", patches[SOURCE])
        self.assertIn(b"upstream repair", patches[SOURCE])
        head = f.commit(files, "candidate", f.main)
        result = sync.verify_candidate(f.repo, CONFIG, f.main, head, "codex/upstream-sync/fixture")
        self.assertTrue(result["protectedFilesUnchanged"])
        self.assertEqual(f.repo.blob(f.main, UI), f.repo.blob(head, UI))
        self.assertEqual(f.repo.blob(f.main, TEST), f.repo.blob(head, TEST))

    def test_overlap_preserves_ours_and_does_not_advance_baseline(self):
        f = Fixture(b"old\n", b"our feature\n", b"different upstream behavior\n")
        state, patches, _ = f.candidate()
        self.assertFalse(patches)
        self.assertEqual(f.base, state["files"][SOURCE])
        self.assertEqual("overlapping-changes", state["unresolved"][SOURCE]["reason"])
        self.assertEqual(b"our feature\n", f.repo.blob(f.main, SOURCE))

    def test_upstream_deletion_is_reported_without_deleting_local_file(self):
        f = Fixture(b"old\n", b"our feature\n", upstream_delete=True)
        state, patches, _ = f.candidate()
        self.assertFalse(patches)
        self.assertEqual("upstream-deletion", state["unresolved"][SOURCE]["reason"])
        self.assertEqual(b"our feature\n", f.repo.blob(f.main, SOURCE))

    def test_independent_additions_do_not_overwrite_our_implementation(self):
        f = Fixture(None, b"our implementation\n", b"upstream implementation\n")
        state, patches, _ = f.candidate()
        self.assertFalse(patches)
        self.assertEqual("added-on-both-sides", state["unresolved"][SOURCE]["reason"])

    def test_app_ui_and_existing_tests_cannot_be_changed(self):
        for path in [UI, TEST, sync.POLICY, "kotlin-tv/distribution/channel.properties"]:
            f = self.fixture()
            _, _, files = f.candidate()
            files[path] = b"unexpected override\n"
            head = f.commit(files, "bad candidate", f.main)
            with self.assertRaisesRegex(ValueError, "must remain unchanged"):
                sync.verify_candidate(f.repo, CONFIG, f.main, head, "codex/upstream-sync/fixture")

    def test_wholesale_replacement_cannot_discard_our_feature(self):
        f = self.fixture()
        _, _, files = f.candidate()
        files[SOURCE] = f.repo.blob(f.target, SOURCE)
        head = f.commit(files, "discard local changes", f.main)
        with self.assertRaisesRegex(ValueError, "does not preserve local changes"):
            sync.verify_candidate(f.repo, CONFIG, f.main, head, "codex/upstream-sync/fixture")

    def test_unresolved_state_cannot_be_marked_as_synchronized(self):
        f = Fixture(b"old\n", b"ours\n", b"theirs\n")
        state, _, files = f.candidate()
        state["files"][SOURCE] = f.target
        state["unresolved"] = {}
        files[sync.STATE] = f.json(state)
        head = f.commit(files, "fake resolution", f.main)
        with self.assertRaisesRegex(ValueError, "unresolved baselines"):
            sync.verify_candidate(f.repo, CONFIG, f.main, head, "codex/upstream-sync/fixture")

    def test_binary_and_invalid_encoding_need_manual_migration(self):
        for value in [b"bad\0binary", b"\xff", b"x" * (1024 * 1024 + 1)]:
            f = Fixture(b"old\n", b"ours\n", value)
            state, patches, _ = f.candidate()
            self.assertFalse(patches)
            self.assertIn(SOURCE, state["unresolved"])

    def test_symlink_sources_are_not_followed(self):
        f = self.fixture()
        bad = f.commit(f.local | {SOURCE: ("120000", b"/private/key")}, "symlink", f.main)
        with self.assertRaisesRegex(ValueError, "regular source files"):
            f.repo.blob(bad, SOURCE)

    def test_noncanonical_history_and_metadata_only_candidates_are_blocked(self):
        f = Fixture(b"old\n", b"ours\n", b"theirs\n")
        _, _, files = f.candidate()
        head = f.commit(files, "manual migration proposal", f.main)
        with self.assertRaisesRegex(ValueError, "keep this proposal as a draft"):
            sync.verify_candidate(f.repo, CONFIG, f.main, head, "codex/upstream-sync/fixture")
        f.repo.git("update-ref", sync.UPSTREAM_REF, f.base)
        with self.assertRaisesRegex(ValueError, "canonical upstream history"):
            sync.verify_candidate(f.repo, CONFIG, f.main, head, "codex/upstream-sync/fixture")


class ReviewTests(unittest.TestCase):
    def review(self, state="APPROVED", sha="candidate", login="BeatsAD", date="2026-11-11T01:00:00Z"):
        return {"state": state, "commit_id": sha, "user": {"login": login}, "submitted_at": date}

    def test_only_owner_approval_of_current_sha_counts(self):
        self.assertTrue(sync.approved([self.review()], "candidate", "BeatsAD"))
        self.assertFalse(sync.approved([], "candidate", "BeatsAD"))
        self.assertFalse(sync.approved([self.review(sha="old")], "candidate", "BeatsAD"))
        self.assertFalse(sync.approved([self.review(login="stranger")], "candidate", "BeatsAD"))
        self.assertFalse(sync.approved([self.review(login="robot[bot]")], "candidate", "robot[bot]"))

    def test_later_review_pages_cannot_hide_a_dismissal(self):
        with patch.object(sync, 'command', return_value=json.dumps([[self.review()], [self.review('DISMISSED',date='2026-11-11T03:00:00Z')]])) as command:
            reviews=sync.all_reviews('BeatsAD/hongguoTV',7)
            self.assertIn('--paginate',command.call_args.args)
            self.assertFalse(sync.approved(reviews,'candidate','BeatsAD'))

    def test_comment_does_not_erase_approval_but_dismissal_and_changes_do(self):
        for state, expected in [("COMMENTED", True), ("DISMISSED", False), ("CHANGES_REQUESTED", False)]:
            values = [self.review(), self.review(state, date="2026-11-11T02:00:00Z")]
            self.assertEqual(expected, sync.approved(values, "candidate", "BeatsAD"))

    def test_all_latest_checks_for_exact_sha_must_pass(self):
        checks = [{"id": i, "name": name, "head_sha": "candidate", "status": "completed", "conclusion": "success", "app": {"slug": "github-actions"}} for i, name in enumerate(CONFIG["requiredChecks"], 1)]
        self.assertTrue(sync.checks_pass(checks, "candidate", CONFIG["requiredChecks"]))
        self.assertFalse(sync.checks_pass(checks, "new-head", CONFIG["requiredChecks"]))
        self.assertFalse(sync.checks_pass(checks[:1], "candidate", CONFIG["requiredChecks"]))
        for changes in [{"status": "in_progress"}, {"conclusion": "failure"}, {"conclusion": "skipped"}, {"app": {"slug": "other"}}]:
            modified = [checks[0] | changes, checks[1]]
            self.assertFalse(sync.checks_pass(modified, "candidate", CONFIG["requiredChecks"]))
        self.assertFalse(sync.checks_pass(checks + [checks[0] | {"id": 9, "conclusion": "failure"}], "candidate", CONFIG["requiredChecks"]))

    def test_only_approved_merged_app_changes_can_release(self):
        pr = {"number": 7, "merged_at": "now", "merge_commit_sha": "main", "base": {"ref": "main"}, "head": {"sha": "candidate"}, "draft": False}
        self.assertEqual(7, sync.review_gate([pr], {7: [self.review()]}, "main", "BeatsAD", [SOURCE]))
        for value in [pr | {"merged_at": None}, pr | {"draft": True}, pr | {"merge_commit_sha": "old"}]:
            self.assertIsNone(sync.review_gate([value], {7: [self.review()]}, "main", "BeatsAD", [SOURCE]))
        self.assertIsNone(sync.review_gate([pr], {}, "main", "BeatsAD", [SOURCE]))
        self.assertIsNone(sync.review_gate([pr], {7: [self.review()]}, "main", "BeatsAD", [sync.STATE, "scripts/upstream-sync.py"]))

    def test_approved_apk_build_input_only_fixes_can_publish(self):
        pr = {"number":7,"merged_at":"now","merge_commit_sha":"main","base":{"ref":"main"},"head":{"sha":"candidate"},"draft":False}
        for path in ['kotlin-tv/app/proguard-rules.pro','kotlin-tv/gradle.properties','kotlin-tv/app/src/distribution/AndroidManifest.xml']:
            self.assertEqual(7,sync.review_gate([pr],{7:[self.review()]},'main','BeatsAD',[path]))

    def test_token_created_pr_explicitly_dispatches_validation(self):
        pending = {"workflow_runs": [{"event": "pull_request", "head_sha": "candidate"}]}
        with patch.object(sync, "api", side_effect=[pending, None]) as api:
            self.assertTrue(sync.ensure_verification(CONFIG, "codex/upstream-sync/test", "candidate"))
            self.assertEqual({"ref": "codex/upstream-sync/test"}, api.call_args.args[2])

    def test_existing_validation_or_main_push_does_not_dispatch_twice(self):
        for branch, event in [("codex/upstream-sync/test", "workflow_dispatch"), ("main", "push")]:
            with patch.object(sync, "api", return_value={"workflow_runs": [{"event": event, "head_sha": "candidate"}]}) as api:
                self.assertFalse(sync.ensure_verification(CONFIG, branch, "candidate"))
                self.assertEqual(1, api.call_count)

    def test_history_query_cannot_suppress_checks_for_a_new_head(self):
        with patch.object(sync, "api", side_effect=[{"workflow_runs": [{"event": "workflow_dispatch", "head_sha": "old"}]}, None]):
            self.assertTrue(sync.ensure_verification(CONFIG, "codex/upstream-sync/test", "candidate"))

    def ci_proposal(self):
        pr = {"number": 4, "draft": False, "user": {"login": "github-actions[bot]"}, "head": {"sha": "candidate", "ref": "codex/upstream-sync/test", "repo": {"full_name": CONFIG["repository"]}}}
        run = {"id": 9, "head_sha": "candidate", "head_branch": pr["head"]["ref"], "event": "pull_request", "conclusion": "action_required", "path": ".github/workflows/tv-release.yml", "head_repository": {"full_name": CONFIG["repository"]}, "pull_requests": [{"number": 4}]}
        return pr, run

    def test_native_ci_activation_recomputes_preservation_before_workflow_approval(self):
        pr, run = self.ci_proposal()
        repo = unittest.mock.Mock()
        repo.git.return_value = "trusted-main"
        with patch.object(sync, "api", side_effect=[{"workflow_runs": [run]}, None]) as api, patch.object(sync, "fetch_upstream"), patch.object(sync, "verify_candidate") as verify, patch("builtins.print"):
            self.assertTrue(sync.activate_candidate_checks(repo, CONFIG, pr))
            verify.assert_called_once_with(repo, CONFIG, "trusted-main", "candidate", pr["head"]["ref"])
            self.assertEqual(("repos/BeatsAD/hongguoTV/actions/runs/9/approve", "POST"), api.call_args.args)

    def test_modified_or_stale_proposal_cannot_activate_ci(self):
        pr, run = self.ci_proposal()
        with patch.object(sync, "api", return_value={"workflow_runs": [run]}) as api, patch.object(sync, "fetch_upstream"), patch.object(sync, "verify_candidate", side_effect=ValueError("preservation failed")):
            with self.assertRaisesRegex(ValueError, "preservation failed"):
                sync.activate_candidate_checks(unittest.mock.Mock(), CONFIG, pr)
            self.assertEqual(1, api.call_count)

    def test_unrelated_workflows_and_already_started_checks_are_not_approved(self):
        pr, run = self.ci_proposal()
        for change in [{"head_sha": "old"}, {"head_branch": "foreign"}, {"event": "workflow_dispatch"}, {"conclusion": None}, {"path": ".github/workflows/other.yml"}, {"head_repository": {"full_name": "other/repo"}}, {"pull_requests": []}]:
            with self.subTest(change=change), patch.object(sync, "api", return_value={"workflow_runs": [run | change]}) as api:
                self.assertFalse(sync.activate_candidate_checks(unittest.mock.Mock(), CONFIG, pr))
                self.assertEqual(1, api.call_count)

    def test_manual_or_foreign_proposals_need_owner_to_allow_ci(self):
        pr, _ = self.ci_proposal()
        for change in [{"draft": True}, {"user": {"login": "BeatsAD"}}, {"head": pr["head"] | {"ref": "codex/manual"}}, {"head": pr["head"] | {"repo": {"full_name": "other/repo"}}}]:
            with patch.object(sync, "api") as api:
                self.assertFalse(sync.activate_candidate_checks(unittest.mock.Mock(), CONFIG, pr | change))
                api.assert_not_called()

    def test_manual_development_pr_is_bot_authored_and_owner_requested(self):
        with patch('builtins.print'), patch.dict(os.environ, {'GITHUB_REPOSITORY':CONFIG['repository'],'GITHUB_ACTOR':'BeatsAD'}), patch.object(sync, 'api', side_effect=[{'commit':{'sha':'candidate'}},[],{'html_url':'https://github.com/BeatsAD/hongguoTV/pull/7'},{'workflow_runs':[]},None]) as api:
            sync.propose_local(CONFIG, 'codex/manual-migration')
            self.assertEqual('codex/manual-migration', api.call_args_list[2].args[2]['head'])
            self.assertEqual({'ref':'codex/manual-migration'}, api.call_args_list[-1].args[2])

    def test_manual_proposal_cannot_be_requested_by_strangers_or_spoof_auto_branch(self):
        with patch.dict(os.environ, {'GITHUB_REPOSITORY':CONFIG['repository'],'GITHUB_ACTOR':'stranger'}):
            with self.assertRaisesRegex(ValueError, 'Only the owner'):
                sync.propose_local(CONFIG, 'codex/manual-migration')
        with patch.dict(os.environ, {'GITHUB_REPOSITORY':CONFIG['repository'],'GITHUB_ACTOR':'BeatsAD'}):
            for branch in ['main','../main','codex/../main','codex/upstream-sync/fake']:
                with self.assertRaises(ValueError):
                    sync.propose_local(CONFIG, branch)


if __name__ == "__main__":
    unittest.main()
