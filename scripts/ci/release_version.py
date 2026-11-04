"""Detect a new stable Android version in a main-branch push; no network access."""

import os
from pathlib import Path
import re
import subprocess


def read_version(properties):
    values = re.findall(r"^futbolioVersion\s*=\s*(\S+)\s*$", properties, re.MULTILINE)
    if len(values) != 1:
        raise ValueError("Expected exactly one futbolioVersion in gradle.properties")
    version = values[0]
    if not re.fullmatch(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)", version):
        raise ValueError("futbolioVersion must be a stable major.minor.patch version")
    major, minor, patch = map(int, version.split("."))
    if not (0 <= major <= 2099 and 0 <= minor <= 999 and 0 <= patch <= 999) or (major, minor, patch) == (0, 0, 0):
        raise ValueError("futbolioVersion is outside the supported Android version range")
    return version


def should_release(current, previous, tagged):
    if previous is not None:
        old = tuple(map(int, previous.split(".")))
        new = tuple(map(int, current.split(".")))
        if new < old:
            raise ValueError("Application versions must not decrease")
        if new == old:
            return False
    return not tagged


def main():
    current = read_version(Path("gradle.properties").read_text())
    before = os.environ["BEFORE_SHA"]
    if not re.fullmatch(r"[0-9a-f]{40}", before):
        raise ValueError("Expected a GitHub push before SHA")
    previous = None
    if before != "0" * 40:
        # Fail closed on unavailable history rather than treating it as a version bump.
        properties = subprocess.check_output(["git", "show", f"{before}:gradle.properties"], text=True)
        previous = read_version(properties)
    tag_result = subprocess.run(["git", "show-ref", "--verify", "--quiet", f"refs/tags/v{current}"], check=False)
    if tag_result.returncode not in (0, 1):
        raise RuntimeError("Unable to inspect existing version tags")
    release = should_release(current, previous, tag_result.returncode == 0)
    with open(os.environ["GITHUB_OUTPUT"], "a") as output:
        output.write(f"version={current}\nrelease={str(release).lower()}\n")
    print(f"Version {previous or '(initial push)'} -> {current}; release={release}")


if __name__ == "__main__":
    main()
