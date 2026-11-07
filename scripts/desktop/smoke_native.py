"""Exercise the packaged launcher/JRE/fonts/server without user settings or providers."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("launcher", type=Path)
    parser.add_argument("--version", required=True)
    parser.add_argument("--appimage", action="store_true")
    args = parser.parse_args()
    env = dict(os.environ, SKIKO_RENDER_API="SOFTWARE")
    if args.appimage:
        env["APPIMAGE_EXTRACT_AND_RUN"] = "1"
    with tempfile.TemporaryDirectory(prefix="futbolio-native-smoke-") as directory:
        marker = Path(directory) / "result.txt"
        subprocess.run([str(args.launcher.resolve()), "--smoke-test", str(marker)], env=env, check=True, timeout=90)
        if not marker.exists() or marker.read_text().strip() != args.version:
            raise RuntimeError("Packaged application did not complete the version/fonts/loopback-server smoke test")
    print(f"Packaged Futbolio {args.version}: OK")


if __name__ == "__main__":
    main()
