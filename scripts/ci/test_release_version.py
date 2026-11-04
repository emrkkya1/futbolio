import unittest
import os
from pathlib import Path
import subprocess
import sys
import tempfile

from release_version import read_version, should_release


class ReleaseVersionTest(unittest.TestCase):
    def test_reads_the_single_application_version(self):
        self.assertEqual("0.3.1", read_version("android.useAndroidX=true\nfutbolioVersion=0.3.1\n"))

    def test_rejects_invalid_and_ambiguous_versions(self):
        for value in ["0.0.0", "01.2.3", "1.02.3", "1.2.03", "1.2", "1.2.3-beta", "1.2.3+build", "2100.0.0", "0.1000.0", "0.0.1000"]:
            with self.subTest(value=value), self.assertRaises(ValueError):
                read_version(f"futbolioVersion={value}\n")
        for properties in ["", "futbolioVersion=1.0.0\nfutbolioVersion=1.0.1\n"]:
            with self.assertRaises(ValueError):
                read_version(properties)

    def test_accepts_maximum_android_version(self):
        self.assertEqual("2099.999.999", read_version("futbolioVersion=2099.999.999"))

    def test_new_version_releases(self):
        self.assertTrue(should_release("0.3.2", "0.3.1", False))
        self.assertTrue(should_release("0.10.0", "0.9.999", False))

    def test_unchanged_version_and_existing_tags_do_not_release(self):
        self.assertFalse(should_release("0.3.1", "0.3.1", False))
        self.assertFalse(should_release("0.3.2", "0.3.1", True))

    def test_version_decreases_fail(self):
        with self.assertRaises(ValueError):
            should_release("0.3.0", "0.3.1", False)

    def test_initial_push_only_releases_an_untagged_version(self):
        self.assertTrue(should_release("0.3.1", None, False))
        self.assertFalse(should_release("0.3.1", None, True))


class ReleaseDetectionIntegrationTest(unittest.TestCase):
    def test_push_comparison_tag_skip_and_invalid_history(self):
        script = Path(__file__).with_name("release_version.py").resolve()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)

            def git(*args):
                return subprocess.check_output(["git", *args], cwd=root, text=True, stderr=subprocess.DEVNULL).strip()

            git("init", "--quiet")
            git("config", "user.name", "CI fixture")
            git("config", "user.email", "ci@example.invalid")

            def commit(version):
                (root / "gradle.properties").write_text(f"futbolioVersion={version}\n")
                git("add", "gradle.properties")
                git("commit", "--quiet", "-m", version)
                return git("rev-parse", "HEAD")

            before = commit("0.3.1")
            current = commit("0.3.2")

            def detect(previous):
                output = root / "output"
                output.write_text("")
                result = subprocess.run([sys.executable, str(script)], cwd=root,
                                        env={**os.environ, "BEFORE_SHA": previous, "GITHUB_OUTPUT": str(output)},
                                        text=True, capture_output=True)
                return result, output.read_text()

            result, output = detect(before)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual("version=0.3.2\nrelease=true\n", output)
            result, output = detect(current)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn("release=false", output)
            git("tag", "v0.3.2")
            result, output = detect(before)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn("release=false", output)
            result, output = detect("f" * 40)
            self.assertNotEqual(0, result.returncode)
            self.assertEqual("", output)
