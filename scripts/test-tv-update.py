import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("tv_update", Path(__file__).with_name("tv-update.py"))
update = importlib.util.module_from_spec(spec)
spec.loader.exec_module(update)


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        # Version-ordering examples use a fixed fixture, independently of the
        # checkout's next release version. Identity still comes from the channel.
        self.config = update.channel() | {"versionName": "1.0.0"}
        channel_fixture = patch.object(update, "channel", return_value=self.config)
        channel_fixture.start()
        self.addCleanup(channel_fixture.stop)
        self.code, self.name, self.tag = update.version(self.config, 1)
        self.badging = (f"package: name='{self.config['applicationId']}' versionCode='{self.code}' versionName='{self.name}'\n"
                        f"sdkVersion:'{self.config['minSdk']}'\napplication-label:'hongguo'\nuses-permission: name='android.permission.REQUEST_INSTALL_PACKAGES'\n")
        self.certificate = f"Signer #1 certificate SHA-256 digest: {self.config['signerSha256']}\n"
        self.record = {"schema": 2, "packageName": self.config["applicationId"], "versionCode": self.code,
                       "versionName": self.name, "minSdk": 26, "tag": self.tag, "sourceCommit": "a" * 40,
                       "certificateSha256": self.config["signerSha256"], "reviewedPullRequest": "https://github.com/BeatsAD/hongguoTV/pull/7"}

    def release(self, code=None, name=None, draft=False):
        metadata = {k: v for k, v in self.record.items() if k not in ["tag", "certificateSha256"]}
        metadata["versionCode"] = self.code if code is None else code
        metadata["versionName"] = self.name if name is None else name
        return {"tag_name": "v" + metadata["versionName"], "draft": draft,
                "body": update.MARKER + json.dumps(metadata) + " -->", "assets": []}

    def check(self, badging=None, certificate=None):
        update.verify_badging(self.config, badging or self.badging, certificate or self.certificate, self.code, self.name)

    def test_current_identity_and_clean_public_version_are_accepted(self):
        self.check()
        self.assertEqual("1.0.0", self.name)
        self.assertEqual("v1.0.0", self.tag)

    def test_wrong_package_is_rejected(self):
        with self.assertRaises(AssertionError):
            self.check(self.badging.replace(self.config['applicationId'], 'com.hongguotv.nativeapp'))

    def test_wrong_signer_is_rejected(self):
        with self.assertRaises(AssertionError):
            self.check(certificate=self.certificate.replace(self.config['signerSha256'], '0' * 64))

    def test_multiple_signers_are_rejected(self):
        with self.assertRaises(AssertionError):
            self.check(certificate=self.certificate + self.certificate.replace('#1', '#2'))

    def test_wrong_version_sdk_or_old_suffix_are_rejected(self):
        for badging in [self.badging.replace(str(self.code), '53'), self.badging.replace(self.name, '0.1.0'),
                        self.badging.replace(self.name, self.name + '-playbackfix'), self.badging.replace("sdkVersion:'26'", "sdkVersion:'21'")]:
            with self.assertRaises(AssertionError):
                self.check(badging)

    def test_debuggable_missing_permission_or_wrong_display_name_are_rejected(self):
        for badging in [self.badging + 'application-debuggable\n', self.badging.replace('android.permission.REQUEST_INSTALL_PACKAGES', 'other'),
                        self.badging.replace("application-label:'hongguo'", "application-label:'other'")]:
            with self.assertRaises(AssertionError):
                self.check(badging)

    def test_version_numbers_exceed_legacy_and_recovered_history(self):
        self.assertGreater(self.code, 10003)
        self.assertGreater(update.version(self.config, 2)[0], self.code)
        self.assertEqual(50001, update.version(self.config, 1, [50000])[0])
        with self.assertRaises(AssertionError):
            update.version(self.config, 0)
        with self.assertRaises(AssertionError):
            update.version(self.config, 1, [2147483647])

    def test_allocator_accounts_for_legacy_modern_and_draft_builds(self):
        releases = [{"tag_name": "kotlin-v0.10.5-build.10003", "draft": False, "body": ""}, self.release(40000, "0.99.0", True)]
        self.assertEqual(40001, update.allocate(self.config, 1, releases, "a" * 40)[0])

    def test_public_version_cannot_be_rebuilt_or_overwritten(self):
        with self.assertRaises(AssertionError):
            update.allocate(self.config, 99, [self.release()], "a" * 40)

    def test_same_source_draft_reuses_reserved_build_number(self):
        self.assertEqual(self.code, update.allocate(self.config, 99, [self.release(draft=True)], "a" * 40)[0])
        with self.assertRaises(AssertionError):
            update.allocate(self.config, 99, [self.release(draft=True)], "b" * 40)
        with self.assertRaises(AssertionError):
            update.allocate(self.config, 99, [self.release(draft=True), self.release(self.code + 1, "0.99.0")], "a" * 40)

    def test_old_runs_or_lower_public_versions_never_replace_latest(self):
        self.assertTrue(update.promote(self.record, None))
        self.assertTrue(update.promote(self.record, {"tag_name": "kotlin-v0.10.5-build.10003", "body": ""}))
        self.assertFalse(update.promote(self.record, self.release()))
        self.assertFalse(update.promote(self.record, self.release(self.code + 1, "1.0.1")))
        self.assertFalse(update.promote(self.record, self.release(self.code - 1, "1.1.0")))

    def test_unrelated_or_malformed_latest_is_not_overwritten(self):
        for release in [{"tag_name": "upstream-v99", "body": ""}, {"tag_name": "v1.0.0", "body": update.MARKER + "not-json -->"}]:
            with self.assertRaises((AssertionError, ValueError)):
                update.promote(self.record, release)

    def test_notes_contain_both_sections_and_hidden_machine_metadata(self):
        body = update.release_notes(self.config, self.record)
        self.assertTrue(body.startswith("## 新增功能\n"))
        self.assertNotIn(f"# hongguo {self.name}", body.split(update.MARKER)[0])
        self.assertEqual(["新增功能", "问题修复"], update.re.findall(r"^## (.+)$", body, update.re.M))
        metadata = update.release_metadata({"tag_name": self.tag, "body": body})
        self.assertEqual(self.code, metadata["versionCode"])
        self.assertEqual(self.name, metadata["versionName"])
        self.assertNotIn(str(self.code), body.split(update.MARKER)[0])

    def test_notes_without_real_sections_are_rejected(self):
        for text in ["## 新增功能\n- x\n", "## 新增功能\n\n## 问题修复\n- x\n", "## 新增功能\n- x\n\n## 问题修复\n- x\n\n## extra\n- x"]:
            with patch.object(Path, 'read_text', return_value=text), self.assertRaises(AssertionError):
                update.release_notes(self.config, self.record)

    def test_missing_review_blocks_publication_before_upload(self):
        root = Path(tempfile.mkdtemp(prefix='tv-review-test-'))
        path = root / 'release-record.json'
        path.write_text(json.dumps(self.record))
        with patch.dict(os.environ, {'GITHUB_REPOSITORY': self.config['repository'], 'GITHUB_REF': 'refs/heads/main'}), patch.object(update, 'run', side_effect=['a' * 40, 'approved=false\npull_request=']) as run:
            with self.assertRaisesRegex(AssertionError, 'Human review'):
                update.publish(path)
            self.assertEqual(2, run.call_count)

    def test_release_metadata_must_link_the_actual_approved_pr(self):
        root = Path(tempfile.mkdtemp(prefix='tv-review-link-test-'))
        path = root / 'release-record.json'
        path.write_text(json.dumps(dict(self.record, reviewedPullRequest='https://github.com/BeatsAD/hongguoTV/pull/99')))
        with patch.dict(os.environ, {'GITHUB_REPOSITORY': self.config['repository'], 'GITHUB_REF': 'refs/heads/main'}), patch.object(update, 'run', side_effect=['a' * 40, 'approved=true\npull_request=7']):
            with self.assertRaisesRegex(AssertionError, 'provenance mismatch'):
                update.publish(path)

    def test_api_errors_do_not_look_like_missing_releases(self):
        import subprocess
        with patch.object(update.subprocess, 'run', return_value=subprocess.CompletedProcess([], 1, '', 'gh: Forbidden (HTTP 403)')):
            with self.assertRaises(AssertionError):
                update.get_release(self.config['repository'])
        with patch.object(update.subprocess, 'run', return_value=subprocess.CompletedProcess([], 1, '', 'gh: Not Found (HTTP 404)')):
            self.assertIsNone(update.get_release(self.config['repository']))

    def test_packaging_uses_same_sdk_as_gradle_local_properties(self):
        root = Path(tempfile.mkdtemp(prefix='tv-sdk-test-'))
        (root / 'kotlin-tv').mkdir()
        (root / 'kotlin-tv/local.properties').write_text('sdk.dir=/existing/sdk\n')
        with patch.object(update, 'ROOT', root), patch.dict(os.environ, {'ANDROID_HOME': '/different/sdk'}):
            self.assertEqual(Path('/existing/sdk'), update.android_sdk())

    def test_draft_is_found_when_published_tag_endpoint_returns_404(self):
        import subprocess
        draft = self.release(draft=True)
        draft['assets'] = [{'name': 'hongguo-1.0.0.apk', 'state': 'uploaded'}]
        with patch.object(update.subprocess, 'run', return_value=subprocess.CompletedProcess([], 1, '', 'gh: Not Found (HTTP 404)')), \
             patch.object(update, 'run', return_value=json.dumps([[self.release(name='0.9.0')], [draft]])) as run:
            self.assertEqual(draft, update.get_release(self.config['repository'], self.tag))
            run.assert_called_once_with('gh', 'api', '--paginate', '--slurp', f"repos/{self.config['repository']}/releases?per_page=100")

    def test_missing_or_ambiguous_draft_is_not_treated_as_existing_release(self):
        import subprocess
        with patch.object(update.subprocess, 'run', return_value=subprocess.CompletedProcess([], 1, '', 'gh: Not Found (HTTP 404)')):
            with patch.object(update, 'run', return_value='[[]]'):
                self.assertIsNone(update.get_release(self.config['repository'], self.tag))
            with patch.object(update, 'run', return_value=json.dumps([[self.release(draft=True)] * 2])):
                with self.assertRaisesRegex(AssertionError, 'Ambiguous'):
                    update.get_release(self.config['repository'], self.tag)

    def test_tag_lookup_permission_errors_never_fall_back_to_draft_lookup(self):
        import subprocess
        with patch.object(update.subprocess, 'run', return_value=subprocess.CompletedProcess([], 1, '', 'gh: Forbidden (HTTP 403)')), \
             patch.object(update, 'run') as run:
            with self.assertRaises(AssertionError):
                update.get_release(self.config['repository'], self.tag)
            run.assert_not_called()

    def test_asset_digest_size_and_upload_state_are_all_required(self):
        path = Path(tempfile.mkdtemp(prefix='tv-release-test-')) / 'test.apk'
        path.write_bytes(b'signed-package')
        asset = {'state': 'uploaded', 'size': path.stat().st_size, 'digest': 'sha256:' + update.digest(path)}
        self.assertTrue(update.asset_matches(asset, path))
        for changes in [{'state': 'new'}, {'size': 0}, {'digest': 'sha256:' + '0' * 64}]:
            self.assertFalse(update.asset_matches(asset | changes, path))

    def test_publish_uploads_only_apk_and_never_extra_files(self):
        out = Path(tempfile.mkdtemp(prefix='tv-release-publish-test-'))
        apk = out / f'hongguo-{self.name}.apk'
        apk.write_bytes(b'signed-package')
        self.record['apk'] = {'name': apk.name, 'size': apk.stat().st_size, 'sha256': update.digest(apk)}
        record_path = out / 'release-record.json'
        record_path.write_text(json.dumps(self.record))
        notes = update.release_notes(self.config, self.record)
        (out / 'release-notes.md').write_text(notes)
        uploaded = self.release(draft=True)
        uploaded['body'] = notes
        uploaded['assets'] = [{'name': apk.name, 'state': 'uploaded', 'size': apk.stat().st_size, 'digest': 'sha256:' + update.digest(apk)}]
        calls = []
        def fake_run(*args):
            calls.append(args)
            return 'a' * 40 if args[:3] == ('git', 'rev-parse', 'HEAD') else ('approved=true\npull_request=7' if args[:2] == ('python3', 'scripts/upstream-sync.py') else '')
        with patch.dict(os.environ, {'GITHUB_REPOSITORY': self.config['repository'], 'GITHUB_REF': 'refs/heads/main'}), \
             patch.object(update, 'run', side_effect=fake_run), patch.object(update, 'get_release', side_effect=[None, uploaded, None]), contextlib.redirect_stdout(io.StringIO()):
            update.publish(record_path)
        uploads = [call for call in calls if call[:3] == ('gh', 'release', 'upload')]
        self.assertEqual([('gh', 'release', 'upload', self.tag, str(apk), '--repo', self.config['repository'])], uploads)
        self.assertFalse(any('--clobber' in call for call in calls))
        self.assertIn(('gh', 'release', 'edit', self.tag, '--repo', self.config['repository'], '--draft=false', '--latest=true', '--notes-file', str(out / 'release-notes.md')), calls)

    def test_published_apk_and_existing_extra_attachments_are_never_replaced(self):
        out = Path(tempfile.mkdtemp(prefix='tv-release-immutable-test-'))
        apk = out / f'hongguo-{self.name}.apk'
        apk.write_bytes(b'signed-package')
        self.record['apk'] = {'name': apk.name, 'size': apk.stat().st_size, 'sha256': update.digest(apk)}
        record_path = out / 'release-record.json'
        record_path.write_text(json.dumps(self.record))
        notes = update.release_notes(self.config, self.record)
        (out / 'release-notes.md').write_text(notes)
        release = self.release()
        release['body'] = notes
        for assets in [[{'name': apk.name, 'state': 'uploaded', 'size': 0, 'digest': 'sha256:' + '0' * 64}], [{'name': 'update.json'}]]:
            release['assets'] = assets
            with patch.dict(os.environ, {'GITHUB_REPOSITORY': self.config['repository'], 'GITHUB_REF': 'refs/heads/main'}), \
                 patch.object(update, 'run', side_effect=lambda *args: 'a' * 40 if args[:3] == ('git', 'rev-parse', 'HEAD') else 'approved=true\npull_request=7') as run, patch.object(update, 'get_release', return_value=release), self.assertRaises(AssertionError):
                update.publish(record_path)
            self.assertFalse(any(call.args[:2] == ('gh', 'release') for call in run.call_args_list))


if __name__ == '__main__':
    unittest.main()
