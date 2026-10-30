<img src="assets/icon.svg" width="64" height="64" alt="">

# TV Home

An ad-free home screen for the living-room TV: a TCL Google TV (G10 platform, Android 12,
1.8 GB RAM), reached over network adb (`adb mdns services` finds it). It replaces Google
TV's home and keeps what that home did well, without the sponsored content.

- **Top bar**: Search (Assistant), All apps, Live TV (Google TV Freeplay), Inputs (Antenna and
  HDMI 1–3 in one panel), Settings and the clock. The buttons are dark glass with a hairline
  edge, so they read over light artwork as well as dark.
- **Spotlight**: 12 slides of films and shows popular on Netflix, Prime Video, Disney+ and Max
  (JustWatch, in English), two films to every show, with clean widescreen art and Watch. The
  day is cut into 3-hour parts and each part shows a different stretch of the popular lists, so
  the slides change through the day. Focusing Watch opens the slide's description (two lines);
  if there's more, a **More** button beside Watch opens a full-screen details view (whole
  description, service, rating, "Watch on …"; Back closes it). Left and Right step through the
  slides, passing More on the way (the dots only appear then); idle, they move on every 10
  seconds. Titles, service names, descriptions and age ratings carry a soft dark shadow, so they
  read on bright artwork; poster logos sit on a dark plate.
- **Your apps**: the favourites row, ending in an **All apps** tile. Hold OK on any app to move
  it, add or remove it, hide it, or open its app info.
- **Continue watching**: what you've started, with progress; removing one keeps you in place.
- **Trending movies** and **Trending shows**: what's trending in the US on those same four
  services, two rows of posters each, opening in the service's app. To keep it fresh, the lists
  are fetched again every 3 hours (checked every half hour, even while the TV sits on home),
  and each title gives up 2 places for each day it has been on screen here, for at most a week
  (14 places): newcomers get a lift in their first week, and after that popularity decides
  again. A title out of the lists for three weeks is forgotten, so a comeback counts as new. If
  a fetch fails, the last lists stay up and it isn't tried again for 25 minutes; while the TV
  is offline (just woken, Wi-Fi still connecting) it doesn't try. Nothing rated above TV-14 /
  PG-13 is shown (unrated titles are left out too, and Continue watching drops TV-MA and R
  where the app reports a rating). Pluto TV, Sling, YouTube and a local news app are never
  used for shows or films anywhere on the home screen.
- **All apps** is a page of its own (the tile, or the top bar's All apps), with hidden apps,
  rows and shows behind "Show N hidden". Back returns to the home screen.
- A **Clock** screensaver in place of Ambient mode.

The page is not a scroll container: a Column translated on the GPU, so the focused row glides
to a fixed height and the sections above it fade out. The background tint is read only at draw
time, so its fade repaints without rebuilding the screen. Back always returns to the first app;
an app being moved keeps itself in view as its row scrolls. Layout is on a 960 x 540 dp canvas
with 48 dp sides and 27 dp top, the Android TV safe area.

Content comes from the system TV provider (`READ_TV_LISTINGS`). Rows from TCL's browser,
Google's movie store and TCL's promo customizer are blocked, and only "continue" and
"next episode" entries count as Continue watching.

## Reliability

- **Crash safety net** (`TvHomeApp.kt`): three crashes within two minutes hand the Home button
  back to Google TV (through the same local-adb key as Live TV), so the TV is never left without
  a home screen. TV Home takes Home back the next time it opens cleanly, after a 10-minute grace.
- **Permission self-repair**: if `READ_TV_LISTINGS` is ever lost, TV Home asks for it on the TV.
- **Boot**: TV Home comes up as Home after a restart; `BootReceiver` switches Google TV off if a
  restart caught it mid-Live TV.
- Stress-tested on the TV (2026-10-28): 7,000 random D-pad presses, rapid Home, 15 rounds each of
  menus and the All apps page, kill and cold start (0.43 s), low-memory trim, three forced
  crashes, a reboot, and Live TV exits by Home and by Back. No crashes or ANRs; the app's own
  memory stays flat (~5.6 MB Java heap; 70–85 MB total with the image cache).

## Live TV (Google TV Freeplay)

Freeplay only runs inside Google's home app (`com.google.android.apps.tv.launcherx`), and
while that app is enabled it takes the Home button: its HOME filter has priority 2, which
Android checks before the user's chosen launcher. So TV Home switches it on only while
Freeplay is open:

1. Live TV: TV Home runs `pm enable` through the TV's own network-debugging port
   (`127.0.0.1:5555`, via [dadb](https://github.com/mobile-dev-inc/dadb)), then opens
   `https://tv.google.com/freeplay/default`.
2. `FreeplayWatcher` follows `wm_set_resumed_activity` events. Once Freeplay has shown,
   landing on Google's home screen (Home or Back) runs `pm disable-user` and brings TV
   Home back. Returning to TV Home any other way does the same in `onResume`. Turning the TV
   off (standby) mid-Freeplay switches Google TV off at once, so the TV wakes up on TV Home;
   if the debugging connection drops while Google TV is on, the watcher connects again.
3. Standby mid-Live TV is remembered: the next time the screen comes on, focus starts on Live
   TV with "Live TV was on. Press OK to go back to it." (the family goes straight back to it
   almost every time). Leaving Live TV with Home first means the screen starts on the apps as usual.
4. `BootReceiver` switches it off if the TV was powered down mid-Freeplay, and restarts the
   watcher if TV Home is updated mid-Freeplay. TCL's firmware only delivers those broadcasts
   with its auto-start op (`cmd appops set br.arthur.tvhome APP_AUTO_START allow`, done by
   `debloat.sh`). If Android kills the watcher, it restarts and carries on.

The first Live TV press makes the TV ask "Allow USB debugging?" for TV Home's own key
(already allowed on this TV). Network debugging must stay on in Developer options.

## Build and install

```sh
./gradlew :app:assembleRelease
adb connect <tv-ip>:5555        # `adb mdns services` lists it
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell pm grant br.arthur.tvhome android.permission.READ_TV_LISTINGS
adb shell cmd appops set br.arthur.tvhome APP_AUTO_START allow
adb shell cmd package compile -m speed -f br.arthur.tvhome
```

Release builds are signed with `tvhome-release.jks` (`keystore.properties`). Both are
gitignored; keep them, since updates only install over an app signed with the same key.

## Cleaning the TV

`scripts/debloat.sh` removes ads, trackers and store-demo apps for user 0 (Samba TV, TCL's
ad, telemetry, account, Alexa and demo apps, Play Games…), disables Google TV home, the
setup "recovery" screen and Ambient mode, makes TV Home the launcher, sets the Clock
screensaver and halves animation lengths. The list, with a reason for each, is in
`scripts/packages.sh`.

`scripts/restore.sh` puts everything back: every removed app (`install-existing`),
Google TV home, Ambient mode and the original settings. If TV Home ever fails to open,
`adb shell pm enable com.google.android.apps.tv.launcherx` brings Google TV's home back
straight away.

## Fonts

Instrument Serif and Figtree are under the SIL Open Font License 1.1; see `licenses/`.
