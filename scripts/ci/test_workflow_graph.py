"""Keep build parallelism and publication/security gates explicit without YAML deps."""
from pathlib import Path
import re
import unittest


class WorkflowGraphTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflow = (Path(__file__).parents[2] / '.github/workflows/check.yml').read_text()

    def job(self, name):
        match = re.search(rf'^  {re.escape(name)}:\n(.*?)(?=^  [a-z][a-z0-9-]*:\n|\Z)', self.workflow, re.M | re.S)
        self.assertIsNotNone(match, f'Missing workflow job {name}')
        return match.group(1)

    def needs(self, job):
        match = re.search(r'^    needs: \[([^\]]*)\]', job, re.M)
        return set(part.strip() for part in match.group(1).split(',')) if match else set()

    def test_native_builds_do_not_wait_for_other_platforms_or_tests(self):
        self.assertEqual({'version'}, self.needs(self.job('build-apk')))
        self.assertEqual(set(), self.needs(self.job('desktop-package')))
        self.assertIn('always() && !cancelled()', self.job('build-apk'))
        self.assertIn("github.event_name == 'pull_request'", self.job('build-apk'))

    def test_publication_waits_for_all_builds_and_checks(self):
        publication = self.job('publish-release')
        self.assertEqual({'version', 'offline-engine', 'unit-and-lint', 'desktop-package', 'build-apk'}, self.needs(publication))
        self.assertIn("github.ref == 'refs/heads/main'", publication)
        self.assertIn("needs.version.outputs.release == 'true'", publication)
        self.assertIn('contents: write', publication)
        self.assertIn('--draft --target', publication)
        self.assertIn('--draft=false', publication)
        for name in ('build-apk', 'desktop-package'):
            self.assertNotIn('contents: write', self.job(name))

    def test_feature_pushes_do_not_duplicate_pr_checks(self):
        self.assertIn('  push:\n    branches: [main]\n  pull_request:', self.workflow)

    def test_pr_apks_never_use_release_signing(self):
        apk = self.job('build-apk')
        for step in ('Restore release signing key', 'Build signed release APK'):
            self.assertIn(f"- name: {step}\n        if: github.event_name != 'pull_request'", apk)
        self.assertIn("- name: Build PR debug APK (no release secrets)\n        if: github.event_name == 'pull_request'", apk)


if __name__ == '__main__':
    unittest.main()
