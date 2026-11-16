#!/bin/sh
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/fiji-env.sh" "${1:-}"
OUT="$HERE/build/drag-test-classes"
mkdir -p "$OUT" "$HERE/build/drag-test-home"
"$JAVA_BIN/javac" --release 8 -Xlint:-options -encoding UTF-8 -cp "$IJ:$(ls "$FIJI"/jars/itextpdf-*.jar | head -1)" -d "$OUT" "$HERE"/src/sidetoolbar/*.java "$HERE/test/sidetoolbar/ShelfDragTests.java"
"$JAVA_BIN/java" -Duser.home="$HERE/build/drag-test-home" -Dapple.awt.UIElement=true -Xmx512m -cp "$OUT:$IJ:$FIJI/plugins/Fiji_Window_Layouts.jar:$FIJI/plugins/Undo_History.jar" sidetoolbar.ShelfDragTests "$HERE/build"
