package com.karaokebox.player.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * Tách kênh Stereo 16-bit: nhân đôi kênh TRÁI hoặc PHẢI ra cả hai loa (dual-mono).
 * Chỉ kích hoạt với PCM 16-bit 2 kênh; định dạng khác (5.1...) được bỏ qua (không lỗi).
 */
@OptIn(UnstableApi::class)
class ChannelSelectAudioProcessor : BaseAudioProcessor() {
    enum class Mode { PASS, LEFT, RIGHT }

    @Volatile var mode: Mode = Mode.PASS

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT && inputAudioFormat.channelCount == 2) {
            inputAudioFormat
        } else {
            AudioProcessor.AudioFormat.NOT_SET   // không hoạt động với định dạng này
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val out = replaceOutputBuffer(remaining)
        val m = mode
        if (m == Mode.PASS) {
            out.put(inputBuffer)
        } else {
            var pos = inputBuffer.position()
            val end = inputBuffer.limit()
            val off = if (m == Mode.LEFT) 0 else 2
            while (pos + 3 < end) {
                val lo = inputBuffer.get(pos + off)
                val hi = inputBuffer.get(pos + off + 1)
                out.put(lo).put(hi).put(lo).put(hi)
                pos += 4
            }
            // Byte lẻ (nếu có) được bỏ để giữ khung 4 byte
            inputBuffer.position(end)
        }
        out.flip()
    }
}
