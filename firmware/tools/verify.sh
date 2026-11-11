#!/bin/sh
set -eu
here=$(cd "$(dirname "$0")/.." && pwd)
build="$here/.pio/build/sticky"
good="$HOME/.local/share/resticky/firmware/last-good"
version=$(sed -n 's/.*set(PROJECT_VER "\(.*\)").*/\1/p' "$here/CMakeLists.txt")
since=$(date +%s)

seen() {
    journalctl --user -u resticky-hub --since "@$since" --no-pager | grep -q "$1"
}

wait_for() {
    end=$(( $(date +%s) + $2 ))
    until seen "$1"; do
        [ "$(date +%s)" -ge "$end" ] && return 1
        sleep 3
    done
}

restore() {
    echo "FAIL: $1. Flashing the last good build from $good"
    uvx esptool --port /dev/ttyACM0 --baud 921600 write-flash \
        0x0 "$good/bootloader.bin" 0x8000 "$good/partitions.bin" 0xf000 "$good/ota_data_initial.bin" 0x20000 "$good/firmware.bin"
    exit 1
}

echo "Checking firmware $version (check-in within 120 s, then a chunk within 90 s)"
wait_for "firmware=$version" 120 || restore "no check-in from $version"
wait_for "INFO chunk" 90 || restore "no chunk uploaded"
mkdir -p "$good"
cp "$build/bootloader/bootloader.bin" "$good/bootloader.bin" 2>/dev/null || cp "$build/bootloader.bin" "$good/bootloader.bin"
cp "$build/partitions.bin" "$build/ota_data_initial.bin" "$build/firmware.bin" "$good/"
echo "$version" > "$good/VERSION"
echo "OK: $version checks in and uploads audio; saved as last good"
