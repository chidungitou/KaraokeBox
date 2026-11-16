package com.karaokebox.player.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * Hiệu ứng âm thanh "phòng thu" (EQ + nén + vang + mở rộng stereo + limiter) chèn sau bộ tách kênh L/R.
 * Chỉ hoạt động với PCM 16-bit 2 kênh (cùng điều kiện với [ChannelSelectAudioProcessor]); định dạng khác
 * được bỏ qua, không lỗi. Đổi hiệu ứng/cường độ từ luồng bất kỳ qua [configure], áp dụng ở buffer kế tiếp.
 * Khi tắt (OFF hoặc cường độ 0) buffer được chép thẳng nên không tốn CPU thêm.
 */
@OptIn(UnstableApi::class)
class StudioAudioProcessor : BaseAudioProcessor() {

    @Volatile private var preset: StudioPreset = StudioPreset.OFF
    @Volatile private var level: Int = 70
    @Volatile private var version = 0
    private var appliedVersion = -1
    private var dsp: StudioDsp? = null

    /** [level] 0..100 (%). Gọi từ luồng chính; luồng âm thanh tự nhận thay đổi. */
    fun configure(preset: StudioPreset, level: Int) {
        this.preset = preset
        this.level = level.coerceIn(0, 100)
        version++
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT && inputAudioFormat.channelCount == 2 &&
            inputAudioFormat.sampleRate >= 8000
        ) {
            dsp = StudioDsp(inputAudioFormat.sampleRate)
            appliedVersion = -1
            inputAudioFormat
        } else {
            dsp = null
            AudioProcessor.AudioFormat.NOT_SET
        }
    }

    override fun onFlush() { dsp?.reset() }          // seek / đổi bài: bỏ đuôi vang cũ

    override fun onReset() { dsp = null }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val out = replaceOutputBuffer(remaining)
        val d = dsp
        if (d == null) {
            out.put(inputBuffer)
        } else {
            val v = version
            if (v != appliedVersion) {
                val p = preset
                d.configure(p.params, level / 100f)
                appliedVersion = v
            }
            if (d.active) d.process(inputBuffer, out) else out.put(inputBuffer)
        }
        out.flip()
    }
}
