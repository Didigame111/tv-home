#!/usr/bin/env bash
# Puts the TV back the way it came: every removed app, Google TV home, Ambient mode.
# Run: scripts/restore.sh <tv-ip>
set -u
cd "$(dirname "$0")"
source ./packages.sh
export ANDROID_SERIAL="${1:?usage: $0 <tv-ip>}:5555"
adb connect "$ANDROID_SERIAL" >/dev/null

for p in "${REMOVE[@]}"; do echo "restore $p: $(adb shell cmd package install-existing "$p" | tr -d '\r')"; done
for p in "${DISABLE[@]}"; do echo "enable $p: $(adb shell pm enable "$p" | tr -d '\r')"; done
adb shell cmd package set-home-activity com.google.android.apps.tv.launcherx/.home.HomeActivity
adb shell settings put secure screensaver_components com.google.android.apps.tv.dreamx/.service.Backdrop
for k in window_animation_scale transition_animation_scale; do adb shell settings put global $k 1.0; done
adb shell settings delete global animator_duration_scale
echo "Restored. TV Home is still installed; remove it with: adb shell pm uninstall br.arthur.tvhome"
