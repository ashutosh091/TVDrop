#!/bin/bash
# Build the app and install it on the Mi Box over the network.
# Usage: ./install.sh <box-ip>       e.g. ./install.sh 192.168.1.42
set -euo pipefail
cd "$(dirname "$0")"

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

[ $# -eq 1 ] || { echo "usage: $0 <box-ip>"; exit 1; }
echo "sdk.dir=$ANDROID_HOME" > local.properties

./gradlew assembleDebug -q
adb connect "$1:5555"   # accept the "Allow USB debugging?" prompt on the TV the first time
adb -s "$1:5555" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$1:5555" shell monkey -p dev.ashutosh.tvdrop -c android.intent.category.LEANBACK_LAUNCHER 1 >/dev/null
echo "Installed. TVDrop is opening on the TV."
