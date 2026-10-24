#!/bin/bash
# Builds the Navigation APK with Gradle and puts it where Android.bp expects it.
#
#   ./build-apk.sh                    # x86_64 (the emulator product)
#   ./build-apk.sh arm64-v8a          # a phone/car build
#   ./build-apk.sh x86_64,arm64-v8a   # both (+~200 MB)
#
# Needs JDK 17+ and the Android SDK (sdk.dir in local.properties or ANDROID_HOME), and the
# TomTom API key in vendor/autobox/tomtom.key.
set -euo pipefail
cd "$(dirname "$0")"

abis="${1:-x86_64}"
./gradlew --console=plain -Pabis="$abis" :app:assembleRelease
mkdir -p prebuilt
cp app/build/outputs/apk/release/app-release.apk prebuilt/Navigation.apk
echo "prebuilt/Navigation.apk ($abis)"
