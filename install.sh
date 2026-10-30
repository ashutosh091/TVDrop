#!/bin/bash
# Build TVDrop and install it on an Android TV over the network with adb.
# Needs network (or USB) debugging turned on in the TV's Developer options.
# Usage: ./install.sh <tv-ip>       e.g. ./install.sh 192.168.1.42
set -euo pipefail
cd "$(dirname "$0")"

[ $# -eq 1 ] || { echo "usage: $0 <tv-ip>"; exit 1; }
[ -n "${ANDROID_HOME:-}" ] || { echo "Set ANDROID_HOME to your Android SDK"; exit 1; }
export PATH="$ANDROID_HOME/platform-tools:$PATH"
[ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties

./gradlew assembleRelease -q
APK=app/build/outputs/apk/release/app-release.apk
adb connect "$1:5555"   # accept the "Allow debugging?" prompt on the TV the first time
adb -s "$1:5555" install -r "$APK"
adb -s "$1:5555" shell monkey -p dev.ashutosh.tvdrop -c android.intent.category.LEANBACK_LAUNCHER 1 >/dev/null
echo "Installed. TVDrop is opening on the TV."
