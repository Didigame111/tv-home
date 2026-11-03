package br.arthur.tvhome

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import kotlin.random.Random

/** A screensaver's content, shown by its DreamService or full screen by [ScreensaverPreview]. */
interface Saver {
    val view: View
    fun start()
    fun stop()
}

/** Hosts a [Saver]. Plain views, so a screensaver costs little compared with Ambient mode. */
abstract class SaverDream : DreamService() {
    private var saver: Saver? = null

    abstract fun create(): Saver

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = false
        setContentView(create().also { saver = it }.view)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        saver?.start()
    }

    override fun onDreamingStopped() {
        saver?.stop()
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        saver?.stop()
        super.onDetachedFromWindow()
    }
}

/** The classic screensaver: the home screen's clock on black, drifting to a new spot every minute. */
class ClockDream : SaverDream() {
    override fun create(): Saver = ClockSaver(this)
}

class ClockSaver(context: Context) : Saver {
    private val clock = DriftingClock(context, restAlpha = 0.8f, shadow = false)
    override val view = FrameLayout(context).apply {
        setBackgroundColor(Color.BLACK)
        addView(clock.view, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
    }
    private val minutes = MinuteTicker(view) {
        clock.showNext { w, h ->
            val margin = clock.dp(48)
            val freeX = (view.width - w - 2 * margin).coerceAtLeast(1)
            val freeY = (view.height - h - 2 * margin).coerceAtLeast(1)
            (margin + Random.nextInt(freeX)).toFloat() to (margin + Random.nextInt(freeY)).toFloat()
        }
    }

    override fun start() = minutes.start()
    override fun stop() = minutes.stop()
}

/** Runs [onMinute] now and then just after every minute turns, until stopped. */
class MinuteTicker(private val view: View, private val onMinute: () -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            onMinute()
            handler.postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000 + 50)
        }
    }

    fun start() {
        stop()
        view.post(tick)
    }

    fun stop() {
        handler.removeCallbacks(tick)
        view.removeCallbacks(tick)
    }
}

/** The screensavers' clock: time over date, fading out and back in somewhere new. */
class DriftingClock(private val context: Context, private val restAlpha: Float, shadow: Boolean) {
    private val time = TextView(context).apply {
        typeface = ResourcesCompat.getFont(context, R.font.instrument_serif)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 132f)
        setTextColor(0xFFF3EEE4.toInt())
        includeFontPadding = false
        maxLines = 1
        // Over a bright sky the words need a soft edge to stand out.
        if (shadow) setShadowLayer(dp(14).toFloat(), 0f, dp(2).toFloat(), 0x59000000)
    }
    private val date = TextView(context).apply {
        typeface = ResourcesCompat.getFont(context, R.font.figtree)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        setTextColor(if (shadow) 0xFFE6E8F0.toInt() else 0xFFA9B0C8.toInt())
        setPadding(dp(4), dp(6), 0, 0)
        if (shadow) setShadowLayer(dp(8).toFloat(), 0f, dp(1).toFloat(), 0x66000000)
    }
    val view = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        alpha = 0f
        clipChildren = false
        clipToPadding = false
        // Wrap, not the vertical LinearLayout's default match_parent: a match_parent TextView keeps
        // its old width when the text changes, so a wider minute wrapped its last digit out of view.
        addView(time, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        // Digits have no descenders; pull the date up into the font's empty space below them.
        addView(date, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = -dp(24) })
    }

    /** Fades out, sets the current time, and fades in where [place] puts a block of that size. */
    fun showNext(place: (width: Int, height: Int) -> Pair<Float, Float>) {
        view.animate().alpha(0f).setDuration(if (view.alpha > 0f) 700 else 0).withEndAction {
            refresh()
            view.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            val (x, y) = place(view.measuredWidth, view.measuredHeight)
            view.translationX = x
            view.translationY = y
            view.animate().alpha(restAlpha).setDuration(1400).start()
        }.start()
    }

    /** Updates the time and date in place. */
    fun refresh() {
        val now = System.currentTimeMillis()
        time.text = ClockFormat.time(context, now)
        date.text = ClockFormat.date(now)
    }

    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
