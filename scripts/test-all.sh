#!/usr/bin/env sh
# Build both implementations and run all automated suites on Linux/macOS.
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)

sh "$SCRIPT_DIR/build.sh"
make -C "$ROOT" test

SERVER="$ROOT/build/cpp/flight_server"
java "-Dsc6103.server.binary=$SERVER" -cp "$ROOT/build/classes" \
    sc6103.integration.ServerIntegrationTest

echo "All 4 suites passed."

if [ "${1:-}" = "--experiments" ]; then
    java "-Dsc6103.server.binary=$SERVER" -cp "$ROOT/build/classes" \
        sc6103.integration.ExperimentHarness
fi
