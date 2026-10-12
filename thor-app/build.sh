#!/usr/bin/env bash
# Builds build/duobeam.jar: the standalone sender (com.duobeam.*) + scrcpy 4.1 hidden-API wrappers, dexed for app_process.
# Only needed to run the sender by hand from adb shell; the Android app (android/) bundles the same code by itself.
#
# Needs: Android SDK (build-tools + platform 36) and a JDK 17+.
#   ANDROID_HOME   SDK location            (default: ~/Android/Sdk)
#   BUILD_TOOLS    build-tools version     (default: 36.0.0)
#   JAVA_HOME      JDK used for javac/jar  (default: javac from PATH)
#
# Run by hand on the device:
#   adb push build/duobeam.jar /data/local/tmp/
#   adb shell CLASSPATH=/data/local/tmp/duobeam.jar app_process / com.duobeam.Sender tv=<TV-IP>:8201
set -e
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
BT="$SDK/build-tools/${BUILD_TOOLS:-36.0.0}"
AJAR="$SDK/platforms/android-36/android.jar"
AIDL_FW="$SDK/platforms/android-36/framework.aidl"
if [ -n "$JAVA_HOME" ]; then JBIN="$JAVA_HOME/bin/"; else JBIN=""; fi
EXE=""; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) EXE=".exe";; esac
# on Windows (Git Bash) the SDK tools want Windows paths
winpath() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else echo "$1"; fi; }
D8="$BT/d8"; [ -f "$BT/d8.bat" ] && D8="$BT/d8.bat"

SRV=third_party/scrcpy/server/src/main
OUT=build; rm -rf $OUT; mkdir -p $OUT/classes $OUT/gen/com/genymobile/scrcpy
cat > $OUT/gen/com/genymobile/scrcpy/BuildConfig.java <<J
package com.genymobile.scrcpy;
public final class BuildConfig { public static final boolean DEBUG = false; public static final String VERSION_NAME = "4.1"; }
J
GENW=$(winpath "$PWD/$OUT/gen"); FWW=$(winpath "$AIDL_FW"); AIW=$(winpath "$PWD/$SRV/aidl")
"$BT/aidl$EXE" -o"$GENW" -I"$AIW" "$(winpath "$PWD/$SRV/aidl/android/content/IOnPrimaryClipChangedListener.aidl")"
"$BT/aidl$EXE" -o"$GENW" -I"$AIW" -p"$FWW" "$(winpath "$PWD/$SRV/aidl/android/view/IDisplayWindowListener.aidl")"
find $SRV/java src $OUT/gen -name '*.java' > $OUT/sources.txt
"${JBIN}javac$EXE" -encoding UTF-8 -nowarn -Xlint:-options -source 1.8 -target 1.8 -bootclasspath "$(winpath "$AJAR")" \
    -cp "$(winpath "$BT/core-lambda-stubs.jar")" -d $OUT/classes @$OUT/sources.txt
"${JBIN}jar$EXE" cf $OUT/classes.jar -C $OUT/classes .
JAVA_HOME="${JAVA_HOME:-}" "$D8" --min-api 30 --lib "$(winpath "$AJAR")" --output "$(winpath "$PWD/$OUT/duobeam.zip")" "$(winpath "$PWD/$OUT/classes.jar")"
mv $OUT/duobeam.zip $OUT/duobeam.jar
ls -la $OUT/duobeam.jar
