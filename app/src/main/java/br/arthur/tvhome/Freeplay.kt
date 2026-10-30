package br.arthur.tvhome

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import dadb.AdbKeyPair
import dadb.AdbShellPacket
import dadb.AdbShellStream
import dadb.Dadb
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Google TV Freeplay (Google's free live channels) only runs inside Google's own home app, and
 * while that app is enabled it also takes over the Home button. So it is switched on just for
 * Freeplay and off again the moment the family heads home.
 *
 * An app can't enable or disable another app by itself; this goes through the TV's own network
 * debugging port, which the TV asks to allow once ("Always allow from this computer").
 */
object Freeplay {
    private const val TAG = "Freeplay"
    const val GOOGLE_HOME = "com.google.android.apps.tv.launcherx"
    private const val HOME_ACTIVITY = "$GOOGLE_HOME/.home.HomeActivity"

    fun isAvailable(context: Context) = runCatching {
        context.packageManager.getApplicationInfo(GOOGLE_HOME, PackageManager.MATCH_DISABLED_COMPONENTS)
    }.isSuccess

    fun isGoogleHomeEnabled(context: Context): Boolean {
        val state = runCatching { context.packageManager.getApplicationEnabledSetting(GOOGLE_HOME) }.getOrNull()
        return state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT || state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    }

    fun intent(): Intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://tv.google.com/freeplay/default"))
        .setComponent(ComponentName(GOOGLE_HOME, "$GOOGLE_HOME.gateway.LauncherDeepLink"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Switches Google's home app on. Blocks, and the first time waits for the TV's "Allow". */
    fun switchOn(context: Context) {
        CrashGuard.clear(context) // pressing Live TV means TV Home is working
        shell(context, approvalWaitMs = 90_000).use { it.shell("pm enable $GOOGLE_HOME") }
        approved(context, true)
    }

    /** Switches Google's home app off, so Home belongs to TV Home again. Blocks. */
    fun switchOff(context: Context, bringHomeForward: Boolean) {
        shell(context, approvalWaitMs = 15_000).use { adb ->
            adb.shell("pm disable-user --user 0 $GOOGLE_HOME")
            if (bringHomeForward) adb.shell("am start -n ${context.packageName}/.HomeActivity")
        }
    }

    /** Whether the TV has already allowed this app's debugging key. */
    fun isApproved(context: Context) = prefs(context).getBoolean("approved", false)

    private fun approved(context: Context, value: Boolean) = prefs(context).edit().putBoolean("approved", value).apply()

    /**
     * The TV went to standby (or lost power) with Live TV on. The family nearly always goes
     * straight back to it, so the next time the screen comes on TV Home offers it first.
     */
    fun markOnAtStandby(context: Context) = prefs(context).edit().putBoolean("onAtStandby", true).apply()

    /** Whether Live TV was on at standby; asking clears it. */
    fun takeOnAtStandby(context: Context): Boolean {
        val prefs = prefs(context)
        if (!prefs.getBoolean("onAtStandby", false)) return false
        prefs.edit().remove("onAtStandby").apply()
        return true
    }

    private fun prefs(context: Context) = context.getSharedPreferences("freeplay", Context.MODE_PRIVATE)

    /** A connection to this TV's own adb port; socket reads wait at most [approvalWaitMs] (0 = forever). */
    fun shell(context: Context, approvalWaitMs: Int): Dadb {
        val dir = File(context.filesDir, "adb").apply { mkdirs() }
        val private = File(dir, "adbkey")
        val public = File(dir, "adbkey.pub")
        if (!private.exists() || !public.exists()) AdbKeyPair.generate(private, public)
        return Dadb.create("127.0.0.1", 5555, AdbKeyPair.read(private, public), 3_000, approvalWaitMs)
    }

    /** Set while Live TV is being opened, so TV Home doesn't tidy Google TV away mid-launch. */
    @Volatile var opening = false

    /** The component in a "wm_set_resumed_activity: [user,component,reason]" event line. */
    fun resumedComponent(eventLine: String): String? {
        val start = eventLine.indexOf("[0,").takeIf { it >= 0 } ?: return null
        val end = eventLine.indexOf(',', start + 3).takeIf { it >= 0 } ?: return null
        return eventLine.substring(start + 3, end)
    }

    /** The component in a dumpsys "mResumedActivity: ActivityRecord{… u0 component t…}" line. */
    fun frontComponent(dumpsysLine: String): String? =
        dumpsysLine.substringAfter(" u0 ", "").substringBefore(' ').takeIf { '/' in it }

    fun isFreeplayPage(component: String) = component.startsWith("$GOOGLE_HOME/") && "PageActivity" in component

    fun isGoogleHome(component: String) =
        component == HOME_ACTIVITY || component == "$GOOGLE_HOME/$GOOGLE_HOME.home.HomeActivity"

    fun log(message: String, e: Throwable? = null) {
        Log.w(TAG, message, e)
    }
}

/**
 * Runs while Freeplay is open, following which screen is in front through the TV's debugging
 * port. Once Freeplay has shown, landing on Google's home screen (by Home or by Back) switches
 * Google's home app off and brings TV Home back. Standby does the same straight away, so the TV
 * wakes up on TV Home. If the debugging connection drops while Google's home app is still on,
 * it connects again and carries on.
 */
class FreeplayWatcher : Service() {
    @Volatile private var stream: AdbShellStream? = null
    @Volatile private var stopped = false
    @Volatile private var destroyed = false
    @Volatile private var lastStartId = 0
    @Volatile private var resumeNext = false
    private var worker: Thread? = null

    private enum class Outcome { DONE, LOST }

    private val standby = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // The TV is going to standby with Live TV open. Hand Home back now; otherwise Google
            // TV's home greets whoever switches the TV on next.
            thread(name = "freeplay-standby") {
                if (!Freeplay.isGoogleHomeEnabled(this@FreeplayWatcher)) return@thread
                Freeplay.markOnAtStandby(this@FreeplayWatcher)
                runCatching { Freeplay.switchOff(this@FreeplayWatcher, bringHomeForward = true) }
                    .onSuccess {
                        stopped = true
                        runCatching { stream?.close() }
                    }
                    .onFailure { Freeplay.log("Couldn't switch Google TV off for standby", it) }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // System broadcasts still arrive with NOT_EXPORTED.
        ContextCompat.registerReceiver(this, standby, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A system restart after the process died, or an app update mid-Freeplay: Live TV is
        // already open, so pick up from there.
        resumeNext = intent == null || intent.getBooleanExtra(EXTRA_RESUME, false)
        lastStartId = startId
        val foreground = runCatching { startForeground(NOTIFICATION_ID, notification()) }
        if (foreground.isFailure) {
            Freeplay.log("Live TV watcher couldn't start", foreground.exceptionOrNull())
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (worker?.isAlive != true) {
            worker = thread(name = "freeplay-watch") {
                while (!destroyed) {
                    val id = lastStartId
                    stopped = false
                    var resume = resumeNext
                    var wait = 2_000L
                    while (watch(resume) == Outcome.LOST && !destroyed && Freeplay.isGoogleHomeEnabled(this)) {
                        // Google TV is still on: look again once the debugging port is back.
                        resume = true
                        try {
                            Thread.sleep(wait)
                        } catch (_: InterruptedException) {
                        }
                        wait = (wait * 2).coerceAtMost(30_000L)
                    }
                    // Live TV pressed again while this watch was ending: watch the new one too.
                    if (stopSelfResult(id)) break
                }
            }
        }
        return START_STICKY
    }

    private fun watch(resume: Boolean): Outcome {
        if (resume && (!Freeplay.isGoogleHomeEnabled(this) || CrashGuard.recentlyHandedBack(this))) return Outcome.DONE
        val home = "$packageName/.HomeActivity"
        // Freeplay opens by way of Google's home screen, so that only counts once Freeplay has shown.
        val freeplayShown = AtomicBoolean(resume) // read by the timeout thread too
        val giveUp = if (resume) null else thread(name = "freeplay-timeout") {
            try {
                Thread.sleep(45_000)
                if (!freeplayShown.get() && !stopped) {
                    stopped = true
                    runCatching { stream?.close() }
                    runCatching { Freeplay.switchOff(this, bringHomeForward = true) }
                }
            } catch (_: InterruptedException) {
            }
        }
        try {
            Freeplay.shell(this, approvalWaitMs = 0).use { adb ->
                if (resume) {
                    // Picking up mid-way: Google's home, or TV Home, may already be in front.
                    val front = Freeplay.frontComponent(adb.shell("dumpsys activity activities | grep -m1 mResumedActivity").output)
                    if (front != null && (front == home || Freeplay.isGoogleHome(front))) {
                        Freeplay.switchOff(this, bringHomeForward = front != home)
                        return Outcome.DONE
                    }
                }
                // -T 1: only what happens from now on.
                adb.openShell("logcat -T 1 -b events -v brief wm_set_resumed_activity:I *:S").use { shell ->
                    stream = shell
                    val pending = StringBuilder()
                    while (!stopped) {
                        val packet = shell.read()
                        if (packet is AdbShellPacket.Exit) break
                        if (packet !is AdbShellPacket.StdOut) continue
                        pending.append(String(packet.payload))
                        while (true) {
                            val end = pending.indexOf('\n')
                            if (end < 0) break
                            val component = Freeplay.resumedComponent(pending.substring(0, end))
                            pending.delete(0, end + 1)
                            when {
                                component == null -> Unit
                                Freeplay.isFreeplayPage(component) -> freeplayShown.set(true)
                                // Back on TV Home: it switches Google TV off itself. (TV Home's
                                // screensaver isn't TV Home, so that doesn't count.)
                                component == home && freeplayShown.get() -> return Outcome.DONE
                                freeplayShown.get() && Freeplay.isGoogleHome(component) -> {
                                    Freeplay.switchOff(this, bringHomeForward = true)
                                    return Outcome.DONE
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (!stopped) Freeplay.log("Freeplay watch ended", e)
        } finally {
            giveUp?.interrupt()
            stream = null
        }
        return if (stopped) Outcome.DONE else Outcome.LOST
    }

    override fun onDestroy() {
        destroyed = true
        stopped = true
        runCatching { unregisterReceiver(standby) }
        runCatching { stream?.close() }
        worker?.interrupt()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Live TV", NotificationManager.IMPORTANCE_MIN))
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Live TV is open")
            .setContentText("Press Home to go back to TV Home.")
            .build()
    }

    companion object {
        private const val CHANNEL = "freeplay"
        private const val NOTIFICATION_ID = 1

        private const val EXTRA_RESUME = "resume"

        fun start(context: Context, resume: Boolean = false) {
            context.startForegroundService(Intent(context, FreeplayWatcher::class.java).putExtra(EXTRA_RESUME, resume))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FreeplayWatcher::class.java))
        }
    }
}

/**
 * If the TV was switched off mid-Freeplay, Google's home would greet it at boot; undo that.
 * An update to TV Home mid-Freeplay stops the watcher, so start it again where it left off.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!Freeplay.isApproved(context) || !Freeplay.isGoogleHomeEnabled(context) || CrashGuard.recentlyHandedBack(context)) return
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            runCatching { FreeplayWatcher.start(context, resume = true) }
                .onFailure { Freeplay.log("Couldn't restart the Live TV watcher", it) }
            return
        }
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Freeplay.markOnAtStandby(context)
        val pending = goAsync()
        thread(name = "freeplay-boot") {
            try {
                // The debugging port comes up a little after boot. A boot broadcast gets about a
                // minute in all, so retries stop well inside that.
                val deadline = System.currentTimeMillis() + 45_000
                while (System.currentTimeMillis() < deadline) {
                    val ok = runCatching { Freeplay.switchOff(context, bringHomeForward = true) }.isSuccess
                    if (ok) return@thread
                    Thread.sleep(4_000)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
