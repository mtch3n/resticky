#!/usr/bin/env bash
set -euo pipefail
src="$1"
out="$2"
list="$(mktemp)"
trap 'rm -f "$list"' EXIT
for f in $(ls "$src"/*.ogg | sort -V); do
  printf "file '%s'\n" "$f" >> "$list"
done
ffmpeg -hide_banner -loglevel error -y -f concat -safe 0 -i "$list" -ar 16000 -ac 1 -c:a pcm_s16le "$out"
