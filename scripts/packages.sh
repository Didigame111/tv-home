# Shared by debloat.sh and restore.sh. TCL G10 Google TV, Android 12.

# Removed for user 0 only ("pm uninstall -k --user 0"): the APK stays on the system
# partition, so "cmd package install-existing" brings each one back unchanged.
REMOVE=(
  # Ads, viewing trackers and telemetry
  tv.samba.ssm.ui                         # Samba TV: tracks what's on screen for ads
  com.tcl.interactive                     # TCL TIAS: interactive ad service
  com.tcl.logkit                          # TCL log collection
  com.tcl.messagebox                      # TCL promo messages
  com.tcl.usercenter                      # TCL account (always running, 12 MB)
  com.tcl.smartalexa                      # Works with Alexa (always running)
  # Store demo, hotel and manuals
  com.tcl.esticker                        # Showmode (store stickers)
  com.tcl.exhibit                         # T-Exhibition (store demo)
  com.tcl.hotelmenu                       # Hotel mode
  com.tcl.ocean.instructions              # User Guide
  # Extras nobody here uses
  com.tcl.guard                           # Safety Guard "cleaner" (always running)
  com.tcl.gamebar                         # Game bar overlay
  com.tcl.partnercustomizer               # Pushes TCL rows into Google TV's home
  com.google.android.play.games           # Play Games
  com.google.android.youtube.tvmusic      # YouTube Music (was already disabled)
  com.google.android.feedback             # Google feedback reporter
  com.google.android.syncadapters.calendar
  com.android.printspooler                # Printing
)

# Disabled ("pm disable-user"), restored with "pm enable".
DISABLE=(
  com.google.android.apps.tv.launcherx    # Google TV home (156 MB), replaced by TV Home; TV Home
                                          # switches it on only while Live TV (Freeplay) is open
  com.google.android.tungsten.setupwraith # Setup "recovery" screen that grabs the Home key
  com.google.android.apps.tv.dreamx       # Ambient mode (77 MB), replaced by the Clock screensaver
)
