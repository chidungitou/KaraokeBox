package com.karaokebox.player.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Xử lý tiếng micro (mono PCM16) thành stereo PCM16 sẵn sàng phát: nhân âm lượng → EQ giọng hát nhẹ → nén → vang → limiter.
 * Tái dùng [StudioDsp] (cùng bộ xử lý với nhạc nền) nên không thêm thuật toán mới. Thuần Kotlin, không phụ thuộc Android
 * để kiểm thử được trên JVM (xem MicProcessorTest).
 *
 * [configure] gọi được từ luồng bất kỳ; luồng xử lý tự nhận thay đổi ở khối âm thanh kế tiếp (không khóa, không cấp phát).
 * [process] chỉ gọi từ MỘT luồng (luồng thu âm).
 */
class MicProcessor(private val sampleRate: Int) {

    companion object {
        /** Âm lượng 100% = nhân 2.0 (+6 dB) so với tín hiệu thu; mặc định 60% ≈ +1.6 dB. */
        const val MAX_GAIN = 2f
        const val DEFAULT_VOLUME = 60
        const val DEFAULT_ECHO = 30

        /** Thông số DSP cho giọng hát; [echo] 0..1 quyết định độ vang. Không có kệ bass lớn (tránh ù khi kề micro). */
        fun paramsFor(echo: Float): StudioParams {
            val e = echo.coerceIn(0f, 1f)
            return StudioParams(
                lowDb = -2.0f, lowHz = 150f,          // bớt ù do kề sát micro (proximity effect)
                mudDb = -1.5f, mudHz = 300f,
                presDb = 1.5f, presHz = 3500f,        // rõ tiếng
                airDb = 1.5f, airHz = 9000f,
                compThrDb = -20f, compRatio = 2.5f, makeupDb = 0f,
                reverbMix = 0.32f * e, reverbRoom = 0.72f, reverbDamp = 0.40f,
                width = 1.0f
            )
        }
    }

    private val dsp = StudioDsp(sampleRate)

    @Volatile private var gain = DEFAULT_VOLUME / 100f * MAX_GAIN
    @Volatile private var echo = DEFAULT_ECHO / 100f
    @Volatile private var version = 0
    private var applied = -1

    private var work: ByteBuffer = ByteBuffer.allocate(0)

    /** [volumePct], [echoPct]: 0..100. */
    fun configure(volumePct: Int, echoPct: Int) {
        gain = volumePct.coerceIn(0, 100) / 100f * MAX_GAIN
        echo = echoPct.coerceIn(0, 100) / 100f
        version++
    }

    /** Xoá đuôi vang (bật lại micro, đổi thiết bị). */
    fun reset() { dsp.reset(); applied = -1 }

    /**
     * Xử lý [count] mẫu mono đầu của [mono], ghi stereo little-endian vào [out] (đã clear, little-endian).
     * Trả về số byte đã ghi (= count * 4).
     */
    fun process(mono: ShortArray, count: Int, out: ByteBuffer): Int {
        val n = count.coerceIn(0, mono.size)
        if (n == 0) return 0
        require(out.order() == ByteOrder.LITTLE_ENDIAN) { "out phải little-endian" }
        require(out.remaining() >= n * 4) { "out quá nhỏ" }

        val v = version
        if (v != applied) {
            dsp.configure(paramsFor(echo), 1f)
            applied = v
        }

        if (work.capacity() < n * 4) work = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN)
        work.clear()
        val g = gain
        for (i in 0 until n) {
            var s = (mono[i] * g).toInt()
            if (s > 32767) s = 32767 else if (s < -32768) s = -32768
            val sh = s.toShort()
            work.putShort(sh); work.putShort(sh)      // mono → L = R
        }
        work.flip()

        val start = out.position()
        dsp.process(work, out)
        return out.position() - start
    }
}
