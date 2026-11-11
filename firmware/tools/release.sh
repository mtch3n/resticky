#!/bin/sh
set -eu
here=$(cd "$(dirname "$0")/.." && pwd)
dir="$HOME/.local/share/resticky/firmware"
version=$(sed -n 's/.*set(PROJECT_VER "\(.*\)").*/\1/p' "$here/CMakeLists.txt")
cd "$here"
pio run
mkdir -p "$dir"
cp .pio/build/sticky/firmware.bin "$dir/$version.bin"
echo "released $version: $(sha256sum "$dir/$version.bin" | cut -c1-16)... $(stat -c %s "$dir/$version.bin") bytes"
