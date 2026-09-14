#!/usr/bin/env sh
# Produce the one-file source archive required for NTULearn submission.
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
DIST="$ROOT/dist"
ARCHIVE="$DIST/SC6103-project-source.zip"

if ! command -v zip >/dev/null 2>&1; then
    echo "ERROR: zip is required to build the submission archive." >&2
    exit 1
fi

mkdir -p "$DIST"
cd "$ROOT"
zip -q -r "$ARCHIVE" .gitignore CLAUDE.md Makefile README.md docs scripts src tests

if ! unzip -tq "$ARCHIVE" >/dev/null; then
    echo "ERROR: archive validation failed: $ARCHIVE" >&2
    exit 1
fi

echo "Created $ARCHIVE"
echo "Archive integrity check passed."
