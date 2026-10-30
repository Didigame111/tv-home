#!/usr/bin/env bash
# Cleans up the TV and makes TV Home the launcher. Run: scripts/debloat.sh <tv-ip>
set -u
cd "$(dirname "$0")"
source ./packages.sh
export ANDROID_SERIAL="${1:?usage: $0 <tv-ip>}:5555"
adb connect "$ANDROID_SERIAL" >/dev/null

installed=$(adb shell pm list packages | tr -d '\r')
for p in "${REMOVE[@]}"; do
  grep -qx "package:$p" <<<"$installed" || { echo "skip   $p (not installed)"; continue; }
  echo "remove $p: $(adb shell pm uninstall -k --user 0 "$p" | tr -d '\r')"
done
for p in "${DISABLE[@]}"; do
  echo "disable $p: $(adb shell pm disable-user --user 0 "$p" | tr -d '\r')"
done

# TV Home as the launcher, compiled ahead of time so it opens instantly.
adb shell cmd package set-home-activity br.arthur.tvhome/.HomeActivity
adb shell cmd package compile -m speed -f br.arthur.tvhome >/dev/null
# TCL's firmware drops boot and update broadcasts to apps without its auto-start op; TV Home
# needs them to tidy up after Live TV.
adb shell cmd appops set br.arthur.tvhome APP_AUTO_START allow

# The Clock screensaver instead of Ambient mode.
adb shell settings put secure screensaver_components br.arthur.tvhome/.ClockDream
adb shell settings put secure screensaver_enabled 1

# Snappier menus: half-length animations.
adb shell settings put global window_animation_scale 0.5
adb shell settings put global transition_animation_scale 0.5
adb shell settings put global animator_duration_scale 0.5
echo "done"
