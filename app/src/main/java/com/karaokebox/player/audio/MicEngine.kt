package com.karaokebox.player.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Process
import androidx.core.content.ContextCompat
import com.karaokebox.ServiceLocator
import com.karaokebox.data.repo.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/**
 * Micro cắm vào box (USB / jack): thu bằng AudioRecord, xử lý bằng [MicProcessor] (vang, nén...) rồi phát bằng một
 * AudioTrack RIÊNG, trộn với nhạc ở tầng mixer của Android. Không đi qua ExoPlayer nên không ảnh hưởng đổi tone/tốc độ
 * hay bộ xử lý nhạc nền, và tắt micro thì không tốn CPU.
 *
 * Vòng đời: PlayerActivity gọi [start] khi hiện lên màn hình và [stop] khi rời đi. Cài đặt (bật/tắt, âm lượng, vang) đổi
 * từ điện thoại hay TV đều qua [SettingsStore.changes] nên áp dụng ngay không cần khởi động lại.
 *
 * Độ trễ: khối 10 ms + bộ đệm AudioTrack nhỏ nhất thiết bị cho phép (Android 8+ xin chế độ độ trễ thấp). Trên box giá rẻ
 * tổng thường vài chục tới ~150 ms; nếu thấy nghe tiếng mình trễ khó hát thì nên dùng vang của amply thay thế.
 */
class MicEngine(
    private val ctx: Context,
    private val settings: SettingsStore,
    private val scope: CoroutineScope
) {
    companion object {
        const val OFF = "OFF"
        const val RUNNING = "RUNNING"
        const val NO_PERMISSION = "NO_PERMISSION"
        const val NO_DEVICE = "NO_DEVICE"
        const val ERROR = "ERROR"

        private const val RATE = 44100
        private val MIC_KEYS = setOf(SettingsStore.K_MIC_ENABLED, SettingsStore.K_MIC_VOLUME, SettingsStore.K_MIC_ECHO)

        fun hasPermission(ctx: Context): Boolean =
            Build.VERSION.SDK_INT < 23 ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private val _status = MutableStateFlow(OFF)
    /** OFF | RUNNING | NO_PERMISSION | NO_DEVICE | ERROR. Điện thoại và bảng cài đặt TV hiển thị theo giá trị này. */
    val status: StateFlow<String> = _status

    private val proc = MicProcessor(RATE)
    private var started = false
    private var watcher: Job? = null
    private var capture: Capture? = null

    /** Gọi trên luồng Main khi màn hình hát hiện lên. */
    @Synchronized
    fun start() {
        started = true
        if (watcher == null) {
            watcher = scope.launch { settings.changes.collect { if (it in MIC_KEYS) apply() } }
        }
        apply()
    }

    /** Gọi khi rời màn hình hát: dừng thu âm ngay (không giữ micro khi app ở nền). */
    @Synchronized
    fun stop() {
        started = false
        watcher?.cancel(); watcher = null
        stopCapture()
        setStatus(OFF)
    }

    /** Đọc lại cài đặt và đưa thu âm về đúng trạng thái. Cũng gọi sau khi người dùng cấp quyền micro. */
    @Synchronized
    fun apply() {
        if (!started) return
        proc.configure(settings.micVolume, settings.micEcho)
        when {
            !settings.micEnabled -> { stopCapture(); setStatus(OFF) }
            !hasPermission(ctx) -> { stopCapture(); setStatus(NO_PERMISSION) }
            capture?.isAlive != true -> startCapture()
        }
    }

    private fun startCapture() {
        capture?.let { old -> old.quit = true; try { old.join(500) } catch (_: InterruptedException) {} }
        proc.reset()
        capture = Capture().also { it.start() }
    }

    private fun stopCapture() {
        capture?.quit = true
        capture = null    // luồng tự nhả AudioRecord/AudioTrack khi thấy cờ quit (không chặn luồng Main)
    }

    private fun setStatus(s: String) {
        if (_status.value == s) return
        _status.value = s
        settings.notifyChanged(SettingsStore.K_MIC_STATUS)   // điện thoại + bảng TV cập nhật dòng trạng thái
        val msg = when (s) {
            NO_DEVICE -> "Micro: không thấy micro nào, hãy cắm micro USB vào box"
            ERROR -> "Micro: không mở được thiết bị thu âm"
            else -> null
        }
        if (msg != null) try { ServiceLocator.controller.announce(msg) } catch (_: Throwable) {}
    }

    private fun hasInputDevice(): Boolean {
        if (Build.VERSION.SDK_INT < 23) return true
        return try {
            (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).getDevices(AudioManager.GET_DEVICES_INPUTS).isNotEmpty()
        } catch (_: Throwable) { true }
    }

    private fun newTrack(frame: Int): AudioTrack? {
        val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return null
        val size = max(min, frame * 4 * 2)
        return if (Build.VERSION.SDK_INT >= 26) {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
                )
                .setAudioFormat(
                    AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
                )
                .setBufferSizeInBytes(size)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(AudioManager.STREAM_MUSIC, RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT, size, AudioTrack.MODE_STREAM)
        }
    }

    private inner class Capture : Thread("karaoke-mic") {
        @Volatile var quit = false

        private fun fail(s: String) { if (!quit) setStatus(s) }

        override fun run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            var rec: AudioRecord? = null
            var track: AudioTrack? = null
            try {
                val frame = RATE / 100   // 10 ms
                val minRec = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (minRec <= 0) { fail(NO_DEVICE); return }
                rec = AudioRecord(
                    MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, max(minRec, frame * 2 * 4)
                )
                if (rec.state != AudioRecord.STATE_INITIALIZED) { fail(if (hasInputDevice()) ERROR else NO_DEVICE); return }
                track = newTrack(frame)
                if (track == null || track.state != AudioTrack.STATE_INITIALIZED) { fail(ERROR); return }

                val shorts = ShortArray(frame)
                val out = ByteBuffer.allocate(frame * 4).order(ByteOrder.LITTLE_ENDIAN)
                rec.startRecording()
                track.play()
                if (!quit) setStatus(RUNNING)

                while (!quit) {
                    val n = rec.read(shorts, 0, frame)    // chặn tới khi đủ 1 khối
                    if (n < 0) { fail(ERROR); return }
                    if (n == 0) continue
                    out.clear()
                    val bytes = proc.process(shorts, n, out)
                    if (bytes > 0 && track.write(out.array(), 0, bytes) < 0) { fail(ERROR); return }
                }
            } catch (_: Throwable) {
                fail(ERROR)
            } finally {
                try { rec?.stop() } catch (_: Throwable) {}
                try { rec?.release() } catch (_: Throwable) {}
                try { track?.stop() } catch (_: Throwable) {}
                try { track?.release() } catch (_: Throwable) {}
            }
        }
    }
}
