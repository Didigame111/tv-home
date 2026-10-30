package br.arthur.tvhome

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

/**
 * Screensaver: the home screen's clock on black, drifting to a new spot every minute so nothing
 * stays still on the panel. Plain views, so it costs almost nothing compared with Ambient mode.
 */
class ClockDream : DreamService() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private lateinit var block: LinearLayout
    private lateinit var time: TextView
    private lateinit var date: TextView

    private val tick = object : Runnable {
        override fun run() {
            showNext()
            handler.postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000 + 50)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = false

        time = TextView(this).apply {
            typeface = ResourcesCompat.getFont(context, R.font.instrument_serif)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 132f)
            setTextColor(0xFFF3EEE4.toInt())
            includeFontPadding = false
            maxLines = 1
        }
        date = TextView(this).apply {
            typeface = ResourcesCompat.getFont(context, R.font.figtree)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTextColor(0xFFA9B0C8.toInt())
            setPadding(dp(4), dp(6), 0, 0)
        }
        block = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            alpha = 0f
            // Wrap, not the vertical LinearLayout's default match_parent: a match_parent TextView keeps
            // its old width when the text changes, so a wider minute wrapped its last digit out of view.
            addView(time, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            // Digits have no descenders; pull the date up into the font's empty space below them.
            addView(date, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = -dp(24) })
        }
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(block, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
        setContentView(root)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        root.post(tick)
    }

    override fun onDreamingStopped() {
        handler.removeCallbacks(tick)
        root.removeCallbacks(tick)
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    private fun showNext() {
        block.animate().alpha(0f).setDuration(if (block.alpha > 0f) 700 else 0).withEndAction {
            val now = System.currentTimeMillis()
            time.text = ClockFormat.time(this, now)
            date.text = ClockFormat.date(now)
            block.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            val margin = dp(48)
            val freeX = (root.width - block.measuredWidth - 2 * margin).coerceAtLeast(1)
            val freeY = (root.height - block.measuredHeight - 2 * margin).coerceAtLeast(1)
            block.translationX = (margin + Random.nextInt(freeX)).toFloat()
            block.translationY = (margin + Random.nextInt(freeY)).toFloat()
            block.animate().alpha(0.8f).setDuration(1400).start()
        }.start()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
