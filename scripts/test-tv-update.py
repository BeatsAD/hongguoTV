import importlib.util
from pathlib import Path
import tempfile
import unittest
import json
import os
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("tv_update", Path(__file__).with_name("tv-update.py"))
update = importlib.util.module_from_spec(spec)
spec.loader.exec_module(update)


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        self.config = update.channel()
        self.code, self.name, self.tag = update.version(self.config, 1)
        self.badging = (f"package: name='{self.config['applicationId']}' versionCode='{self.code}' versionName='{self.name}-playbackfix'\n"
                        f"sdkVersion:'{self.config['minSdk']}'\nuses-permission: name='android.permission.REQUEST_INSTALL_PACKAGES'\n")
        self.certificate = f"Signer #1 certificate SHA-256 digest: {self.config['signerSha256']}\n"

    def check(self, badging=None, certificate=None):
        update.verify_badging(self.config, badging or self.badging, certificate or self.certificate, self.code, self.name)

    def test_current_identity_and_permission_are_accepted(self):
        self.check()

    def test_wrong_package_is_rejected(self):
        with self.assertRaises(AssertionError):
            self.check(self.badging.replace(self.config['applicationId'], 'com.hongguotv.nativeapp'))

    def test_wrong_signer_is_rejected(self):
        with self.assertRaises(AssertionError):
            self.check(certificate=self.certificate.replace(self.config['signerSha256'], '0' * 64))

    def test_multiple_signers_are_rejected(self):
        with self.assertRaises(AssertionError):
            self.check(certificate=self.certificate + self.certificate.replace('#1', '#2'))

    def test_wrong_version_or_sdk_are_rejected(self):
        for badging in [self.badging.replace(str(self.code), '53'), self.badging.replace(self.name, '0.1.0'), self.badging.replace("sdkVersion:'26'", "sdkVersion:'21'")]:
            with self.assertRaises(AssertionError):
                self.check(badging)

    def test_debuggable_or_missing_install_permission_are_rejected(self):
        for badging in [self.badging + 'application-debuggable\n', self.badging.replace('android.permission.REQUEST_INSTALL_PACKAGES', 'other')]:
            with self.assertRaises(AssertionError):
                self.check(badging)

    def test_version_is_monotonic_and_never_downgrades_53(self):
        self.assertGreater(self.code, 53)
        self.assertGreater(update.version(self.config, 2)[0], self.code)
        with self.assertRaises(AssertionError):
            update.version(self.config, 0)

    def test_old_runs_never_replace_latest(self):
        self.assertTrue(update.promote(self.code, None))
        self.assertFalse(update.promote(self.code, self.tag))
        self.assertFalse(update.promote(self.code - 1, self.tag))
        self.assertTrue(update.promote(self.code + 1, self.tag))

    def test_unrelated_latest_is_not_overwritten(self):
        with self.assertRaises(AssertionError):
            update.promote(self.code, 'upstream-v99')

    def test_missing_review_blocks_publication_before_upload(self):
        root = Path(tempfile.mkdtemp(prefix='tv-review-test-'))
        metadata = root / 'update.json'
        sha = 'a' * 40
        metadata.write_text(json.dumps({'sourceCommit':sha,'packageName':self.config['applicationId'],'certificateSha256':self.config['signerSha256']}))
        with patch.dict(os.environ, {'GITHUB_REPOSITORY':self.config['repository'],'GITHUB_REF':'refs/heads/main'}), patch.object(update,'run',side_effect=[sha,'approved=false\npull_request=']) as run:
            with self.assertRaisesRegex(AssertionError,'Human review'):
                update.publish(metadata)
            self.assertEqual(2,run.call_count)

    def test_release_metadata_must_link_the_actual_approved_pr(self):
        root = Path(tempfile.mkdtemp(prefix='tv-review-link-test-'))
        metadata = root / 'update.json'
        sha = 'a' * 40
        metadata.write_text(json.dumps({'sourceCommit':sha,'packageName':self.config['applicationId'],'certificateSha256':self.config['signerSha256'],'reviewedPullRequest':'https://github.com/BeatsAD/hongguoTV/pull/99'}))
        with patch.dict(os.environ, {'GITHUB_REPOSITORY':self.config['repository'],'GITHUB_REF':'refs/heads/main'}), patch.object(update,'run',side_effect=[sha,'approved=true\npull_request=7']):
            with self.assertRaisesRegex(AssertionError,'provenance mismatch'):
                update.publish(metadata)

    def test_asset_digest_size_and_upload_state_are_all_required(self):
        # Retain temporary evidence; no recursive deletion under workspace rules.
        path = Path(tempfile.mkdtemp(prefix='tv-release-test-')) / 'test.apk'
        path.write_bytes(b'signed-package')
        asset = {'state': 'uploaded', 'size': path.stat().st_size, 'digest': 'sha256:' + update.digest(path)}
        self.assertTrue(update.asset_matches(asset, path))
        for changes in [{'state': 'new'}, {'size': 0}, {'digest': 'sha256:' + '0' * 64}]:
            self.assertFalse(update.asset_matches(asset | changes, path))


if __name__ == '__main__':
    unittest.main()
