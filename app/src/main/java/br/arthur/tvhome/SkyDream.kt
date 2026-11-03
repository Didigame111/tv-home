package br.arthur.tvhome

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** The Sky screensaver: hills under a sky that follows the time of day, with the clock above. */
class SkyDream : SaverDream() {
    override fun create(): Saver = SkySaver(this)
}

class SkySaver(context: Context) : Saver {
    val sky = SkyView(context)
    private val clock = DriftingClock(context, restAlpha = 0.92f, shadow = true)
    override val view = FrameLayout(context).apply {
        clipChildren = false
        addView(sky, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(clock.view, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
    }

    // The clock keeps to the sky, clear of the sun and moon, and moves every few minutes.
    private var minute = 0
    private val minutes = MinuteTicker(view) {
        if (minute++ % 3 == 0) clock.showNext(::place) else clock.refresh()
    }

    private fun place(w: Int, h: Int): Pair<Float, Float> {
        val margin = clock.dp(56)
        val maxX = (view.width - w - margin).coerceAtLeast(margin + 1)
        val maxY = (view.height * 0.52f - h).toInt().coerceAtLeast(margin + 1)
        val avoid = sky.bodies()
        var best = margin.toFloat() to margin.toFloat()
        for (i in 0 until 16) {
            val x = Random.nextInt(margin, maxX).toFloat()
            val y = Random.nextInt(margin, maxY).toFloat()
            best = x to y
            val box = RectF(x, y, x + w, y + h)
            if (avoid.none { RectF.intersects(it, box) }) break
        }
        return best
    }

    override fun start() {
        sky.running = true
        minutes.start()
    }

    override fun stop() {
        sky.running = false
        minutes.stop()
    }
}

/**
 * A window onto hills under the real sky. The sun crosses from 6:30 to 18:30, dawn and dusk warm
 * the horizon, and at night the stars come out and wheel slowly round the pole, the moon shows
 * tonight's phase, a shooting star crosses now and then, and lamps come on in the valley (most go
 * out late at night, and come back on before dawn). Clouds drift across all day.
 *
 * Kept cheap for the TV: the sky and the hills are repainted into bitmaps every couple of seconds,
 * clouds are pre-blurred masks, and frames come at 15 fps (30 while a shooting star crosses).
 */
class SkyView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    // Time: the real clock, unless a preview asks for another time of day or a time-lapse.
    private var realStart = System.currentTimeMillis()
    private var sceneStart = realStart
    private var speed = 1f

    /** Shows the sky at [minuteOfDay] (0–1439) today, running [speed] times faster than real time. */
    fun setTime(minuteOfDay: Int?, speed: Float) {
        realStart = System.currentTimeMillis()
        sceneStart = if (minuteOfDay == null) realStart else Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis + minuteOfDay.coerceIn(0, 1439) * 60_000L
        this.speed = speed.coerceAtLeast(0.1f)
        lastLook = 0L
        invalidate()
    }

    private fun sceneNow() = sceneStart + ((System.currentTimeMillis() - realStart) * speed).toLong()

    var running = false
        set(value) {
            field = value
            if (value) invalidate() else removeCallbacks(frame)
        }

    // ---------------------------------------------------------------- the landscape, per size and day

    private var horizon = 0f
    private var maskTop = 0
    private var landDay = -1L
    private val hills = arrayOfNulls<Bitmap>(4)
    private val hillMix = floatArrayOf(0.28f, 0.52f, 0.76f, 1f)

    private class Star(val r: Float, val angle: Float, val size: Float, val bright: Float, val color: Int, val phase: Float, val rate: Float)
    private var stars = emptyArray<Star>()
    private var poleX = 0f
    private var poleY = 0f

    private class Lamp(val x: Float, val y: Float, val onBelow: Float, val offAt: Int, val phase: Float)
    private var lamps = emptyArray<Lamp>()

    private class Cloud(val bitmap: Bitmap, val width: Float, val height: Float, val y: Float, var x: Float, val speed: Float)
    private var clouds = emptyArray<Cloud>()
    private val cloudScale = 0.5f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w == 0 || h == 0) return
        horizon = h * 0.74f
        buildSky(w, h)
        buildLand(w, h, today())
        lastLook = 0L
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(frame)
        super.onDetachedFromWindow()
    }

    private fun today(): Long {
        val c = Calendar.getInstance().apply { timeInMillis = sceneNow() }
        return c.get(Calendar.YEAR) * 1000L + c.get(Calendar.DAY_OF_YEAR)
    }

    /** Stars and clouds: the same every day. */
    private fun buildSky(w: Int, h: Int) {
        val rnd = Random(7)
        // Stars sit on circles round a pole above the top right, so turning them is one angle.
        poleX = w * 0.82f
        poleY = -h * 0.28f
        val inner = -poleY
        val outer = hypot(poleX, horizon - poleY)
        stars = Array(1500) {
            val u = rnd.nextFloat()
            val b = rnd.nextFloat()
            val tint = rnd.nextFloat()
            Star(
                r = sqrt(inner * inner + rnd.nextFloat() * (outer * outer - inner * inner)),
                angle = rnd.nextFloat() * 2 * PI.toFloat(),
                size = dp(0.45f + 1.25f * u.pow(5)),
                bright = 0.3f + 0.7f * max(b * b, u.pow(5)),
                color = when {
                    tint < 0.1f -> 0xFFCFDBFF.toInt()
                    tint < 0.15f -> 0xFFFFE2B8.toInt()
                    else -> 0xFFF6F3EA.toInt()
                },
                phase = rnd.nextFloat() * 6.28f,
                rate = 0.6f + rnd.nextFloat() * 2.2f,
            )
        }

        clouds.forEach { it.bitmap.recycle() }
        clouds = Array(6) { i ->
            val width = dp(240f + rnd.nextFloat() * 260f)
            val height = width * 0.34f
            val blur = dp(14f) * cloudScale
            val bw = (width * cloudScale + 4 * blur).toInt()
            val bh = (height * cloudScale + 4 * blur).toInt()
            val bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ALPHA_8)
            val canvas = Canvas(bitmap)
            val puff = Paint(Paint.ANTI_ALIAS_FLAG).apply { maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL) }
            val base = 2 * blur + height * cloudScale * 0.78f
            val n = 7 + rnd.nextInt(5)
            for (p in 0 until n) {
                val cx = 2 * blur + width * cloudScale * (0.12f + 0.76f * p / (n - 1)) + rnd.nextFloat() * dp(10f)
                // Taller puffs in the middle, so each cloud has a crown.
                val middle = 1f - abs(p / (n - 1f) - 0.5f) * 2f
                val rx = width * cloudScale * (0.09f + 0.07f * middle + rnd.nextFloat() * 0.05f)
                val ry = rx * (0.62f + rnd.nextFloat() * 0.3f)
                puff.alpha = 150 + rnd.nextInt(100)
                canvas.drawOval(cx - rx, base - ry * (1.2f + middle * 0.6f), cx + rx, base + ry * 0.35f, puff)
            }
            Cloud(
                bitmap,
                width = bw / cloudScale,
                height = bh / cloudScale,
                y = h * (0.05f + 0.07f * i + rnd.nextFloat() * 0.05f),
                x = rnd.nextFloat() * (w + width) - width,
                speed = dp(1.6f + rnd.nextFloat() * 3.2f) * (0.7f + 0.3f * (i / 5f)),
            )
        }
    }

    /** Hills and the valley's lamps: a new landscape every day. */
    private fun buildLand(w: Int, h: Int, day: Long) {
        landDay = day
        val rnd = Random(day)
        maskTop = (h * 0.52f).toInt()
        val bases = floatArrayOf(0.66f, 0.745f, 0.83f, 0.915f)
        val amps = floatArrayOf(0.10f, 0.065f, 0.05f, 0.035f)
        val ridges = Array(4) { layer ->
            val k1 = 2 * PI.toFloat() / (w * (0.55f + rnd.nextFloat() * 0.6f))
            val k2 = 2 * PI.toFloat() / (w * (0.2f + rnd.nextFloat() * 0.18f))
            val k3 = 2 * PI.toFloat() / (w * (0.06f + rnd.nextFloat() * 0.05f))
            val p1 = rnd.nextFloat() * 6.28f
            val p2 = rnd.nextFloat() * 6.28f
            val p3 = rnd.nextFloat() * 6.28f
            FloatArray(w / 4 + 2) { i ->
                val x = i * 4f
                // The far range is peaked (folded sines); the nearer hills roll.
                val f = if (layer == 0) {
                    0.6f * (1 - abs(sin(x * k1 + p1))).pow(1.4f) + 0.3f * (1 - abs(sin(x * k2 + p2))) + 0.1f * sin(x * k3 + p3)
                } else {
                    0.5f + 0.32f * sin(x * k1 + p1) + 0.13f * sin(x * k2 + p2) + 0.05f * sin(x * k3 + p3)
                }
                h * bases[layer] - h * amps[layer] * (f * 2 - 0.6f)
            }
        }
        fun ridge(layer: Int, x: Float): Float {
            val i = (x / 4f).toInt().coerceIn(0, ridges[layer].size - 2)
            val t = (x / 4f - i).coerceIn(0f, 1f)
            return ridges[layer][i] * (1 - t) + ridges[layer][i + 1] * t
        }

        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        for (layer in 0 until 4) {
            val bitmap = hills[layer]?.takeIf { it.width == w && it.height == h - maskTop }
                ?: Bitmap.createBitmap(w, h - maskTop, Bitmap.Config.ALPHA_8).also { hills[layer]?.recycle() }
            bitmap.eraseColor(Color.TRANSPARENT)
            val path = Path().apply {
                moveTo(0f, (h - maskTop).toFloat())
                ridges[layer].forEachIndexed { i, y -> lineTo(i * 4f, y - maskTop) }
                lineTo(w.toFloat(), (h - maskTop).toFloat())
                close()
            }
            Canvas(bitmap).drawPath(path, fill)
            hills[layer] = bitmap
        }

        // Lamps live on the third range, where the nearest hills don't hide them.
        val found = ArrayList<Lamp>()
        var tries = 0
        while (found.size < 22 && tries++ < 800) {
            val x = dp(20f) + rnd.nextFloat() * (w - dp(40f))
            val y = ridge(2, x) + dp(5f) + rnd.nextFloat() * dp(26f)
            if (y > ridge(3, x) - dp(4f)) continue
            if (found.any { abs(it.x - x) < dp(14f) && abs(it.y - y) < dp(10f) }) continue
            found += Lamp(
                x, y,
                onBelow = -0.02f + rnd.nextFloat() * 0.1f,
                // Most homes turn in between 21:30 and 01:30; a few stay lit all night.
                offAt = if (rnd.nextFloat() < 0.8f) 1290 + rnd.nextInt(240) else -1,
                phase = rnd.nextFloat() * 6.28f,
            )
        }
        lamps = found.toTypedArray()
    }

    // ---------------------------------------------------------------- the look, every couple of seconds

    private var lastLook = 0L
    private var sunElev = 0f
    private var sunX = 0f
    private var sunY = 0f
    private var moonElev = 0f
    private var moonX = 0f
    private var moonY = 0f
    private var moonPhase = 0f
    private var starVis = 0f
    private var lampVis = 0f
    private var minuteOfDay = 0f
    private val hillColors = IntArray(4)
    private var cloudColor = Color.WHITE
    private var cloudAlpha = 0.5f

    private val skyPaint = Paint().apply { isDither = true }
    private val glowPaint = Paint().apply { isDither = true }
    private val sunPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isDither = true }
    private val moonPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val moonHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply { isDither = true }
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hillPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val cloudPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val lampPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lampGlow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = dp(1.6f)
        strokeCap = Paint.Cap.ROUND
    }
    private val moonPath = Path()
    private val rect = RectF()

    private fun updateLook(w: Int, h: Int) {
        val now = sceneNow()
        val c = Calendar.getInstance().apply { timeInMillis = now }
        if (c.get(Calendar.YEAR) * 1000L + c.get(Calendar.DAY_OF_YEAR) != landDay) buildLand(w, h, today())
        val m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE) + c.get(Calendar.SECOND) / 60f
        minuteOfDay = m
        val (e, frac) = elevation(m)
        sunElev = e
        val morning = m < 12.5f * 60
        val top = horizon * 0.12f

        sunX = w * (0.08f + 0.84f * frac)
        sunY = horizon - e * (horizon - top)

        // The moon is the sun's path, late by its phase: full moon is high at midnight.
        moonPhase = moonPhase(now)
        val (me, mf) = elevation((m - moonPhase * 1440 + 1440) % 1440)
        moonElev = me
        moonX = w * (0.08f + 0.84f * mf)
        moonY = horizon - me * (horizon - top) * 0.9f

        val ramp = if (morning) DAWN else DUSK
        val zenith = ramp.zenith(e)
        val middle = ramp.middle(e)
        val low = ramp.horizon(e)
        skyPaint.shader = LinearGradient(0f, 0f, 0f, horizon, intArrayOf(zenith, middle, low), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)

        // The horizon glows where the sun is, around sunrise and sunset.
        val glow = (1f - abs(e) / 0.32f).coerceIn(0f, 1f)
        val glowX = sunX.coerceIn(w * 0.04f, w * 0.96f)
        glowPaint.shader = if (glow > 0.01f) RadialGradient(
            glowX, horizon, w * 0.62f,
            intArrayOf(withAlpha(ramp.glow(e), 0.62f * glow), withAlpha(ramp.glow(e), 0.22f * glow), Color.TRANSPARENT),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
        ) else null

        starVis = ((-e - 0.03f) / 0.22f).coerceIn(0f, 1f)
        lampVis = ((0.12f - e) / 0.16f).coerceIn(0f, 1f)

        val near = NEAR.at(e)
        for (i in 0 until 4) hillColors[i] = mix(low, near, hillMix[i])
        cloudColor = CLOUD.at(e)
        cloudAlpha = 0.36f + 0.14f * glow

        val sunColor = SUN.at(e)
        sunPaint.color = sunColor
        haloPaint.shader = RadialGradient(
            sunX, sunY, dp(150f),
            intArrayOf(withAlpha(sunColor, 0.45f), withAlpha(sunColor, 0.12f), Color.TRANSPARENT),
            floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP,
        )
        paintBack(w)
        paintFront(w, h)
    }

    // What changes only with the time of day is painted into two bitmaps every couple of seconds,
    // so a frame is two big bitmap draws plus the small moving things. The sky is painted at half
    // size (gradients scale up without a seam); the hills at full size, for crisp ridges.
    private var back: Bitmap? = null
    private var front: Bitmap? = null
    private val copy = Paint(Paint.FILTER_BITMAP_FLAG)

    private fun paintBack(w: Int) {
        val bh = (horizon + dp(12f)).toInt()
        val bitmap = back?.takeIf { it.width == w / 2 && it.height == bh / 2 }
            ?: Bitmap.createBitmap(w / 2, bh / 2, Bitmap.Config.ARGB_8888).also { back?.recycle(); back = it }
        val canvas = Canvas(bitmap)
        canvas.scale(0.5f, 0.5f)
        canvas.drawRect(0f, 0f, w.toFloat(), bh.toFloat(), skyPaint)
        if (glowPaint.shader != null) canvas.drawRect(0f, 0f, w.toFloat(), bh.toFloat(), glowPaint)
    }

    private fun paintFront(w: Int, h: Int) {
        val bitmap = front?.takeIf { it.width == w && it.height == h - maskTop }
            ?: Bitmap.createBitmap(w, h - maskTop, Bitmap.Config.ARGB_8888).also { front?.recycle(); front = it }
        bitmap.eraseColor(Color.TRANSPARENT)
        val canvas = Canvas(bitmap)
        for (layer in 0 until 4) {
            if (layer == 3) {
                canvas.save()
                canvas.translate(0f, -maskTop.toFloat())
                drawLamps(canvas)
                canvas.restore()
            }
            hillPaint.color = hillColors[layer]
            canvas.drawBitmap(hills[layer]!!, 0f, 0f, hillPaint)
        }
    }

    /**
     * How high the sun is for a minute of the day, -1 (midnight) to 1 (noon), and how far across
     * the sky it is (0 at sunrise, 1 at sunset; beyond that at night).
     */
    private fun elevation(m: Float): Pair<Float, Float> {
        val rise = 390f
        val set = 1110f
        return if (m in rise..set) {
            val f = (m - rise) / (set - rise)
            sin(PI.toFloat() * f) to f
        } else {
            val n = ((m - set + 1440) % 1440) / (1440 - (set - rise))
            // Past sunset it carries on off the right; before sunrise it comes in from the left.
            -sin(PI.toFloat() * n) to (if (n < 0.5f) 1f + n else n - 1f)
        }
    }

    // ---------------------------------------------------------------- drawing

    private var lastFrame = 0L
    private var shootStart = 0L
    private var shootNext = 0L
    private val shoot = FloatArray(5) // x, y, dx, dy, length
    private val frame = Runnable { invalidate() }

    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height
        if (w == 0 || h == 0 || hills[0] == null) return
        val real = SystemClock.uptimeMillis()
        if (real - lastLook > (if (speed > 1f) 250 else 2_000)) {
            lastLook = real
            updateLook(w, h)
        }
        val dt = if (lastFrame == 0L) 0f else ((real - lastFrame).coerceAtMost(200)) / 1000f
        lastFrame = real
        val t = real / 1000f

        back?.let {
            rect.set(0f, 0f, w.toFloat(), it.height * 2f)
            canvas.drawBitmap(it, null, rect, copy)
        }

        drawStars(canvas, w, t)
        drawShootingStar(canvas, w, h, real)

        if (moonElev > -0.08f && moonLit() > 0.03f) drawMoon(canvas)
        if (sunElev > -0.1f) {
            canvas.drawCircle(sunX, sunY, dp(150f), haloPaint)
            canvas.drawCircle(sunX, sunY, dp(30f), sunPaint)
        }

        for (cloud in clouds) {
            cloud.x += cloud.speed * dt
            if (cloud.x > w) cloud.x = -cloud.width
            rect.set(cloud.x, cloud.y, cloud.x + cloud.width, cloud.y + cloud.height)
            // Clouds are alpha masks, like the hills: the paint's colour fills them.
            cloudPaint.color = withAlpha(cloudColor, cloudAlpha)
            canvas.drawBitmap(cloud.bitmap, null, rect, cloudPaint)
        }

        front?.let { canvas.drawBitmap(it, 0f, maskTop.toFloat(), null) }

        // 15 fps is plenty for drifting clouds and twinkling; a shooting star gets 30.
        if (running) postOnAnimationDelayed(frame, if (shootStart != 0L) 30 else 63)
    }

    private fun drawStars(canvas: Canvas, w: Int, t: Float) {
        if (starVis <= 0.01f) return
        // One turn a sidereal day, against the clock as you face the pole.
        val turn = -((sceneNow() % 86_164_091L) / 86_164_091.0 * 2 * PI).toFloat()
        val bottom = horizon + dp(20f)
        for (s in stars) {
            val a = s.angle + turn
            val x = poleX + s.r * cos(a)
            if (x < -4 || x > w + 4) continue
            val y = poleY + s.r * sin(a)
            if (y < -4 || y > bottom) continue
            // Stars low in the sky are dimmer, as they are through more air.
            val air = ((bottom - y) / (horizon * 0.35f)).coerceIn(0.2f, 1f)
            val twinkle = 0.78f + 0.22f * sin(t * s.rate + s.phase)
            starPaint.color = s.color
            starPaint.alpha = (255 * starVis * s.bright * twinkle * air).toInt().coerceIn(0, 255)
            canvas.drawCircle(x, y, s.size, starPaint)
        }
    }

    private fun drawShootingStar(canvas: Canvas, w: Int, h: Int, real: Long) {
        if (shootNext == 0L) shootNext = real + 8_000
        if (starVis < 0.7f) return
        if (shootStart == 0L && real > shootNext) {
            shootStart = real
            val left = Random.nextBoolean()
            val angle = (0.3f + Random.nextFloat() * 0.35f)
            shoot[0] = w * (0.15f + Random.nextFloat() * 0.7f)
            shoot[1] = h * (0.05f + Random.nextFloat() * 0.25f)
            shoot[2] = cos(angle) * (if (left) -1 else 1)
            shoot[3] = sin(angle)
            shoot[4] = dp(130f + Random.nextFloat() * 90f)
        }
        if (shootStart == 0L) return
        val p = (real - shootStart) / 900f
        if (p >= 1f) {
            shootStart = 0L
            shootNext = real + 20_000 + Random.nextLong(50_000)
            return
        }
        val travel = shoot[4] * 2.2f * (1 - (1 - p) * (1 - p))
        val hx = shoot[0] + shoot[2] * travel
        val hy = shoot[1] + shoot[3] * travel
        val len = shoot[4] * min(1f, p * 2.5f)
        val tx = hx - shoot[2] * len
        val ty = hy - shoot[3] * len
        val fade = sin(PI.toFloat() * p) * starVis
        trailPaint.shader = LinearGradient(tx, ty, hx, hy, Color.TRANSPARENT, withAlpha(0xFFFFFFFF.toInt(), fade), Shader.TileMode.CLAMP)
        canvas.drawLine(tx, ty, hx, hy, trailPaint)
    }

    private fun moonLit() = (1 - cos(2 * PI * moonPhase).toFloat()) / 2

    private fun drawMoon(canvas: Canvas) {
        val r = dp(24f)
        val cx = moonX
        val cy = moonY
        val night = max(starVis, 0.35f)
        // A soft halo, then the dark disc faintly (earthshine), then the lit part.
        moonHalo.shader = RadialGradient(cx, cy, r * 5, withAlpha(0xFFDDE4FF.toInt(), 0.18f * night * moonLit()), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r * 5, moonHalo)
        moonPaint.color = withAlpha(0xFF2A3154.toInt(), 0.55f * starVis)
        canvas.drawCircle(cx, cy, r, moonPaint)

        val phi = 2 * PI * moonPhase
        val waxing = moonPhase < 0.5f
        val crescent = moonLit() < 0.5f
        val rx = r * abs(cos(phi)).toFloat()
        moonPath.reset()
        rect.set(cx - r, cy - r, cx + r, cy + r)
        moonPath.arcTo(rect, -90f, if (waxing) 180f else -180f)
        rect.set(cx - rx, cy - r, cx + rx, cy + r)
        // Back up the terminator: a crescent's bows toward the lit side, a gibbous moon's away.
        moonPath.arcTo(rect, 90f, if (waxing == crescent) -180f else 180f)
        moonPath.close()
        moonPaint.color = withAlpha(0xFFF2ECDC.toInt(), 0.4f + 0.6f * night)
        canvas.drawPath(moonPath, moonPaint)

        // A few grey seas, on the lit part only.
        canvas.save()
        canvas.clipPath(moonPath)
        moonPaint.color = withAlpha(0xFF8C8FA0.toInt(), 0.22f)
        canvas.drawCircle(cx - r * 0.3f, cy - r * 0.25f, r * 0.32f, moonPaint)
        canvas.drawCircle(cx + r * 0.25f, cy + r * 0.05f, r * 0.24f, moonPaint)
        canvas.drawCircle(cx - r * 0.05f, cy + r * 0.45f, r * 0.18f, moonPaint)
        canvas.restore()
    }

    private fun drawLamps(canvas: Canvas) {
        if (lampVis <= 0.01f) return
        val m = minuteOfDay.toInt()
        for (lamp in lamps) {
            if (sunElev > lamp.onBelow) continue
            // Off from bedtime until 5:30.
            if (lamp.offAt >= 0) {
                val asleep = if (lamp.offAt < 1440) m >= lamp.offAt || m < 330 else m in (lamp.offAt - 1440) until 330
                if (asleep) continue
            }
            // Lamps differ a little in brightness, and glow softly.
            val a = lampVis * (0.75f + 0.25f * sin(lamp.phase))
            lampGlow.shader = RadialGradient(lamp.x, lamp.y, dp(6f), withAlpha(LAMP, 0.22f * a), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawCircle(lamp.x, lamp.y, dp(6f), lampGlow)
            lampPaint.color = withAlpha(LAMP, a)
            canvas.drawCircle(lamp.x, lamp.y, dp(1.1f), lampPaint)
        }
    }

    /** Where the sun and moon are, so the clock can stay out of their way. */
    fun bodies(): List<RectF> {
        val out = ArrayList<RectF>(2)
        val pad = dp(70f)
        if (sunElev > -0.1f) out += RectF(sunX - pad, sunY - pad, sunX + pad, sunY + pad)
        if (moonElev > -0.08f) out += RectF(moonX - pad, moonY - pad, moonX + pad, moonY + pad)
        return out
    }

    // ---------------------------------------------------------------- colours

    /** Colours keyed by the sun's height, blended between the keys. */
    private class Ramp(private val keys: FloatArray, private val colors: IntArray) {
        fun at(e: Float): Int {
            if (e <= keys.first()) return colors.first()
            for (i in 1 until keys.size) {
                if (e <= keys[i]) return mix(colors[i - 1], colors[i], (e - keys[i - 1]) / (keys[i] - keys[i - 1]))
            }
            return colors.last()
        }
    }

    private class Palette(val zenith: Ramp, val middle: Ramp, val horizon: Ramp, val glow: Ramp) {
        fun zenith(e: Float) = zenith.at(e)
        fun middle(e: Float) = middle.at(e)
        fun horizon(e: Float) = horizon.at(e)
        fun glow(e: Float) = glow.at(e)
    }

    companion object {
        private const val LAMP = 0xFFFFC870.toInt()
        private val E = floatArrayOf(-1f, -0.35f, -0.15f, -0.04f, 0.04f, 0.15f, 0.35f, 1f)

        private fun ramp(vararg c: Long) = Ramp(E, IntArray(c.size) { c[it].toInt() })

        // Evening: violet, then coral and amber low down.
        private val DUSK = Palette(
            zenith = ramp(0xFF04060F, 0xFF080C1E, 0xFF121838, 0xFF1D2350, 0xFF2D3A6E, 0xFF3A5A96, 0xFF3C6FAD, 0xFF3A6EB2),
            middle = ramp(0xFF070B19, 0xFF0D1431, 0xFF26295A, 0xFF4B3B72, 0xFF8A5A80, 0xFFC08A86, 0xFF86AED6, 0xFF7FB0DE),
            horizon = ramp(0xFF0C1328, 0xFF1A2146, 0xFF4A3566, 0xFFB5596A, 0xFFEE8A5C, 0xFFF3BC80, 0xFFC9DEEC, 0xFFBAD8EE),
            glow = ramp(0xFF1A2146, 0xFF2A2350, 0xFF7A3A6A, 0xFFE0645A, 0xFFFF9A5A, 0xFFFFC27A, 0xFFFFE6B8, 0xFFFFF0D0),
        )

        // Morning: the same night, waking to rose and peach.
        private val DAWN = Palette(
            zenith = ramp(0xFF04060F, 0xFF080C1E, 0xFF141A3E, 0xFF22295A, 0xFF34437A, 0xFF4064A0, 0xFF3C70AE, 0xFF3A6EB2),
            middle = ramp(0xFF070B19, 0xFF0D1431, 0xFF2E3266, 0xFF5A4A80, 0xFFA07094, 0xFFD2A0A0, 0xFF8DB4DA, 0xFF7FB0DE),
            horizon = ramp(0xFF0C1328, 0xFF1A2146, 0xFF54406E, 0xFFC76F8A, 0xFFF4A483, 0xFFF7CB9A, 0xFFCDE0EC, 0xFFBAD8EE),
            glow = ramp(0xFF1A2146, 0xFF2C2655, 0xFF8A4A7A, 0xFFF0807E, 0xFFFFB08A, 0xFFFFD2A0, 0xFFFFEAC4, 0xFFFFF0D0),
        )

        // The nearest hills, darker than the sky; farther ranges blend toward the horizon.
        private val NEAR = ramp(0xFF020309, 0xFF04060F, 0xFF0A0B1C, 0xFF14122A, 0xFF1C1C34, 0xFF22283E, 0xFF253848, 0xFF243D4C)
        private val CLOUD = ramp(0xFF222A4C, 0xFF262E52, 0xFF4A3E6E, 0xFFC0727E, 0xFFF1A48C, 0xFFF8D2B8, 0xFFF7F8FB, 0xFFFFFFFF)
        private val SUN = ramp(0xFFFF7A40, 0xFFFF7A40, 0xFFFF7A40, 0xFFFF8248, 0xFFFF9C58, 0xFFFFC27A, 0xFFFFEDC2, 0xFFFFF8E4)

        /** Moon phase 0–1: 0 new, 0.5 full. From the new moon of 6 Jan 2000, 18:14 UTC. */
        fun moonPhase(millis: Long): Float {
            val days = (millis - 947_182_440_000L) / 86_400_000.0
            val p = (days / 29.530588853) % 1.0
            return (if (p < 0) p + 1 else p).toFloat()
        }

        fun mix(a: Int, b: Int, t: Float): Int {
            val u = t.coerceIn(0f, 1f)
            fun ch(shift: Int) = ((a shr shift and 0xFF) * (1 - u) + (b shr shift and 0xFF) * u).toInt()
            return Color.argb(ch(24), ch(16), ch(8), ch(0))
        }

        fun withAlpha(color: Int, alpha: Float) = (color and 0x00FFFFFF) or ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24)
    }
}
