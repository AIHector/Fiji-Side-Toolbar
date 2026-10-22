#!/bin/sh
# Build Side_Toolbar.jar from src/ and install it (plus the startup macro) into Fiji.
# Usage: ./build.sh [/path/to/Fiji] [--no-install]
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
ARG="$1"; [ "$ARG" = "--no-install" ] && ARG=""
. "$HERE/fiji-env.sh" "$ARG"
VERSION=$(cat "$HERE/VERSION")

rm -rf "$HERE/build/classes" && mkdir -p "$HERE/build/classes"
"$JAVA_BIN/javac" --release 8 -Xlint:-options -encoding UTF-8 -cp "$IJ:$(ls "$FIJI"/jars/itextpdf-*.jar 2>/dev/null | head -1)" -d "$HERE/build/classes" "$HERE"/src/sidetoolbar/*.java
cp "$HERE/src/plugins.config" "$HERE/build/classes/"
printf 'Implementation-Title: Side Toolbar\nImplementation-Version: %s\n' "$VERSION" > "$HERE/build/manifest.txt"
"$JAVA_BIN/jar" cfm "$HERE/build/Side_Toolbar.jar" "$HERE/build/manifest.txt" -C "$HERE/build/classes" .
echo "Built $HERE/build/Side_Toolbar.jar (version $VERSION)"

case " $* " in *" --no-install "*) exit 0 ;; esac
cp "$HERE/build/Side_Toolbar.jar" "$FIJI/plugins/Side_Toolbar.jar"
mkdir -p "$FIJI/macros/AutoRun"
cp "$HERE/src/Side_Toolbar_Startup.ijm" "$FIJI/macros/AutoRun/Side_Toolbar_Startup.ijm"
echo "Installed into $FIJI (plugins/Side_Toolbar.jar, macros/AutoRun/Side_Toolbar_Startup.ijm) - restart Fiji."
