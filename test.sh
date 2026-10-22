#!/bin/sh
# Compile the plugin and run its headless unit tests (test/sidetoolbar/StoreTests.java).
# Usage: ./test.sh [/path/to/Fiji]
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/fiji-env.sh" "$1"
OUT="$HERE/build/test-classes"
rm -rf "$OUT" && mkdir -p "$OUT"
"$JAVA_BIN/javac" --release 8 -Xlint:-options -encoding UTF-8 -cp "$IJ:$(ls "$FIJI"/jars/itextpdf-*.jar 2>/dev/null | head -1)" -d "$OUT" "$HERE"/src/sidetoolbar/*.java "$HERE"/test/sidetoolbar/*.java
"$JAVA_BIN/java" -Djava.awt.headless=true -Duser.home="$OUT" -cp "$OUT:$IJ" sidetoolbar.StoreTests
