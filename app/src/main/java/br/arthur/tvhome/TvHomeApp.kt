package br.arthur.tvhome

import android.app.Application
import android.content.Context
import android.util.Log
import kotlin.concurrent.thread

class TvHomeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashGuard.install(this)
    }
}

/**
 * The safety net for a home screen the whole family depends on. If TV Home crashes three times
 * within two minutes, it hands the Home button back to Google TV, so the TV is never left
 * without a usable home. TV Home takes Home back by itself the next time it opens cleanly
 * (see HomeActivity.onResume).
 */
object CrashGuard {
    private const val WINDOW_MS = 2 * 60_000L
    private const val LIMIT = 3
    private const val GRACE_MS = 10 * 60_000L

    /**
     * True for a while after handing Home back. Android restarts a crashed home screen at once,
     * and TV Home would otherwise switch Google TV straight back off and carry on crashing.
     */
    fun recentlyHandedBack(context: Context) =
        System.currentTimeMillis() - context.getSharedPreferences("crashes", Context.MODE_PRIVATE)
            .getLong("handedBackAt", 0) < GRACE_MS

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { record(context.applicationContext) }
            previous?.uncaughtException(t, e)
        }
    }

    /** TV Home is plainly working again (someone is using it): end the hand-back grace. */
    fun clear(context: Context) {
        context.getSharedPreferences("crashes", Context.MODE_PRIVATE).edit().remove("handedBackAt").apply()
    }

    private fun record(context: Context) {
        val prefs = context.getSharedPreferences("crashes", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val recent = prefs.getString("times", "").orEmpty().split(',')
            .mapNotNull { it.toLongOrNull() }
            .filter { now - it < WINDOW_MS } + now
        prefs.edit().putString("times", recent.joinToString(",")).commit()
        if (recent.size < LIMIT || !Freeplay.isApproved(context)) return
        Log.e("CrashGuard", "TV Home keeps crashing; handing Home back to Google TV")
        // The crashing thread may be the main one, where network calls aren't allowed.
        thread {
            runCatching { Freeplay.shell(context, 8_000).use { it.shell("pm enable ${Freeplay.GOOGLE_HOME}") } }
        }.join(10_000)
        prefs.edit().putString("times", "").putLong("handedBackAt", System.currentTimeMillis()).commit()
    }
}
