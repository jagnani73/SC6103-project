#!/usr/bin/env sh
# SC6103 -- compile src/java and tests/java, then run the conformance vectors.
#
# The Linux/macOS twin of scripts/build.ps1. README sec. 15.1 commits the project to
# plain javac with no build tool and no third-party jar, so this is a thin driver
# around one javac call and one java call. It must not grow into a build system.
#
# Unlike the PowerShell script there is no JDK hunting here: on a Unix machine javac
# and java are expected on PATH, which is also what the demo machines will have.
#
# POSIX sh, not bash: nothing below needs arrays or [[ ]], and sh is the one shell
# present on every machine this might be run on.

set -eu

# ---- Paths -------------------------------------------------------------------
#
# Resolved from the script's own location, so the script works from any working
# directory. Quoting is not decoration: the repository path contains spaces and
# square brackets.

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
SRC_DIR="$ROOT/src/java"
TEST_DIR="$ROOT/tests/java"
OUT_DIR="$ROOT/build/classes"

# ---- Locate the JDK ----------------------------------------------------------

if ! command -v javac >/dev/null 2>&1 || ! command -v java >/dev/null 2>&1; then
    echo "ERROR: javac and java must both be on PATH." >&2
    echo "" >&2
    echo "Install a JDK 17 or newer, for example:" >&2
    echo "  Debian/Ubuntu   sudo apt install openjdk-17-jdk" >&2
    echo "  Fedora          sudo dnf install java-17-openjdk-devel" >&2
    echo "  macOS           brew install openjdk@17" >&2
    echo "" >&2
    echo "Then re-run this script." >&2
    exit 1
fi

for dir in "$SRC_DIR" "$TEST_DIR"; do
    if [ ! -d "$dir" ]; then
        echo "ERROR: source directory not found: $dir" >&2
        exit 1
    fi
done

mkdir -p "$OUT_DIR"

# The source list is read one line at a time into the positional parameters, so each
# path stays a single argument no matter what it contains.
#
# Two tempting shortcuts are wrong here and the repository path proves it, since it
# contains both spaces and square brackets:
#
#   javac @argfile     javac splits an argfile on whitespace, so an unquoted path
#                      containing a space arrives as two arguments and javac reports
#                      the first half as an invalid flag.
#   for f in $(find)   word splitting does the same damage one step earlier.
#
# find writes to a temp file rather than a pipe because a pipeline runs its right
# side in a subshell, and the positional parameters built there would not survive.

SOURCES=$(mktemp "${TMPDIR:-/tmp}/sc6103-sources.XXXXXX")
trap 'rm -f "$SOURCES"' EXIT INT TERM

find "$SRC_DIR" "$TEST_DIR" -name '*.java' -type f > "$SOURCES"

set --
while IFS= read -r file; do
    [ -n "$file" ] || continue
    set -- "$@" "$file"
done < "$SOURCES"

if [ "$#" -eq 0 ]; then
    echo "ERROR: no .java sources found under src/java or tests/java" >&2
    exit 1
fi

# Git Bash and MSYS2 on Windows run a native javac.exe, which cannot resolve the
# POSIX paths this shell produces: /d/project is not D:\project, and the automatic
# argument translation turns it into the nonexistent \d\project. Where cygpath is
# available, hand javac Windows paths instead.
#
# This branch never runs on Linux or macOS, which is what the script is for --
# scripts/build.ps1 is the supported Windows entry point. It is here so that the
# script is not simply broken when a developer on Windows reaches for it out of
# habit, which costs six lines and one conversion loop.
if command -v cygpath >/dev/null 2>&1; then
    OUT_DIR=$(cygpath -w "$OUT_DIR")
    remaining=$#
    while [ "$remaining" -gt 0 ]; do
        # Append the converted form, drop the original: after $# rotations the
        # positional parameters hold the Windows paths in their original order.
        set -- "$@" "$(cygpath -w "$1")"
        shift
        remaining=$((remaining - 1))
    done
fi

echo "javac:    $(command -v javac)"
echo "sources:  $# files"
echo "output:   $OUT_DIR"
echo ""

# ---- Compile -----------------------------------------------------------------
#
# --release 17    the language and API level the project targets, checked against
#                 the real 17 API rather than merely -source/-target
# -encoding US-ASCII
#                 all sources are pure ASCII by rule; this makes a stray non-ASCII
#                 byte a compile error instead of a value that depends on the
#                 machine's default charset (README sec. 11.1)
# -Xlint:all      the marshalling code is the graded artefact, so every warning is
#                 worth seeing

echo "Compiling..."
javac --release 17 -encoding US-ASCII -Xlint:all -d "$OUT_DIR" "$@"
echo "Compiled OK."
echo ""

# ---- Run the conformance vectors --------------------------------------------
#
# The test prints one line per assertion and exits nonzero on any failure, so its
# exit code is this script's exit code. set -e would abort before the trap could
# report anything useful, so the status is captured and returned explicitly.

set +e
java -cp "$OUT_DIR" sc6103.conformance.ConformanceTest
STATUS=$?
set -e
exit "$STATUS"
