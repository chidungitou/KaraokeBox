package com.karaokebox.player.audio

import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Chuỗi xử lý "phòng thu" cho PCM 16-bit stereo, thuần Kotlin (không phụ thuộc Android) để dễ kiểm thử:
 *   EQ (lọc rumble + bass + cắt đục + rõ lời + sáng) → nén động liên kết L/R → vang Freeverb
 *   → mở rộng stereo (Mid/Side) → limiter chống vỡ tiếng.
 * Mọi thông số đổi mềm (làm mượt từng mẫu) nên đổi hiệu ứng lúc đang hát không bị "bụp".
 * Không cấp phát bộ nhớ trong vòng lặp xử lý.
 */
class StudioDsp(private val sampleRate: Int) {

    // ---------- Bộ lọc biquad (RBJ cookbook), dạng Transposed Direct Form II, mỗi kênh một trạng thái ----------
    private class Biquad {
        var on = false
        var b0 = 1f; var b1 = 0f; var b2 = 0f; var a1 = 0f; var a2 = 0f
        var zl1 = 0f; var zl2 = 0f; var zr1 = 0f; var zr2 = 0f

        fun reset() { zl1 = 0f; zl2 = 0f; zr1 = 0f; zr2 = 0f }

        fun set(nb0: Double, nb1: Double, nb2: Double, na0: Double, na1: Double, na2: Double) {
            b0 = (nb0 / na0).toFloat(); b1 = (nb1 / na0).toFloat(); b2 = (nb2 / na0).toFloat()
            a1 = (na1 / na0).toFloat(); a2 = (na2 / na0).toFloat()
        }

        fun highPass(fs: Int, hz: Double, q: Double) {
            val w = 2 * PI * hz / fs; val c = cos(w); val al = sin(w) / (2 * q)
            set((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + al, -2 * c, 1 - al)
        }

        fun peak(fs: Int, hz: Double, db: Double, q: Double) {
            val a = 10.0.pow(db / 40); val w = 2 * PI * hz / fs; val c = cos(w); val al = sin(w) / (2 * q)
            set(1 + al * a, -2 * c, 1 - al * a, 1 + al / a, -2 * c, 1 - al / a)
        }

        fun lowShelf(fs: Int, hz: Double, db: Double) {
            val a = 10.0.pow(db / 40); val w = 2 * PI * hz / fs; val c = cos(w)
            val al = sin(w) / 2 * sqrt(2.0); val k = 2 * sqrt(a) * al
            set(a * ((a + 1) - (a - 1) * c + k), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - k),
                (a + 1) + (a - 1) * c + k, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - k)
        }

        fun highShelf(fs: Int, hz: Double, db: Double) {
            val a = 10.0.pow(db / 40); val w = 2 * PI * hz / fs; val c = cos(w)
            val al = sin(w) / 2 * sqrt(2.0); val k = 2 * sqrt(a) * al
            set(a * ((a + 1) + (a - 1) * c + k), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - k),
                (a + 1) - (a - 1) * c + k, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - k)
        }
    }

    // ---------- Vang Freeverb: 8 comb song song + 4 allpass nối tiếp cho mỗi kênh ----------
    private class Comb(size: Int) {
        val buf = FloatArray(size); var idx = 0; var store = 0f
        fun reset() { buf.fill(0f); idx = 0; store = 0f }
    }
    private class Allpass(size: Int) {
        val buf = FloatArray(size); var idx = 0
        fun reset() { buf.fill(0f); idx = 0 }
    }

    private val combTune = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
    private val allpassTune = intArrayOf(556, 441, 341, 225)
    private val spread = 23
    private fun scaled(n: Int) = max(8, (n.toLong() * sampleRate / 44100L).toInt())

    private val combL = Array(8) { Comb(scaled(combTune[it])) }
    private val combR = Array(8) { Comb(scaled(combTune[it] + spread)) }
    private val apL = Array(4) { Allpass(scaled(allpassTune[it])) }
    private val apR = Array(4) { Allpass(scaled(allpassTune[it] + spread)) }

    private val eq = arrayOf(Biquad(), Biquad(), Biquad(), Biquad(), Biquad())   // HPF, bass, đục, rõ lời, sáng

    // ---------- Tham số hiện hành ----------
    /** false = bỏ qua hoàn toàn (copy thẳng), không tốn CPU. */
    var active = false; private set
    private var feedback = 0.85f
    private var damp1 = 0.2f
    private var damp2 = 0.8f
    private var wetTarget = 0f; private var wet = 0f
    private var widthTarget = 1f; private var width = 1f
    private var compOn = false
    private var thrDb = -18f
    private var slope = 0f          // 1 - 1/ratio
    private var makeupDb = 0f
    private var redDb = 0f          // mức nén đang áp (dB, dương = giảm)
    private var atk = 0f; private var rel = 0f
    private var limGain = 1f
    private var limRel = 0f
    private val ceiling = 0.92f

    init {
        val fs = sampleRate.toDouble()
        atk = exp(-1.0 / (0.010 * fs)).toFloat()      // attack 10 ms
        rel = exp(-1.0 / (0.150 * fs)).toFloat()      // release 150 ms
        limRel = exp(-1.0 / (0.080 * fs)).toFloat()   // limiter release 80 ms
    }

    /** Xoá toàn bộ đuôi vang / trạng thái lọc (khi seek, đổi bài). */
    fun reset() {
        eq.forEach { it.reset() }
        combL.forEach { it.reset() }; combR.forEach { it.reset() }
        apL.forEach { it.reset() }; apR.forEach { it.reset() }
        redDb = 0f; limGain = 1f
    }

    /**
     * Áp một hiệu ứng ở cường độ [t] (0..1). params = null hoặc t = 0 → bỏ qua. Gọi từ luồng âm thanh,
     * giữ nguyên trạng thái lọc/vang đang chạy nên chuyển hiệu ứng mượt.
     */
    fun configure(p: StudioParams?, t: Float) {
        val k = t.coerceIn(0f, 1f)
        if (p == null || k <= 0.001f) {
            if (active) { active = false }
            return
        }
        if (!active) reset()   // vừa bật lại: bắt đầu từ trạng thái sạch
        active = true
        val fs = sampleRate

        eq[0].apply { on = true; highPass(fs, 30.0, 0.707) }
        setBand(eq[1], p.lowDb * k) { gain -> lowShelf(fs, p.lowHz.toDouble(), gain) }
        setBand(eq[2], p.mudDb * k) { peak(fs, p.mudHz.toDouble(), it, 1.0) }
        setBand(eq[3], p.presDb * k) { peak(fs, p.presHz.toDouble(), it, 0.8) }
        setBand(eq[4], p.airDb * k) { highShelf(fs, p.airHz.toDouble(), it) }

        val ratio = 1f + (p.compRatio - 1f) * k
        compOn = ratio > 1.02f
        thrDb = p.compThrDb
        slope = 1f - 1f / ratio
        // Bù âm lượng tự động theo mức nén để bật hiệu ứng không làm bài nhỏ đi (makeupDb của preset là phần cộng thêm)
        makeupDb = (-p.compThrDb * slope * AUTO_MAKEUP + p.makeupDb * 0.5f) * k

        val room = p.reverbRoom.coerceIn(0f, 1f)
        feedback = room * 0.28f + 0.70f
        damp1 = p.reverbDamp.coerceIn(0f, 1f) * 0.4f
        damp2 = 1f - damp1
        wetTarget = p.reverbMix * k * 3f      // 3 = hệ số scalewet của Freeverb
        widthTarget = 1f + (p.width - 1f) * k
    }

    private inline fun setBand(b: Biquad, db: Float, f: Biquad.(Double) -> Unit) {
        if (abs(db) < 0.05f) { b.on = false } else { b.on = true; b.f(db.toDouble()) }
    }

    /** Xử lý toàn bộ [src] (PCM16 stereo, byte order của buffer) và ghi vào [dst]. Byte lẻ cuối được bỏ. */
    fun process(src: ByteBuffer, dst: ByteBuffer) {
        var pos = src.position()
        val end = src.limit()
        val inv = 1f / 32768f
        val wetStep = 0.0008f
        while (pos + 3 < end) {
            var l = src.getShort(pos) * inv
            var r = src.getShort(pos + 2) * inv
            pos += 4

            // 1) EQ
            for (f in eq) {
                if (!f.on) continue
                val yl = f.b0 * l + f.zl1
                f.zl1 = f.b1 * l - f.a1 * yl + f.zl2
                f.zl2 = f.b2 * l - f.a2 * yl
                val yr = f.b0 * r + f.zr1
                f.zr1 = f.b1 * r - f.a1 * yr + f.zr2
                f.zr2 = f.b2 * r - f.a2 * yr
                l = yl; r = yr
            }

            // 2) Nén động (detector liên kết L/R, knee mềm 6 dB) + makeup
            var gainDb = makeupDb
            if (compOn) {
                val pk = max(abs(l), abs(r))
                val lv = if (pk < 1e-5f) -100f else 8.685889f * ln(pk)
                val d = lv - thrDb
                val target = when {
                    2f * d < -KNEE -> 0f
                    2f * abs(d) <= KNEE -> slope * (d + KNEE / 2f) * (d + KNEE / 2f) / (2f * KNEE)
                    else -> slope * d
                }
                redDb = target + (if (target > redDb) atk else rel) * (redDb - target)
                gainDb -= redDb
            }
            if (gainDb != 0f) {
                val g = exp(gainDb * 0.11512925f)
                l *= g; r *= g
            }

            // 3) Vang
            wet += (wetTarget - wet) * wetStep
            if (wet > 0.0005f) {
                val input = (l + r) * 0.015f
                var oL = 0f; var oR = 0f
                for (c in combL) {
                    val o = c.buf[c.idx]
                    c.store = o * damp2 + c.store * damp1
                    if (c.store > -1e-20f && c.store < 1e-20f) c.store = 0f
                    c.buf[c.idx] = input + c.store * feedback
                    if (++c.idx >= c.buf.size) c.idx = 0
                    oL += o
                }
                for (c in combR) {
                    val o = c.buf[c.idx]
                    c.store = o * damp2 + c.store * damp1
                    if (c.store > -1e-20f && c.store < 1e-20f) c.store = 0f
                    c.buf[c.idx] = input + c.store * feedback
                    if (++c.idx >= c.buf.size) c.idx = 0
                    oR += o
                }
                for (a in apL) {
                    val bo = a.buf[a.idx]
                    val o = -oL + bo
                    a.buf[a.idx] = oL + bo * 0.5f
                    if (++a.idx >= a.buf.size) a.idx = 0
                    oL = o
                }
                for (a in apR) {
                    val bo = a.buf[a.idx]
                    val o = -oR + bo
                    a.buf[a.idx] = oR + bo * 0.5f
                    if (++a.idx >= a.buf.size) a.idx = 0
                    oR = o
                }
                l += oL * wet
                r += oR * wet
            }

            // 4) Mở rộng stereo (Mid/Side)
            width += (widthTarget - width) * wetStep
            if (width > 1.001f || width < 0.999f) {
                val m = (l + r) * 0.5f
                val s = (l - r) * 0.5f * width
                l = m + s; r = m - s
            }

            // 5) Limiter: hạ gain tức thời khi vượt trần, nhả 80 ms; cắt cứng lần cuối cho chắc
            val peak = max(abs(l), abs(r))
            val lt = if (peak > ceiling) ceiling / peak else 1f
            limGain = if (lt < limGain) lt else 1f + (limGain - 1f) * limRel
            l *= limGain; r *= limGain
            dst.putShort(toShort(l)); dst.putShort(toShort(r))
        }
        src.position(end)
    }

    private fun toShort(v: Float): Short {
        val x = (v * 32767f)
        return (if (x > 32767f) 32767f else if (x < -32768f) -32768f else x).toInt().toShort()
    }

    private companion object { const val KNEE = 6f; const val AUTO_MAKEUP = 0.30f }
}
