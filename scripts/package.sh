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
# Relative to ROOT from here on: a native Windows zip.exe reached from Git Bash cannot
# open the /d/... form of an absolute path, and a relative one works everywhere.
RELATIVE_ARCHIVE="dist/SC6103-project-source.zip"
# zip -r adds to an existing archive, so a stale file would survive a rebuild.
rm -f "$RELATIVE_ARCHIVE"
# Not part of the submitted source: CLAUDE.md holds instructions for an AI coding
# assistant, and the course brief is the module's own document.
zip -q -r "$RELATIVE_ARCHIVE" .gitignore Makefile README.md docs scripts src tests \
    -x "docs/Course Project.pdf"

if ! unzip -tq "$RELATIVE_ARCHIVE" >/dev/null; then
    echo "ERROR: archive validation failed: $ARCHIVE" >&2
    exit 1
fi

echo "Created $ARCHIVE"
echo "Archive integrity check passed."
