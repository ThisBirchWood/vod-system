#!/bin/sh
# Packages a built vod-client binary as a single-file AppImage that runs on any modern Linux
# desktop without installing anything.
#
#   packaging/linux/build-appimage.sh dist/vod-client dist/VoD-Client-x86_64.AppImage
#
# Set APPIMAGETOOL to use an existing appimagetool; otherwise a pinned release is downloaded and
# its checksum verified.
set -eu

bin=$1
out=$2
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)

tool_version=1.9.1
tool_sha256=ed4ce84f0d9caff66f50bcca6ff6f35aae54ce8135408b3fa33abfc3cb384eb0

work=$(mktemp -d)
cleanup() {
	if [ -n "$work" ] && [ -d "$work" ]; then
		rm -rf -- "$work"
	fi
}
trap cleanup EXIT

appdir=$work/AppDir
mkdir -p "$appdir/usr/bin"
install -m 755 "$bin" "$appdir/usr/bin/vod-client"
cp "$here/dev.defaoite.VodClient.desktop" "$appdir/"
cp "$root/internal/ui/assets/icon.png" "$appdir/dev.defaoite.VodClient.png"
ln -s usr/bin/vod-client "$appdir/AppRun"

tool=${APPIMAGETOOL:-}
if [ -z "$tool" ]; then
	tool=$work/appimagetool
	curl -fsSL -o "$tool" \
		"https://github.com/AppImage/appimagetool/releases/download/$tool_version/appimagetool-x86_64.AppImage"
	echo "$tool_sha256  $tool" | sha256sum -c -
	chmod +x "$tool"
fi

mkdir -p "$(dirname "$out")"
# Extract-and-run avoids needing FUSE on the build machine.
APPIMAGE_EXTRACT_AND_RUN=1 ARCH=x86_64 "$tool" --no-appstream "$appdir" "$out"
echo "built $out"
