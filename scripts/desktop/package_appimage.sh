#!/usr/bin/env bash
set -euo pipefail
# Run from the repository root after :desktop:createDistributable.
version="$(sed -n 's/^futbolioVersion=//p' gradle.properties)"
mkdir -p desktop/build/appimage dist/desktop
# jlink license files are read-only. Use a fresh staging directory on each run
# rather than overwriting a previously copied runtime tree.
appdir="$(mktemp -d desktop/build/appimage/Futbolio.XXXXXX.AppDir)"
cp -a desktop/build/compose/binaries/main/app/Futbolio/. "$appdir/"
cp desktop/build/generated/icons/futbolio.png "$appdir/futbolio.png"
cat > "$appdir/AppRun" <<'EOF'
#!/bin/sh
HERE="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
exec "$HERE/bin/Futbolio" "$@"
EOF
chmod +x "$appdir/AppRun"
cat > "$appdir/futbolio.desktop" <<'EOF'
[Desktop Entry]
Type=Application
Name=Futbolio
Comment=Relay football matches to Stremio
Exec=Futbolio
Icon=futbolio
Categories=AudioVideo;Video;
Terminal=false
EOF
# Pin both the upstream version and checksum. Extract-and-run needs no FUSE.
tool="desktop/build/appimage/appimagetool.AppImage"
if [ ! -f "$tool" ]; then
    curl --fail --location --retry 3 'https://github.com/AppImage/appimagetool/releases/download/1.9.1/appimagetool-x86_64.AppImage' -o "$tool"
fi
printf '%s  %s\n' ed4ce84f0d9caff66f50bcca6ff6f35aae54ce8135408b3fa33abfc3cb384eb0 "$tool" | sha256sum --check
chmod +x "$tool"
# Reuse the verified tool's embedded type-2 runtime instead of downloading a
# second, floating runtime from upstream during packaging.
offset="$("$tool" --appimage-offset)"
head -c "$offset" "$tool" > desktop/build/appimage/runtime-x86_64
ARCH=x86_64 APPIMAGE_EXTRACT_AND_RUN=1 "$tool" --runtime-file desktop/build/appimage/runtime-x86_64 --no-appstream --comp zstd "$appdir" "dist/desktop/futbolio-v${version}-linux-x86_64.AppImage"
