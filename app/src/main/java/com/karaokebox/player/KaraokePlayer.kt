package com.karaokebox.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.karaokebox.data.yt.ResolvedMedia
import com.karaokebox.data.yt.YoutubeRepository
import com.karaokebox.player.audio.ChannelSelectAudioProcessor
import com.karaokebox.player.audio.StudioAudioProcessor
import java.io.File

/** Bọc ExoPlayer: giải mã phần cứng (fallback nếu lỗi), chọn audio track, tách kênh L/R, hiệu ứng âm thanh phòng thu. */
@OptIn(UnstableApi::class)
class KaraokePlayer(private val ctx: Context) {
    val channelProcessor = ChannelSelectAudioProcessor()
    /** Hiệu ứng phòng thu (EQ + nén + vang...), đứng sau bộ tách kênh và trước Sonic (đổi tone/tốc độ). */
    val studioProcessor = StudioAudioProcessor()
    val exo: ExoPlayer

    init {
        val factory = object : DefaultRenderersFactory(ctx) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(false)   // giữ PCM 16-bit để bộ tách kênh hoạt động
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessors(arrayOf(channelProcessor, studioProcessor))
                .build()
        }
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
        exo = ExoPlayer.Builder(ctx, factory).build()
    }

    fun load(path: String, startMs: Long = 0L) {
        exo.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))), startMs)
        exo.prepare()
        exo.playWhenReady = true
    }

    /**
     * Đổi tone (số nửa cung, âm/dương) và tốc độ (%) độc lập nhau: ExoPlayer dùng bộ xử lý Sonic để
     * giữ nguyên tốc độ khi đổi tone và ngược lại. Áp dụng cho cả bài trong máy lẫn YouTube.
     */
    fun setPitchTempo(semitones: Int, tempoPct: Int) {
        val pitch = Math.pow(2.0, semitones / 12.0).toFloat()
        exo.setPlaybackParameters(PlaybackParameters(tempoPct / 100f, pitch))
    }

    /**
     * Phát bài YouTube đã giải link: ghép luồng hình + tiếng, hoặc phát một luồng duy nhất.
     * Luồng hình/tiếng tách riêng được đi qua cache trên ổ box (nếu cacheMb > 0 và URL đủ thông tin định danh).
     */
    fun loadYoutube(videoId: String, media: ResolvedMedia, startMs: Long = 0L, cacheMb: Int = 0) {
        val plain = DataSource.Factory { ChunkedHttpDataSource(userAgentFor = { url -> YoutubeRepository.userAgentFor(url) }) }
        when (media) {
            is ResolvedMedia.Split -> {
                val vKey = YtCache.keyFor(videoId, media.videoUrl, "v")
                val aKey = YtCache.keyFor(videoId, media.audioUrl, "a")
                val useCache = cacheMb > 0 && vKey != null && aKey != null
                val factory = if (useCache) YtCache.wrap(ctx, cacheMb, plain) else plain
                // Ghi nhớ khóa cache của bài: lần sau nếu đã nằm đủ trong cache thì phát thẳng, không cần lấy link YouTube
                if (useCache && vKey != null && aKey != null) YtOfflineIndex.record(ctx, videoId, vKey, aKey, media.height)
                val f = ProgressiveMediaSource.Factory(factory)
                val v = f.createMediaSource(cachedItem(media.videoUrl, if (useCache) vKey else null))
                val a = f.createMediaSource(cachedItem(media.audioUrl, if (useCache) aKey else null))
                exo.setMediaSource(MergingMediaSource(v, a), startMs)
            }
            is ResolvedMedia.Single ->
                if (media.hls) {
                    val http = DefaultHttpDataSource.Factory()
                        .setUserAgent(YoutubeRepository.userAgentFor(media.url))
                        .setAllowCrossProtocolRedirects(true)
                    exo.setMediaSource(HlsMediaSource.Factory(http).createMediaSource(MediaItem.fromUri(media.url)), startMs)
                } else {
                    exo.setMediaSource(ProgressiveMediaSource.Factory(plain).createMediaSource(MediaItem.fromUri(media.url)), startMs)
                }
        }
        exo.prepare()
        exo.playWhenReady = true
    }

    /**
     * Phát bài YouTube đã nằm đủ trong cache, KHÔNG ra mạng (không cần lấy link). Trả về false nếu không mở được cache.
     * Khóa cache lấy từ sổ [YtOfflineIndex]; địa chỉ giả chỉ để ExoPlayer có URI, dữ liệu đọc hoàn toàn từ cache.
     */
    fun loadOffline(videoId: String, entry: YtOfflineIndex.Entry, startMs: Long, cacheMb: Int): Boolean {
        val factory = YtCache.cacheOnly(ctx, cacheMb) ?: return false
        val f = ProgressiveMediaSource.Factory(factory)
        val v = f.createMediaSource(cachedItem("https://offline.invalid/yt/$videoId/v", entry.vKey))
        val a = f.createMediaSource(cachedItem("https://offline.invalid/yt/$videoId/a", entry.aKey))
        exo.setMediaSource(MergingMediaSource(v, a), startMs)
        exo.prepare()
        exo.playWhenReady = true
        return true
    }

    private fun cachedItem(url: String, cacheKey: String?): MediaItem {
        val b = MediaItem.Builder().setUri(url)
        if (cacheKey != null) b.setCustomCacheKey(cacheKey)
        return b.build()
    }

    /** Các nhóm audio track mà thiết bị giải mã được (theo thứ tự trong container). */
    fun audioGroups(): List<Tracks.Group> =
        exo.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO && it.isSupported }

    fun selectAudioGroup(index: Int) {
        val g = audioGroups().getOrNull(index) ?: return
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, 0))
            .build()
    }

    fun release() = exo.release()
}
