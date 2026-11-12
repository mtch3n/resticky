#!/bin/sh
set -eu
here=$(cd "$(dirname "$0")" && pwd)
port=$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1])')
token=5e7c0b1d2a3f4e5d6c7b8a9f0e1d2c3b4a5f6e7d8c9b0a1f2e3d4c5b6a7f8e9d
data=$(mktemp -d /tmp/resticky-contract.XXXXXX)
if timeout 60 "$@" -port "$port" -data "$data" -model /dev/null -vad off -mdns=false -weather "$data.weather.json" > "$data.open.log" 2>&1; then
    echo "the hub started without -token" >&2
    exit 1
fi
grep -q "need -token" "$data.open.log" || { echo "the hub without -token did not refuse to start:" >&2; cat "$data.open.log" >&2; exit 1; }
rm -f "$data.open.log"
PATH="$here/fakes:$PATH" "$@" -port "$port" -data "$data" -model /dev/null -vad off -mdns=false -weather "$data.weather.json" -token "$token" > "$data.log" 2>&1 &
hub=$!
trap 'kill $hub 2>/dev/null; rm -rf "$data" "$data.weather.json"' EXIT
for _ in $(seq 100); do curl -sf "http://127.0.0.1:$port/api/hello" > /dev/null && break; sleep 0.2; done
cd "$here/.." && ./gradlew -q :contract:test -PhubUrl="http://127.0.0.1:$port" -PhubData="$data" -PhubToken="$token"
