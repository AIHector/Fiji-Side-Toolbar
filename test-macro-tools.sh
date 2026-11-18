#!/bin/sh
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/fiji-env.sh" "${1:-}"
OUT="$HERE/build/macro-tools-test-classes"
mkdir -p "$OUT"
TEST_HOME=$(mktemp -d "$HERE/build/macro-tools-test-home.XXXXXX")
trap 'rm -rf "$TEST_HOME"' EXIT
"$JAVA_BIN/javac" --release 8 -Xlint:-options -proc:none -encoding UTF-8 -cp "$FIJI/jars/*" -d "$OUT" "$HERE"/src/sidetoolbar/*.java "$HERE/test/integration/sidetoolbar/MacroToolsTest.java"
PATCHER=$(find "$FIJI/jars" -maxdepth 1 -name 'ij1-patcher-*.jar' | head -1)
"$JAVA_BIN/java" --add-opens=java.base/java.lang=ALL-UNNAMED -javaagent:"$PATCHER"=init -Duser.home="$TEST_HOME" -Djava.util.prefs.userRoot="$TEST_HOME" -Dplugins.dir="$TEST_HOME" -Dapple.awt.UIElement=true -Xmx1g -cp "$OUT:$FIJI/jars/*" sidetoolbar.MacroToolsTest
