package com.karaokebox.ui.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import com.karaokebox.ServiceLocator
import com.karaokebox.ui.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Màn hình chờ sau khi có điện thoại quét QR: sân khấu có rèm nhung hé mở, đèn spotlight quét chậm,
 * chữ 3D "VUI LÒNG CHỌN BÀI HÁT" xoay nhẹ + nhấp nhô, kèm nút bánh xe răng cưa (cài đặt) ở góc dưới bên phải.
 * Màu sắc và hạt bay đổi theo dịp (Tết, Giáng sinh, Sinh nhật) - xem [StageTheme].
 * Khi có điện thoại mới vào phòng, hiện huy hiệu "Khách vừa vào phòng hát" ở phía trên.
 */
class WaitingView(ctx: Context, onSettings: () -> Unit) : FrameLayout(ctx) {
    private val stage = StageView(ctx)
    private val gear = GearButton(ctx)
    private val welcome = TextView(ctx)
    private val welcomeBg = GradientDrawable()
    private var welcomeGen = 0
    private val hideWelcome = Runnable {
        val g = ++welcomeGen
        welcome.animate().alpha(0f).translationY(-dp(20).toFloat()).setDuration(400).start()
        postDelayed({ if (g == welcomeGen) welcome.visibility = GONE }, 420)
    }

    init {
        addView(stage, LayoutParams(-1, -1))

        welcomeBg.cornerRadius = dp(32).toFloat()
        welcomeBg.orientation = GradientDrawable.Orientation.LEFT_RIGHT
        welcome.background = welcomeBg
        welcome.typeface = Typeface.DEFAULT_BOLD
        welcome.textSize = 26f
        welcome.letterSpacing = 0.03f
        welcome.setTextColor(0xFF1B0E35.toInt())
        welcome.gravity = Gravity.CENTER
        welcome.setSingleLine()
        welcome.ellipsize = android.text.TextUtils.TruncateAt.END
        welcome.setPadding(dp(30), dp(12), dp(30), dp(12))
        welcome.elevation = dp(8).toFloat()
        welcome.visibility = GONE
        addView(welcome, LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(60) })

        addView(gear, LayoutParams(dp(64), dp(64), Gravity.BOTTOM or Gravity.RIGHT).apply {
            rightMargin = dp(32); bottomMargin = dp(28)
        })
        gear.setOnClickListener { onSettings() }
        visibility = GONE
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (w <= 0 || h <= 0) return
        // Huy hiệu nằm ngay dưới diềm rèm phía trên (diềm cao ~9% chiều cao màn hình)
        post {
            (welcome.layoutParams as? LayoutParams)?.let {
                it.topMargin = (h * 0.115f).toInt()
                welcome.layoutParams = it
            }
            welcome.maxWidth = (w * 0.7f).toInt()
        }
    }

    /** Đọc lại giao diện đã chọn trong Cài đặt (hoặc theo ngày nếu để Tự động). */
    fun refreshTheme() {
        stage.applyTheme(StageTheme.resolve(ServiceLocator.settings.stageThemeMode))
    }

    fun show() {
        if (visibility == VISIBLE) return
        refreshTheme()
        stage.restartCurtains()      // mỗi lần về màn hình chờ, rèm lại kéo ra
        visibility = VISIBLE
        if (gear.isFocusable) gear.requestFocus()   // hàng chờ trống: bấm OK trên remote để mở cài đặt
    }

    /**
     * Hàng chờ còn bài (vd: khôi phục sau khi mở máy) → nhắc bấm OK để hát, và nhả focus khỏi bánh răng
     * để phím OK trên remote chạy hàng chờ thay vì mở Cài đặt (Cài đặt vẫn mở được bằng cảm ứng / nút Vàng).
     */
    fun setQueueCount(n: Int) {
        stage.hint = if (n > 0) "Có $n bài đang chờ – nhấn OK trên remote để bắt đầu hát"
        else "Dùng điện thoại để thêm bài hát vào danh sách"
        gear.setRemoteFocusable(n == 0)
        if (n == 0 && visibility == VISIBLE) gear.requestFocus()
        stage.invalidate()
    }

    fun hide() {
        visibility = GONE
        removeCallbacks(hideWelcome)
        welcomeGen++
        welcome.animate().cancel()
        welcome.visibility = GONE
    }

    /** Huy hiệu "<tên> vừa vào phòng hát" trượt xuống từ phía trên rồi tự ẩn sau ~4 giây. */
    fun showWelcome(name: String) {
        val th = stage.theme
        val label = name.trim().ifEmpty { "Khách" }.take(30)
        welcomeBg.colors = intArrayOf(th.accent, blend(th.accent, Color.WHITE, 0.45f))
        welcome.text = "♪  $label vừa vào phòng hát  ♪"
        removeCallbacks(hideWelcome)
        welcomeGen++
        welcome.animate().cancel()
        welcome.visibility = VISIBLE
        welcome.alpha = 0f
        welcome.translationY = -dp(40).toFloat()
        welcome.scaleX = 0.85f; welcome.scaleY = 0.85f
        welcome.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
            .setDuration(450).setInterpolator(OvershootInterpolator(1.4f)).start()
        postDelayed(hideWelcome, 4200)
    }

    private fun blend(a: Int, b: Int, f: Float): Int {
        val r = (Color.red(a) * (1 - f) + Color.red(b) * f).toInt()
        val g = (Color.green(a) * (1 - f) + Color.green(b) * f).toInt()
        val bl = (Color.blue(a) * (1 - f) + Color.blue(b) * f).toInt()
        return Color.rgb(r, g, bl)
    }

    // ---------------------------------------------------------------------------------------------
    /**
     * Nền sân khấu + chữ 3D + rèm + đèn + hạt bay. Chữ được vẽ (đùn khối) vào bitmap MỘT lần, mỗi khung hình
     * chỉ biến đổi bằng Camera. Mọi shader dựng sẵn khi đổi kích thước / đổi giao diện (không tạo mới mỗi khung hình).
     * Giới hạn ~30 khung hình/giây để nhẹ cho Mali-T720 của Q10 Pro; chuyển động đều chậm nên không bị giật mắt.
     */
    private class StageView(ctx: Context) : View(ctx) {
        var theme: StageTheme = StageTheme.DEFAULT
            private set

        private val bgPaint = Paint()
        private val bokeh = Paint(Paint.ANTI_ALIAS_FLAG)
        private val star = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val greetStar = Paint(Paint.ANTI_ALIAS_FLAG)
        private val beam = Paint(Paint.ANTI_ALIAS_FLAG)
        private val pool = Paint(Paint.ANTI_ALIAS_FLAG)
        private val lamp = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
        private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
        private val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFD9CCF0.toInt(); textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        private val greet = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            letterSpacing = 0.12f
        }
        private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val curtainPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val valancePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val trimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val cordPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        private val flakeLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }

        private var textBmp: Bitmap? = null
        private var textPad = 0f
        private val camera = Camera()
        private val m = Matrix()
        private val shaderM = Matrix()
        private val rectF = RectF()
        private val path = Path()
        private val curtainPath = Path()
        private val edgePath = Path()
        private val valancePath = Path()
        private val valanceTrim = Path()
        var hint = "Dùng điện thoại để thêm bài hát vào danh sách"
        private var t = 0f
        private var curtainT = 0f
        private var last = 0L
        private var lastDraw = 0L

        // ----- shader dựng sẵn -----
        private var orbShaders: Array<RadialGradient?> = arrayOfNulls(0)
        private var beamShader: LinearGradient? = null
        private var poolShader: RadialGradient? = null
        private var lampShader: RadialGradient? = null
        private var foldShader: LinearGradient? = null
        private var foldSpan = 0f
        private var shadeShader: LinearGradient? = null
        private var valanceShader: LinearGradient? = null
        private var valBase = 0f      // y của đường diềm rèm (chỗ nối giữa các vòng cung)
        private var valDip = 0f       // độ sâu vòng cung
        private var openInner = 0f    // vị trí mép trong của rèm khi mở
        private var closedInner = 0f  // vị trí mép trong khi khép (hai rèm gặp nhau ở giữa)

        // hạt bokeh: x, y (0..1), bán kính (dp), tốc độ, pha, chỉ số màu trong theme.orbs
        private class Orb(val x: Float, val y: Float, val r: Float, val speed: Float, val phase: Float, val ci: Int)
        private val orbs = listOf(
            Orb(.08f, .20f, 70f, .30f, 0.0f, 0), Orb(.22f, .75f, 50f, .45f, 1.3f, 1),
            Orb(.35f, .12f, 38f, .55f, 2.1f, 2), Orb(.50f, .88f, 80f, .25f, 0.7f, 0),
            Orb(.64f, .18f, 56f, .40f, 3.0f, 3), Orb(.78f, .70f, 64f, .35f, 4.2f, 1),
            Orb(.90f, .25f, 46f, .50f, 5.1f, 2), Orb(.14f, .48f, 34f, .60f, 2.6f, 3),
            Orb(.88f, .52f, 30f, .65f, 1.1f, 0), Orb(.42f, .60f, 26f, .70f, 3.7f, 2)
        )
        private class Spark(val x: Float, val y: Float, val size: Float, val speed: Float, val phase: Float)
        private val sparks = List(22) { i ->
            val a = (i * 0.6180339f) % 1f
            val b = (i * 0.7548776f + 0.3f) % 1f
            Spark(a, b, 4f + (i % 5) * 2f, 1.2f + (i % 4) * 0.5f, i * 0.9f)
        }

        private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener {
                val now = System.nanoTime()
                if (last != 0L) {
                    val dt = ((now - last) / 1_000_000_000f).coerceAtMost(0.1f)
                    t += dt
                    curtainT += dt
                }
                last = now
                if (now - lastDraw >= FRAME_NS) { lastDraw = now; invalidate() }   // ~30 khung hình/giây
            }
        }

        override fun onAttachedToWindow() { super.onAttachedToWindow(); last = 0L; if (isShown) anim.start() }
        override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }
        override fun onVisibilityChanged(changedView: View, visibility: Int) {
            super.onVisibilityChanged(changedView, visibility)
            if (isAttachedToWindow) { if (isShown) { last = 0L; anim.start() } else anim.cancel() }
        }

        fun restartCurtains() { curtainT = 0f }

        fun applyTheme(newTheme: StageTheme) {
            if (newTheme == theme) return
            theme = newTheme
            rebuild()
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            if (w <= 0 || h <= 0) return
            rebuild()
        }

        /** Dựng lại mọi thứ phụ thuộc kích thước màn hình hoặc giao diện: shader, đường diềm rèm, bitmap chữ 3D. */
        private fun rebuild() {
            val w = width; val h = height
            if (w <= 0 || h <= 0) return
            val wf = w.toFloat(); val hf = h.toFloat()
            val th = theme

            bgPaint.shader = LinearGradient(0f, 0f, 0f, hf, th.bg,
                floatArrayOf(0f, .45f, .75f, 1f), Shader.TileMode.CLAMP)

            // bokeh: gradient tròn đặt ở gốc tọa độ, khi vẽ chỉ tịnh tiến canvas (không tạo shader mới mỗi khung hình)
            orbShaders = Array(orbs.size) { i ->
                val o = orbs[i]
                val col = th.orbs[o.ci % th.orbs.size]
                RadialGradient(0f, 0f, dpf(o.r),
                    intArrayOf(withAlpha(col, 0x55), withAlpha(col, 0x00)), null, Shader.TileMode.CLAMP)
            }

            // diềm rèm phía trên: các vòng cung (scallop)
            val vh = hf * 0.075f
            valBase = vh * 0.72f
            valDip = vh * 0.50f
            val n = 9
            valancePath.reset(); valanceTrim.reset()
            valancePath.moveTo(0f, 0f); valancePath.lineTo(wf, 0f); valancePath.lineTo(wf, valBase)
            valanceTrim.moveTo(wf, valBase)
            for (i in n downTo 1) {
                val xr = wf * i / n; val xl = wf * (i - 1) / n; val xm = (xl + xr) / 2f
                valancePath.quadTo(xm, valBase + valDip * 2f, xl, valBase)
                valanceTrim.quadTo(xm, valBase + valDip * 2f, xl, valBase)
            }
            valancePath.close()
            valanceShader = foldGradient(wf, dpf(64f), th.curtainLight, th.curtainDark)
            valancePaint.shader = valanceShader
            trimPaint.color = th.trim
            trimPaint.strokeWidth = dpf(3f)

            // rèm hai bên: nếp gấp đều nhau theo px (không đổi theo độ rộng rèm)
            openInner = wf * 0.085f
            closedInner = wf / 2f + dpf(10f)
            foldSpan = closedInner * 1.7f
            foldShader = foldGradient(foldSpan, dpf(46f), th.curtainLight, th.curtainDark)
            curtainPaint.shader = foldShader
            shadeShader = LinearGradient(0f, 0f, 0f, hf,
                intArrayOf(0x00000000, 0x22000000, 0x88000000.toInt()), floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
            shadePaint.shader = shadeShader
            cordPaint.color = th.trim
            cordPaint.strokeWidth = dpf(6f)
            dot.color = th.trim

            // đèn spotlight: chùm sáng dọc, vệt sáng trên sàn, bóng đèn
            val bc = th.beam
            val floorY = hf * 0.84f
            beamShader = LinearGradient(0f, 0f, 0f, floorY,
                intArrayOf(withAlpha(bc, 0x4A), withAlpha(bc, 0x0C)), null, Shader.TileMode.CLAMP)
            poolShader = RadialGradient(0f, 0f, wf * 0.13f,
                intArrayOf(withAlpha(bc, 0x58), withAlpha(bc, 0x00)), null, Shader.TileMode.CLAMP)
            lampShader = RadialGradient(0f, 0f, dpf(26f),
                intArrayOf(withAlpha(bc, 0xB0), withAlpha(bc, 0x00)), null, Shader.TileMode.CLAMP)
            beam.shader = beamShader
            pool.shader = poolShader
            lamp.shader = lampShader

            greetStar.color = th.accent

            textBmp?.recycle()
            textBmp = renderText(w, h)
        }

        /** Dải màu nếp gấp: tối - sáng xen kẽ, chu kỳ [period] px, trải dài [span] px. */
        private fun foldGradient(span: Float, period: Float, light: Int, dark: Int): LinearGradient {
            val folds = (span / period).toInt().coerceAtLeast(2)
            val cnt = folds * 2 + 1
            val colors = IntArray(cnt) { i -> if (i % 2 == 0) dark else light }
            val pos = FloatArray(cnt) { i -> i / (cnt - 1).toFloat() }
            return LinearGradient(0f, 0f, span, 0f, colors, pos, Shader.TileMode.CLAMP)
        }

        /** Vẽ chữ khối 3D: lớp đùn nhiều tầng + viền + mặt chữ chuyển màu + vệt sáng + quầng sáng. */
        private fun renderText(w: Int, h: Int): Bitmap {
            val th = theme
            val line1 = "VUI LÒNG"
            val line2 = "CHỌN BÀI HÁT"
            val face = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
                letterSpacing = 0.04f
            }
            // cỡ chữ: dòng dài nhất chiếm ~66% bề rộng màn hình
            var size = h * 0.2f
            face.textSize = size
            val maxW = w * 0.66f
            val wide = maxOf(face.measureText(line1), face.measureText(line2))
            size *= maxW / wide
            face.textSize = size

            val depth = (size * 0.16f).toInt().coerceAtLeast(10)
            val pad = (size * 0.5f).toInt()
            textPad = pad.toFloat()
            val lineH = size * 1.08f
            val bw = (maxW + pad * 2 + depth).toInt()
            val bh = (lineH * 2 + pad * 2 + depth).toInt()
            val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)

            val cx = (bw - depth) / 2f
            val y1 = pad + size * 0.85f
            val y2 = y1 + lineH
            val lines = listOf(line1 to y1, line2 to y2)

            // 1) quầng sáng neon phía sau
            val glow = Paint(face).apply {
                color = th.glow; maskFilter = BlurMaskFilter(size * 0.22f, BlurMaskFilter.Blur.NORMAL)
            }
            for ((s, y) in lines) c.drawText(s, cx + depth / 2f, y + depth / 2f, glow)

            // 2) khối đùn: từ tầng sâu nhất (tối) tới sát mặt (sáng hơn)
            val ext = Paint(face)
            for (i in depth downTo 1) {
                val f = i / depth.toFloat()
                ext.color = blend(th.extrudeNear, th.extrudeFar, f)
                for ((s, y) in lines) c.drawText(s, cx + i * 0.9f, y + i, ext)
            }

            // 3) viền ngoài tối để chữ tách khỏi nền
            val stroke = Paint(face).apply {
                style = Paint.Style.STROKE; strokeWidth = size * 0.05f; strokeJoin = Paint.Join.ROUND
                color = th.outline
            }
            for ((s, y) in lines) c.drawText(s, cx, y, stroke)

            // 4) mặt chữ: chuyển màu theo giao diện
            for ((s, y) in lines) {
                face.shader = LinearGradient(0f, y - size * 0.8f, 0f, y + size * 0.1f,
                    th.face, floatArrayOf(0f, .35f, .7f, 1f), Shader.TileMode.CLAMP)
                c.drawText(s, cx, y, face)
            }

            // 5) vệt sáng bóng loáng nửa trên mặt chữ
            val shine = Paint(face).apply {
                shader = LinearGradient(0f, 0f, 0f, size * 0.5f,
                    intArrayOf(0xB3FFFFFF.toInt(), 0x00FFFFFF), null, Shader.TileMode.CLAMP)
            }
            for ((s, y) in lines) {
                c.save()
                c.clipRect(0f, y - size * 0.82f, bw.toFloat(), y - size * 0.34f)
                shine.shader = LinearGradient(0f, y - size * 0.82f, 0f, y - size * 0.34f,
                    intArrayOf(0xB3FFFFFF.toInt(), 0x10FFFFFF), null, Shader.TileMode.CLAMP)
                c.drawText(s, cx, y, shine)
                c.restore()
            }
            return bmp
        }

        private fun blend(a: Int, b: Int, f: Float): Int {
            val r = (Color.red(a) * (1 - f) + Color.red(b) * f).toInt()
            val g = (Color.green(a) * (1 - f) + Color.green(b) * f).toInt()
            val bl = (Color.blue(a) * (1 - f) + Color.blue(b) * f).toInt()
            return Color.rgb(r, g, bl)
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val th = theme
            canvas.drawRect(0f, 0f, w, h, bgPaint)

            drawSpotlights(canvas, w, h)

            // bokeh trôi nổi
            for (i in orbs.indices) {
                val o = orbs[i]
                val sh = orbShaders.getOrNull(i) ?: continue
                val ox = o.x * w + sin(t * o.speed + o.phase) * dpf(24f)
                val oy = o.y * h + cos(t * o.speed * 0.8f + o.phase) * dpf(18f)
                bokeh.shader = sh
                canvas.save(); canvas.translate(ox, oy); canvas.drawCircle(0f, 0f, dpf(o.r), bokeh); canvas.restore()
            }

            // chữ 3D xoay nhẹ quanh trục Y/X + nhấp nhô
            val bmp = textBmp
            if (bmp != null) {
                val cxp = w / 2f
                val cyp = h * 0.44f
                val bob = sin(t * 1.4f) * dpf(8f)

                // bóng đổ mềm dưới chữ (co giãn ngược chiều nhấp nhô)
                val sw = bmp.width * (0.52f - bob / dpf(8f) * 0.02f)
                val sy = cyp + bmp.height * 0.50f
                shadow.shader = RadialGradient(cxp, sy, sw, intArrayOf(withAlpha(th.glow, 0x66), withAlpha(th.glow, 0x00)), null, Shader.TileMode.CLAMP)
                canvas.save(); canvas.scale(1f, 0.12f, cxp, sy); canvas.drawCircle(cxp, sy, sw, shadow); canvas.restore()

                camera.save()
                camera.setLocation(0f, 0f, -bmp.width * 3.5f / 72f)   // xa camera → phối cảnh dịu, chữ không méo
                camera.rotateY(sin(t * 0.9f) * 16f)
                camera.rotateX(cos(t * 0.6f) * 5f)
                camera.getMatrix(m)
                camera.restore()
                m.preTranslate(-bmp.width / 2f, -bmp.height / 2f)
                m.postTranslate(cxp, cyp + bob)
                canvas.drawBitmap(bmp, m, bmpPaint)

                // lời chúc theo dịp, ngay phía trên chữ 3D
                th.greeting?.let { g ->
                    val gy = cyp + bob - bmp.height / 2f + textPad * 0.62f
                    greet.textSize = dpf(30f)
                    greet.color = th.accent
                    greet.alpha = (215 + 40 * sin(t * 1.8f)).toInt().coerceIn(0, 255)
                    canvas.drawText(g, cxp, gy, greet)
                    val half = greet.measureText(g) / 2f
                    greetStar.alpha = 235
                    drawStar(canvas, cxp - half - dpf(34f), gy - dpf(10f), dpf(12f) * (0.8f + 0.2f * sin(t * 2.4f)), greetStar)
                    drawStar(canvas, cxp + half + dpf(34f), gy - dpf(10f), dpf(12f) * (0.8f + 0.2f * sin(t * 2.4f + 1.5f)), greetStar)
                }

                // dòng phụ
                sub.textSize = dpf(20f)
                sub.alpha = (160 + 80 * sin(t * 2f)).toInt().coerceIn(0, 255)
                canvas.drawText(hint, cxp, cyp + bmp.height * 0.5f + dpf(46f), sub)
            }

            // thanh equalizer nhảy theo nhịp ở đáy
            val n = 28
            val bwid = dpf(10f); val gap = dpf(8f)
            val total = n * (bwid + gap) - gap
            var x = (w - total) / 2f
            val base = h - dpf(26f)
            for (i in 0 until n) {
                val v = abs(sin(t * (1.6f + (i % 5) * 0.35f) + i * 0.7f))
                val bh = dpf(8f) + v * dpf(44f)
                bar.shader = LinearGradient(0f, base - bh, 0f, base,
                    intArrayOf(th.eqTop, th.eqBottom), null, Shader.TileMode.CLAMP)
                rectF.set(x, base - bh, x + bwid, base)
                canvas.drawRoundRect(rectF, bwid / 2f, bwid / 2f, bar)
                x += bwid + gap
            }

            drawParticles(canvas, w, h)

            // rèm + diềm + đèn nằm trên cùng để khung lại sân khấu (hạt rơi ra phía sau rèm)
            drawCurtains(canvas, w, h)
        }

        // ------------------------------------------------------------------ đèn spotlight
        /** Hai đèn treo dưới diềm rèm, chùm sáng quét chậm qua lại (chu kỳ ~20 giây) và vệt sáng trên sàn. */
        private fun drawSpotlights(c: Canvas, w: Float, h: Float) {
            val floorY = h * 0.84f
            val lampY = valBase + valDip + dpf(2f)
            for (k in 0..1) {
                val lx = if (k == 0) w * 2.5f / 9f else w * 6.5f / 9f          // thẳng hàng với đáy vòng cung của diềm
                val sway = sin(t * (0.33f - k * 0.04f) + k * 2f)
                val tx = lx + sway * w * 0.20f + (if (k == 0) w * 0.10f else -w * 0.10f)
                val fw = w * 0.085f
                val hw = dpf(7f)
                path.reset()
                path.moveTo(lx - hw, lampY); path.lineTo(lx + hw, lampY)
                path.lineTo(tx + fw, floorY); path.lineTo(tx - fw, floorY); path.close()
                c.drawPath(path, beam)

                // vệt sáng hình elip trên sàn
                c.save(); c.translate(tx, floorY); c.scale(1f, 0.22f)
                c.drawCircle(0f, 0f, w * 0.13f, pool)
                c.restore()
            }
        }

        // ------------------------------------------------------------------ rèm sân khấu
        private fun edgeX(inner: Float, flare: Float, e: Float, yf: Float): Float {
            val d = yf - 0.62f
            val sway = sin(t * 0.9f + yf * 4f) * dpf(4f) * yf * e
            return inner * (1f + flare * d * d * 2.6f) + sway
        }

        private fun drawCurtains(c: Canvas, w: Float, h: Float) {
            // rèm kéo ra sau ~0,4 giây đầu, mất ~2,2 giây (chậm dần về cuối)
            val p = ((curtainT - 0.4f) / 2.2f).coerceIn(0f, 1f)
            val e = 1f - (1f - p) * (1f - p) * (1f - p)
            val inner = closedInner + (openInner - closedInner) * e
            drawCurtain(c, w, h, inner, e, false)
            drawCurtain(c, w, h, inner, e, true)

            // diềm rèm phía trên + viền vàng
            c.drawPath(valancePath, valancePaint)
            c.drawPath(valanceTrim, trimPaint)

            // đèn: bóng đèn nhỏ treo dưới đáy vòng cung + quầng sáng
            val lampY = valBase + valDip + dpf(2f)
            for (k in 0..1) {
                val lx = if (k == 0) w * 2.5f / 9f else w * 6.5f / 9f
                c.save(); c.translate(lx, lampY); c.drawCircle(0f, 0f, dpf(26f), lamp); c.restore()
                dot.color = 0xFFFFF8E1.toInt()
                c.drawCircle(lx, lampY, dpf(6f), dot)
            }
            dot.color = theme.trim
        }

        private fun drawCurtain(c: Canvas, w: Float, h: Float, inner: Float, e: Float, mirror: Boolean) {
            val flare = 0.55f * e   // khép: mép thẳng; mở: loe rộng ở trên/dưới, thắt ở chỗ buộc dây
            c.save()
            if (mirror) c.scale(-1f, 1f, w / 2f, 0f)

            val steps = 12
            curtainPath.reset(); edgePath.reset()
            curtainPath.moveTo(0f, 0f)
            for (i in 0..steps) {
                val yf = i / steps.toFloat()
                val x = edgeX(inner, flare, e, yf)
                curtainPath.lineTo(x, yf * h)
                if (i == 0) edgePath.moveTo(x, 0f) else edgePath.lineTo(x, yf * h)
            }
            curtainPath.lineTo(0f, h); curtainPath.close()

            // nếp gấp trượt theo mép rèm khi kéo ra/vào (vải dồn lại ở hai bên)
            val shift = inner * (1f + 0.55f * e) - foldSpan
            shaderM.setTranslate(shift, 0f)
            foldShader?.setLocalMatrix(shaderM)
            curtainPaint.shader = foldShader
            c.drawPath(curtainPath, curtainPaint)
            c.drawPath(curtainPath, shadePaint)

            trimPaint.alpha = 150
            c.drawPath(edgePath, trimPaint)
            trimPaint.alpha = 255

            // dây buộc rèm + tua rua vàng (chỉ hiện khi rèm đã mở)
            if (e > 0.5f) {
                val a = ((e - 0.5f) * 2f * 255).toInt().coerceIn(0, 255)
                val yTie = h * 0.62f
                val ex = edgeX(inner, flare, e, 0.62f)
                cordPaint.alpha = a
                val len = minOf(ex, dpf(110f))
                c.drawLine(ex - len, yTie - dpf(8f), ex + dpf(2f), yTie, cordPaint)
                dot.alpha = a
                c.drawCircle(ex, yTie + dpf(4f), dpf(9f), dot)
                flakeLine.color = theme.trim
                flakeLine.alpha = a
                flakeLine.strokeWidth = dpf(2.5f)
                for (k in -2..2) {
                    c.drawLine(ex + k * dpf(3f), yTie + dpf(10f), ex + k * dpf(5f), yTie + dpf(38f), flakeLine)
                }
                dot.alpha = 255
                cordPaint.alpha = 255
            }
            c.restore()
        }

        // ------------------------------------------------------------------ hạt bay theo dịp
        private fun frac(x: Float): Float = x - floor(x)

        private fun drawParticles(c: Canvas, w: Float, h: Float) {
            when (theme.particles) {
                Particles.TWINKLE -> {
                    for (s in sparks) {
                        val a = (sin(t * s.speed + s.phase) * 0.5f + 0.5f)
                        if (a < 0.15f) continue
                        star.alpha = (a * 220).toInt()
                        drawStar(c, s.x * w, s.y * h * 0.85f, dpf(s.size) * (0.5f + a * 0.5f), star)
                    }
                }
                Particles.BLOSSOM -> drawBlossoms(c, w, h)
                Particles.SNOW -> drawSnow(c, w, h)
                Particles.CONFETTI -> drawConfetti(c, w, h)
            }
        }

        /** Hoa mai vàng + hoa đào hồng rơi chậm, xoay nhẹ. */
        private fun drawBlossoms(c: Canvas, w: Float, h: Float) {
            val cols = theme.particleColors
            for (i in 0 until 24) {
                val px = frac(i * 0.618034f + 0.13f)
                val r2 = frac(i * 0.381966f + 0.07f)
                val r3 = frac(i * 0.754878f + 0.31f)
                val yf = frac(r3 + t * (0.045f + 0.035f * r2))
                val x = px * w + sin(t * (0.5f + r2) + i) * dpf(30f) + yf * dpf(40f)
                val y = -h * 0.05f + yf * h * 1.1f
                val size = dpf(10f + 8f * r3)
                val col = cols[i % cols.size.coerceAtLeast(1)]
                c.save(); c.translate(x, y); c.rotate(t * 35f * (0.6f + r2) + i * 37f)
                dot.color = col; dot.alpha = 225
                for (k in 0 until 5) {
                    val a = (k * 2 * PI / 5).toFloat()
                    c.drawCircle(cos(a) * size * 0.5f, sin(a) * size * 0.5f, size * 0.42f, dot)
                }
                dot.color = blend(col, 0xFFE65100.toInt(), 0.7f); dot.alpha = 255
                c.drawCircle(0f, 0f, size * 0.2f, dot)
                c.restore()
            }
            dot.color = theme.trim; dot.alpha = 255
        }

        /** Tuyết rơi: chấm tròn mềm và vài bông tuyết 6 cánh. */
        private fun drawSnow(c: Canvas, w: Float, h: Float) {
            dot.color = theme.particleColors[0]
            flakeLine.color = theme.particleColors[0]
            for (i in 0 until 46) {
                val px = frac(i * 0.618034f + 0.21f)
                val r2 = frac(i * 0.381966f + 0.11f)
                val r3 = frac(i * 0.754878f + 0.47f)
                val yf = frac(r3 + t * (0.06f + 0.07f * r2))
                val x = px * w + sin(t * (0.4f + r2) + i * 1.7f) * dpf(26f)
                val y = -h * 0.04f + yf * h * 1.08f
                if (i % 9 == 0) {
                    val s = dpf(11f + 5f * r2)
                    flakeLine.alpha = 220; flakeLine.strokeWidth = dpf(1.8f)
                    c.save(); c.translate(x, y); c.rotate(t * 25f + i * 20f)
                    for (k in 0 until 3) {
                        c.drawLine(-s, 0f, s, 0f, flakeLine)
                        c.rotate(60f)
                    }
                    c.restore()
                } else {
                    dot.alpha = (120 + 110 * r2).toInt()
                    c.drawCircle(x, y, dpf(2f + 4f * r3), dot)
                }
            }
            dot.color = theme.trim; dot.alpha = 255
        }

        /** Giấy confetti nhiều màu xoay lật + vài quả bóng bay lơ lửng bay lên. */
        private fun drawConfetti(c: Canvas, w: Float, h: Float) {
            val cols = theme.particleColors
            val nc = cols.size.coerceAtLeast(1)

            // bóng bay
            for (i in 0 until 5) {
                val yf = frac(i * 0.2f + t * 0.032f)
                val x = (0.12f + 0.76f * frac(i * 0.381966f + 0.3f)) * w + sin(t * 0.6f + i * 2f) * dpf(22f)
                val y = h * (1.12f - yf * 1.35f)
                val rx = dpf(24f); val ry = dpf(30f)
                val col = cols[i % nc]
                // dây
                flakeLine.color = 0xAAFFFFFF.toInt(); flakeLine.strokeWidth = dpf(1.6f)
                c.drawLine(x, y + ry, x + sin(t + i) * dpf(6f), y + ry + dpf(70f), flakeLine)
                // thân bóng + nút thắt + vệt sáng
                dot.color = col; dot.alpha = 235
                rectF.set(x - rx, y - ry, x + rx, y + ry)
                c.drawOval(rectF, dot)
                path.reset()
                path.moveTo(x, y + ry - dpf(1f)); path.lineTo(x - dpf(5f), y + ry + dpf(7f)); path.lineTo(x + dpf(5f), y + ry + dpf(7f)); path.close()
                c.drawPath(path, dot)
                dot.color = Color.WHITE; dot.alpha = 110
                rectF.set(x - rx * 0.55f, y - ry * 0.7f, x - rx * 0.1f, y - ry * 0.2f)
                c.drawOval(rectF, dot)
            }

            // confetti
            for (i in 0 until 44) {
                val px = frac(i * 0.618034f + 0.09f)
                val r2 = frac(i * 0.381966f + 0.05f)
                val r3 = frac(i * 0.754878f + 0.27f)
                val yf = frac(r3 + t * (0.07f + 0.09f * r2))
                val x = px * w + sin(t * (0.6f + r2) + i) * dpf(22f)
                val y = -h * 0.04f + yf * h * 1.08f
                val wd = dpf(8f) * abs(cos(t * 3f + i)) + dpf(1.5f)
                val ht = dpf(12f)
                dot.color = cols[i % nc]; dot.alpha = 230
                c.save(); c.translate(x, y); c.rotate(t * 90f * (r2 + 0.3f) + i * 41f)
                c.drawRect(-wd / 2f, -ht / 2f, wd / 2f, ht / 2f, dot)
                c.restore()
            }
            dot.color = theme.trim; dot.alpha = 255
        }

        private fun drawStar(c: Canvas, x: Float, y: Float, r: Float, paint: Paint) {
            path.reset()
            path.moveTo(x, y - r); path.quadTo(x, y, x + r, y)
            path.quadTo(x, y, x, y + r); path.quadTo(x, y, x - r, y)
            path.quadTo(x, y, x, y - r); path.close()
            c.drawPath(path, paint)
        }

        private fun withAlpha(color: Int, a: Int) = (color and 0x00FFFFFF) or (a shl 24)
        private fun dpf(v: Float) = v * resources.displayMetrics.density

        companion object {
            private const val FRAME_NS = 30_000_000L
        }
    }

    // ---------------------------------------------------------------------------------------------
    /** Nút bánh xe răng cưa vẽ bằng Path (không cần file icon). Focus (remote TV) → sáng viền + xoay. */
    private class GearButton(ctx: Context) : View(ctx) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val disc = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val gearPath = Path()
        private var angle = 0f
        private var spin: ValueAnimator? = null

        init {
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            contentDescription = "Cài đặt"
            alpha = 0.8f
        }

        fun setRemoteFocusable(v: Boolean) {
            isFocusable = v
            isFocusableInTouchMode = v
            if (!v) clearFocus()
        }

        override fun drawableStateChanged() {
            super.drawableStateChanged()
            val active = isFocused || isPressed
            animate().alpha(if (active) 1f else 0.8f).scaleX(if (active) 1.18f else 1f)
                .scaleY(if (active) 1.18f else 1f).setDuration(150).start()
            if (isFocused) {
                if (spin == null) spin = ValueAnimator.ofFloat(0f, 360f).apply {
                    duration = 6000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
                    addUpdateListener { angle = it.animatedValue as Float; invalidate() }
                    start()
                }
            } else {
                spin?.cancel(); spin = null; angle = 0f
            }
            invalidate()
        }

        override fun onDetachedFromWindow() { spin?.cancel(); spin = null; super.onDetachedFromWindow() }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f; val cy = height / 2f
            val r = minOf(cx, cy)

            // nền tròn mờ
            disc.shader = RadialGradient(cx, cy, r,
                intArrayOf(0xCC3A1F66.toInt(), 0xCC1B0E35.toInt()), null, Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, r - 2f, disc)
            ring.strokeWidth = if (isFocused) r * 0.07f else r * 0.04f
            ring.color = if (isFocused) 0xFFFFD56B.toInt() else 0x66FFFFFF
            canvas.drawCircle(cx, cy, r - ring.strokeWidth, ring)

            // bánh răng: 8 răng, lỗ giữa (EVEN_ODD)
            val teeth = 8
            val rOut = r * 0.58f; val rIn = r * 0.44f
            gearPath.reset()
            gearPath.fillType = Path.FillType.EVEN_ODD
            val step = (2 * PI / teeth).toFloat()
            for (i in 0 until teeth) {
                val a0 = i * step
                val p = arrayOf(
                    a0 - step * 0.24f to rIn, a0 - step * 0.13f to rOut,
                    a0 + step * 0.13f to rOut, a0 + step * 0.24f to rIn
                )
                p.forEachIndexed { k, (ang, rad) ->
                    val x = cx + cos(ang) * rad; val y = cy + sin(ang) * rad
                    if (i == 0 && k == 0) gearPath.moveTo(x, y) else gearPath.lineTo(x, y)
                }
            }
            gearPath.close()
            gearPath.addCircle(cx, cy, r * 0.2f, Path.Direction.CCW)

            fill.shader = LinearGradient(0f, cy - rOut, 0f, cy + rOut,
                intArrayOf(0xFFFFF4C2.toInt(), 0xFFFF9AD5.toInt()), null, Shader.TileMode.CLAMP)
            canvas.save()
            canvas.rotate(angle, cx, cy)
            canvas.drawPath(gearPath, fill)
            canvas.restore()
        }
    }
}
