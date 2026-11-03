package br.arthur.tvhome

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.WindowManager

/** TV Home's screensavers, and which one Android shows when the TV sits idle. */
object Screensavers {
    enum class Style(val label: String, val dream: Class<out SaverDream>, val create: (Context) -> Saver) {
        SKY("Sky", SkyDream::class.java, ::SkySaver),
        CLOCK("Clock", ClockDream::class.java, ::ClockSaver),
    }

    private const val SETTING = "screensaver_components"

    /** The style Android will show, or null if it's set to something else. */
    fun current(context: Context): Style? {
        val first = Settings.Secure.getString(context.contentResolver, SETTING)?.split(',')?.firstOrNull() ?: return null
        val name = ComponentName.unflattenFromString(first) ?: return null
        return Style.entries.firstOrNull { ComponentName(context, it.dream) == name }
    }

    /**
     * Makes [style] the TV's screensaver. Blocks: call off the main thread. Uses
     * WRITE_SECURE_SETTINGS if adb granted it, else the TV's own debugging port (as Live TV does).
     */
    fun choose(context: Context, style: Style) {
        val value = ComponentName(context, style.dream).flattenToShortString()
        if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED) {
            Settings.Secure.putString(context.contentResolver, SETTING, value)
            Settings.Secure.putInt(context.contentResolver, "screensaver_enabled", 1)
        } else {
            Freeplay.shell(context, approvalWaitMs = 30_000).use {
                it.shell("settings put secure $SETTING $value && settings put secure screensaver_enabled 1")
            }
        }
        check(current(context) == style) { "screensaver setting didn't change" }
    }

    fun preview(context: Context, style: Style) {
        context.startActivity(
            Intent(context, ScreensaverPreview::class.java)
                .putExtra(ScreensaverPreview.STYLE, style.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/**
 * A screensaver full screen, until any key. Opened from the Settings button's menu; also handy
 * over adb: `am start -n br.arthur.tvhome/.ScreensaverPreview --es style SKY --ei minute 1140
 * --ef speed 60` shows the sky from 19:00 at a minute a second.
 */
class ScreensaverPreview : Activity() {
    private var saver: Saver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.setBackgroundColor(Color.BLACK)
        val style = runCatching { Screensavers.Style.valueOf(intent.getStringExtra(STYLE)!!) }.getOrDefault(Screensavers.Style.SKY)
        val saver = style.create(this).also { saver = it }
        if (saver is SkySaver) {
            val minute = intent.getIntExtra(MINUTE, -1)
            saver.sky.setTime(minute.takeIf { it >= 0 }, intent.getFloatExtra(SPEED, 1f))
        }
        setContentView(saver.view)
    }

    override fun onStart() {
        super.onStart()
        saver?.start()
    }

    override fun onStop() {
        saver?.stop()
        super.onStop()
        finish()
    }

    // Like the real screensaver, any key ends it; the key itself does nothing else.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP) finish()
        return true
    }

    companion object {
        const val STYLE = "style"
        const val MINUTE = "minute"
        const val SPEED = "speed"
    }
}
