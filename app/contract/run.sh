#!/bin/sh
set -eu
here=$(cd "$(dirname "$0")" && pwd)
port=$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1])')
data=$(mktemp -d /tmp/resticky-contract.XXXXXX)
PATH="$here/fakes:$PATH" "$@" -port "$port" -data "$data" -model /dev/null -vad off -mdns=false -weather "$data.weather.json" > "$data.log" 2>&1 &
hub=$!
trap 'kill $hub 2>/dev/null; rm -rf "$data" "$data.weather.json"' EXIT
for _ in $(seq 100); do curl -sf "http://127.0.0.1:$port/api/hello" > /dev/null && break; sleep 0.2; done
cd "$here/.." && ./gradlew -q :contract:test -PhubUrl="http://127.0.0.1:$port" -PhubData="$data"
