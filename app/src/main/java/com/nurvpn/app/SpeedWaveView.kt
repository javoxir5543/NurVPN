package com.nurvpn.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.Choreographer
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * NurVPN — SpeedWaveView v17 "Cosmic Aurora" FINAL
 *
 * v16 → v17:
 *  - Markaz kichraytirildi (0.72 → 0.55, 0.42 → 0.30) — chuqurlik
 *  - Shield aura qo'shildi — tugma ham pulsatsiya qiladi
 *  - Zarrachalar VPN off da yorqinroq (0.30 → 0.42)
 *  - Butun halqa tizimi sekin aylanadi (dynamizm)
 *  - Yulduzlar zichligi chetlarga ko'proq
 *  - Bir xil markazda kichik yorqin "yadro"
 */
class SpeedWaveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "SpeedWave"
        private const val RING_COUNT = 3
        private const val PARTICLES_PER_RING = 8
        private const val TRAIL_LENGTH = 12
        private const val STAR_COUNT = 70

        private const val MAX_SPEED_DEFAULT = 100L * 1024L
        private const val AUTO_DISCONNECT_FRAMES = 600  // 10 sekund @ 60fps
        private const val SPEED_THRESHOLD = 200L

        private const val COLOR_BG_CENTER = 0xFF14281A.toInt()
        private const val COLOR_BG_EDGE   = 0xFF04090A.toInt()

        private const val COLOR_LIME        = 0xFFC4F82A.toInt()
        private const val COLOR_LIME_BRIGHT = 0xFFE8FF80.toInt()
        private const val COLOR_TEAL        = 0xFF5FEFE0.toInt()
        private const val COLOR_TEAL_BRIGHT = 0xFF80FFFF.toInt()
        private const val COLOR_WHITE       = 0xFFFFFFFF.toInt()
    }

    private val density = resources.displayMetrics.density
    private val TWO_PI: Float = (PI * 2.0).toFloat()
    private fun dp(v: Float): Float = v * density

    // ═══ State ═══
    private var connected = false
    private var currentSpeed = 0f
    private var targetSpeed = 0f
    private var maxSpeed = MAX_SPEED_DEFAULT.toFloat()
    private var animationTime = 0f
    private var lastFrameNanos = 0L
    private var zeroSpeedFrames = 0
    private var running = false
    private var permanentlyStopped = false

    // ═══ Geometry ═══
    private var cx = 0f; private var cy = 0f; private var radius = 0f
    private val clipPath = Path()

    // ═══ Fon ═══
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var bgGradient: RadialGradient? = null

    // ═══ Markaz glow (3 qatlam) ═══
    private val outerGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private var outerGlowGradient: RadialGradient? = null

    private val innerGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private var innerGlowGradient: RadialGradient? = null

    private val coreGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
        color = COLOR_WHITE
    }

    // ═══ Shield aura ═══
    private val shieldAuraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
        color = COLOR_LIME_BRIGHT
        strokeCap = Paint.Cap.ROUND
    }

    // ═══ Halqalar ═══
    private val ringLimePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
        color = COLOR_LIME
    }
    private val ringTealPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
        color = COLOR_TEAL
    }

    // ═══ Sweep ═══
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private var sweepGradient: SweepGradient? = null

    // ═══ Zarrachalar ═══
    private val particleHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
        color = COLOR_LIME
    }
    private val particleMidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
        color = COLOR_LIME_BRIGHT
    }
    private val particleTealPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
        color = COLOR_TEAL_BRIGHT
    }
    private val particleCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
        color = COLOR_WHITE
    }

    // ═══ Yulduzlar ═══
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_LIME_BRIGHT
    }
    private val starTealPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_TEAL_BRIGHT
    }

    // ═══ Inner pulse ═══
    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
        color = COLOR_LIME_BRIGHT
        strokeCap = Paint.Cap.ROUND
    }

    // ═══ Data ═══
    private val starX = FloatArray(STAR_COUNT)
    private val starY = FloatArray(STAR_COUNT)
    private val starSize = FloatArray(STAR_COUNT)
    private val starPhase = FloatArray(STAR_COUNT)
    private val starIsTeal = BooleanArray(STAR_COUNT)

    private val ringPhase = FloatArray(RING_COUNT)
    private val ringSpeed = floatArrayOf(0.30f, -0.20f, 0.14f)
    private val ringRadiusFactor = floatArrayOf(0.42f, 0.62f, 0.82f)

    private var sweepAngle = 0f
    private var pulsePhase = 0f
    private var ringRotation = 0f     // v17: butun tizim aylanishi

    // ═══ Choreographer ═══
    private val choreographer: Choreographer by lazy { Choreographer.getInstance() }
    private var frameCount = 0L
    private var lastLogTimeMs = 0L

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running || permanentlyStopped) return
            val dt = if (lastFrameNanos == 0L) 1f / 60f
                     else ((frameTimeNanos - lastFrameNanos) / 1_000_000_000f)
                         .coerceIn(0.008f, 0.05f)
            lastFrameNanos = frameTimeNanos
            updateAnimation(dt)
            invalidate()
            frameCount++
            val now = SystemClock.uptimeMillis()
            if (now - lastLogTimeMs >= 1000L) {
                lastLogTimeMs = now
                val ratio = (currentSpeed / maxSpeed).coerceIn(0f, 1f)
                Log.d(TAG, "frame=$frameCount speed=${"%.0f".format(currentSpeed)} " +
                    "ratio=${"%.2f".format(ratio)} connected=$connected")
            }
            if (running && !permanentlyStopped) choreographer.postFrameCallback(this)
        }
    }

    // ═══ Lifecycle ═══
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Log.d(TAG, "onAttached connected=$connected")
        permanentlyStopped = false
        startAnimation()
    }
    override fun onDetachedFromWindow() {
        stopAnimation()
        super.onDetachedFromWindow()
    }
    private fun startAnimation() {
        if (running || permanentlyStopped) return
        running = true; lastFrameNanos = 0L
        choreographer.postFrameCallback(frameCallback)
    }
    private fun stopAnimation() {
        if (!running) return
        running = false; lastFrameNanos = 0L
        choreographer.removeFrameCallback(frameCallback)
    }
    fun stop() {
        permanentlyStopped = true
        stopAnimation()
        currentSpeed = 0f; targetSpeed = 0f; animationTime = 0f
        invalidate()
    }

    // ═══ API ═══
    fun setConnected(connected: Boolean) {
        if (this.connected != connected) Log.d(TAG, "setConnected($connected)")
        this.connected = connected
        if (!connected) { targetSpeed = 0f; zeroSpeedFrames = 0 }
        if (isAttachedToWindow && !permanentlyStopped) startAnimation()
        invalidate()
    }
    fun setSpeed(downloadBytesPerSec: Long, uploadBytesPerSec: Long) {
        val total = (downloadBytesPerSec + uploadBytesPerSec).coerceAtLeast(0L)
        targetSpeed = total.toFloat()
        if (total > SPEED_THRESHOLD) {
            zeroSpeedFrames = 0
            if (!connected) {
                connected = true
                Log.d(TAG, "setSpeed auto-CONNECT total=$total")
            }
        }
        if (isAttachedToWindow && !permanentlyStopped) startAnimation()
    }
    fun setMaxSpeed(bytesPerSec: Long) {
        maxSpeed = bytesPerSec.coerceAtLeast(1L).toFloat()
    }

    // ═══ Layout ═══
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cx = w / 2f; cy = h / 2f
        radius = min(w, h) / 2f - dp(4f)
        clipPath.reset()
        clipPath.addCircle(cx, cy, radius, Path.Direction.CW)
        createGradients()
        initStars()
        Log.d(TAG, "onSizeChanged radius=$radius")
    }

    private fun createGradients() {
        bgGradient = RadialGradient(
            cx, cy, radius,
            intArrayOf(COLOR_BG_CENTER, COLOR_BG_EDGE),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        bgPaint.shader = bgGradient

        // v17: Markaz kichraytirildi + alpha pasaytirildi
        // Tashqi yumshoq lime (0.72 → 0.55)
        outerGlowGradient = RadialGradient(
            cx, cy, radius * 0.55f,
            intArrayOf(
                Color.argb(95, 196, 248, 42),
                Color.argb(50, 196, 248, 42),
                Color.argb(18, 196, 248, 42),
                Color.argb(0, 196, 248, 42)
            ),
            floatArrayOf(0f, 0.42f, 0.75f, 1f),
            Shader.TileMode.CLAMP
        )
        outerGlowPaint.shader = outerGlowGradient

        // Ichki yorqin (0.42 → 0.30)
        innerGlowGradient = RadialGradient(
            cx, cy, radius * 0.30f,
            intArrayOf(
                Color.argb(180, 232, 255, 128),
                Color.argb(115, 196, 248, 42),
                Color.argb(30, 196, 248, 42),
                Color.argb(0, 196, 248, 42)
            ),
            floatArrayOf(0f, 0.40f, 0.75f, 1f),
            Shader.TileMode.CLAMP
        )
        innerGlowPaint.shader = innerGlowGradient

        // Sweep gradient
        sweepGradient = SweepGradient(
            cx, cy,
            intArrayOf(
                Color.TRANSPARENT,
                Color.argb(30, 232, 255, 128),
                Color.argb(200, 232, 255, 128),
                Color.argb(255, 255, 255, 255),
                Color.argb(200, 196, 248, 42),
                Color.argb(30, 196, 248, 42),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.60f, 0.72f, 0.78f, 0.85f, 0.95f, 1f)
        )
        sweepPaint.shader = sweepGradient
    }

    private fun initStars() {
        var seed = 0xABCD1234
        fun nextF(): Float {
            seed = seed * 1_103_515_245 + 12_345
            return ((seed ushr 8) and 0xFFFFFF) / 0xFFFFFF.toFloat()
        }
        for (i in 0 until STAR_COUNT) {
            val angle = nextF() * TWO_PI
            // v17: chetlarga ko'proq (sqrt distribution)
            val rNorm = kotlin.math.sqrt(nextF())  // 0..1, ko'proq chetlarga
            val r = dp(90f) + rNorm * (radius - dp(95f))
            starX[i] = cx + cos(angle) * r
            starY[i] = cy + sin(angle) * r
            starSize[i] = dp(0.7f) + nextF() * dp(1.6f)
            starPhase[i] = nextF() * TWO_PI
            starIsTeal[i] = nextF() > 0.65f
        }
    }

    // ═══ Animation ═══
    private fun updateAnimation(dt: Float) {
        currentSpeed += (targetSpeed - currentSpeed) * (1f - exp(-dt * 5.5f))

        if (connected && currentSpeed < SPEED_THRESHOLD) {
            zeroSpeedFrames++
            if (zeroSpeedFrames > AUTO_DISCONNECT_FRAMES) {
                connected = false
                zeroSpeedFrames = 0
                Log.d(TAG, "auto-DISCONNECT (10s trafik yo'q)")
            }
        } else if (currentSpeed >= SPEED_THRESHOLD) {
            zeroSpeedFrames = 0
        }

        val speedRatio = (currentSpeed / maxSpeed).coerceIn(0f, 1f)
        val motion = if (connected) 0.40f + speedRatio * 1.80f else 0.12f
        animationTime += dt * motion
        if (animationTime > 100_000f) animationTime -= 100_000f

        for (i in 0 until RING_COUNT) {
            val ringMult = if (connected) (0.6f + speedRatio * 1.6f) else 0.30f
            ringPhase[i] += dt * ringSpeed[i] * ringMult
        }

        // v17: butun tizim sekin aylanadi (0.08 rad/s)
        val rotMult = if (connected) (0.7f + speedRatio * 0.5f) else 0.3f
        ringRotation = (ringRotation + dt * 0.08f * rotMult) % TWO_PI

        val sweepMult = if (connected) (0.55f + speedRatio * 0.65f) else 0.20f
        sweepAngle = (sweepAngle + dt * 1.4f * sweepMult) % TWO_PI

        pulsePhase += dt * (if (connected) 0.7f + speedRatio * 0.5f else 0.35f)
        if (pulsePhase > 1f) pulsePhase -= 1f
    }

    // ═══ Drawing ═══
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (radius < 10f) return

        val sc = canvas.save()
        canvas.clipPath(clipPath)

        // 1. Fon
        canvas.drawCircle(cx, cy, radius, bgPaint)

        // 2. Markaz glow (3 qatlam)
        val pulse = 1f + 0.08f * sin(animationTime * 1.8f)
        canvas.save()
        canvas.scale(pulse, pulse, cx, cy)
        canvas.drawCircle(cx, cy, radius * 0.55f, outerGlowPaint)
        canvas.drawCircle(cx, cy, radius * 0.30f, innerGlowPaint)
        canvas.restore()

        // 3. Yadro — kichik yorqin nuqta
        val speedRatio = (currentSpeed / maxSpeed).coerceIn(0f, 1f)
        coreGlowPaint.alpha = if (connected) (180 + speedRatio * 60).toInt() else 90
        canvas.drawCircle(cx, cy, dp(3f + speedRatio * 2f), coreGlowPaint)

        // 4. Yulduzlar
        drawStars(canvas)

        // 5. Shield aura (pulsatsiya qiluvchi halqa)
        drawShieldAura(canvas, speedRatio)

        // 6. Inner pulse
        drawInnerPulse(canvas, speedRatio)

        // 7. Butun tizim aylanadi
        canvas.save()
        canvas.rotate(ringRotation * 180f / PI.toFloat(), cx, cy)

        // 8. Orbit tizim
        drawOrbitSystem(canvas, speedRatio)

        canvas.restore()

        // 9. Sweep (aylanadi, lekin tizim rotatsiyasidan tashqarida)
        drawSweep(canvas, speedRatio)

        canvas.restoreToCount(sc)
    }

    private fun drawStars(canvas: Canvas) {
        val ratio = (currentSpeed / maxSpeed).coerceIn(0f, 1f)
        val brightness = if (connected) 0.60f + ratio * 0.40f else 0.42f  // v17: 0.30 → 0.42
        for (i in 0 until STAR_COUNT) {
            val twinkle = 0.5f + 0.5f * sin(animationTime * 2.2f + starPhase[i])
            val alpha = ((80f + 175f * twinkle) * brightness).toInt().coerceIn(0, 255)
            val p = if (starIsTeal[i]) starTealPaint else starPaint
            p.alpha = alpha
            canvas.drawCircle(starX[i], starY[i], starSize[i], p)
        }
    }

    /**
     * v17: Shield aura — tugma atrofida pulsatsiya qiluvchi halqa.
     * Radius ~ 0.38 — tugma chegarasida.
     */
    private fun drawShieldAura(canvas: Canvas, speedRatio: Float) {
        val pulse = 0.5f + 0.5f * sin(animationTime * 2.8f)
        val auraR = radius * (0.36f + 0.04f * pulse)
        val intensity = if (connected) 0.75f + speedRatio * 0.25f else 0.30f
        val alpha = ((120f + 100f * pulse) * intensity).toInt().coerceIn(0, 255)

        shieldAuraPaint.strokeWidth = dp(1.5f + pulse * 0.8f)
        shieldAuraPaint.alpha = alpha
        canvas.drawCircle(cx, cy, auraR, shieldAuraPaint)
    }

    private fun drawInnerPulse(canvas: Canvas, speedRatio: Float) {
        val maxR = radius * 0.55f
        val r = dp(60f) + pulsePhase * (maxR - dp(60f))
        val alpha = ((1f - pulsePhase) * 130f *
            (if (connected) 0.75f + speedRatio * 0.25f else 0.30f)).toInt().coerceIn(0, 255)
        pulsePaint.strokeWidth = dp(1.6f)
        pulsePaint.alpha = alpha
        canvas.drawCircle(cx, cy, r, pulsePaint)
    }

    private fun drawOrbitSystem(canvas: Canvas, speedRatio: Float) {
        val intensity = if (connected) 0.78f + speedRatio * 0.22f else 0.38f
        val speedBoost = speedRatio * 0.4f

        for (ring in 0 until RING_COUNT) {
            val ringR = radius * ringRadiusFactor[ring]
            val useTeal = ring == 1
            val paint = if (useTeal) ringTealPaint else ringLimePaint
            paint.strokeWidth = dp(if (useTeal) 2.8f else 2.2f)
            val baseAlpha = if (useTeal) 80f else 70f
            paint.alpha = ((baseAlpha + speedBoost * 100f) * intensity)
                .toInt().coerceIn(0, 255)
            canvas.drawCircle(cx, cy, ringR, paint)

            for (p in 0 until PARTICLES_PER_RING) {
                val baseAngle = (p.toFloat() / PARTICLES_PER_RING) * TWO_PI
                val angle = baseAngle + ringPhase[ring]
                val wobble = sin(animationTime * (1.2f + ring * 0.3f) + baseAngle) *
                    dp(2f + speedRatio * 4f)
                val effR = ringR + wobble
                val px = cx + cos(angle) * effR
                val py = cy + sin(angle) * effR
                val pSize = dp(1.8f + ring * 0.4f) * (0.9f + speedRatio * 0.7f)

                val isTeal = (p + ring) % 3 == 1
                val midPaint = if (isTeal) particleTealPaint else particleMidPaint

                // Kometa dumi
                for (t in 1..TRAIL_LENGTH) {
                    val trailAngle = angle - t * 0.038f * (1f + speedRatio * 1.8f)
                    val tx = cx + cos(trailAngle) * effR
                    val ty = cy + sin(trailAngle) * effR
                    val fade = 1f - t.toFloat() / TRAIL_LENGTH
                    val trailAlpha = (200f * fade * fade * intensity).toInt().coerceIn(0, 255)
                    particleHaloPaint.alpha = (trailAlpha * 0.35f).toInt().coerceIn(0, 255)
                    canvas.drawCircle(tx, ty, pSize * 0.9f, particleHaloPaint)
                }

                // 4 qatlamli glow
                particleHaloPaint.alpha = (90f * intensity).toInt().coerceIn(0, 255)
                canvas.drawCircle(px, py, pSize * 3.5f, particleHaloPaint)
                particleHaloPaint.alpha = (160f * intensity).toInt().coerceIn(0, 255)
                canvas.drawCircle(px, py, pSize * 2.0f, particleHaloPaint)
                midPaint.alpha = (230f * intensity).toInt().coerceIn(0, 255)
                canvas.drawCircle(px, py, pSize * 1.25f, midPaint)
                particleCorePaint.alpha = 255
                canvas.drawCircle(px, py, pSize * 0.65f, particleCorePaint)
            }
        }
    }

    private fun drawSweep(canvas: Canvas, ratio: Float) {
        val outerR = radius * 0.90f
        canvas.save()
        canvas.rotate(sweepAngle * 180f / PI.toFloat(), cx, cy)
        sweepPaint.strokeWidth = dp(3.5f + ratio * 2f)
        sweepPaint.alpha = (140 + 115 * ratio).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, outerR, sweepPaint)
        canvas.restore()
    }
}
