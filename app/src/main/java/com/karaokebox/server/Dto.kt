package com.karaokebox.server

import com.karaokebox.ServiceLocator
import com.karaokebox.data.db.SongEntity
import com.karaokebox.data.repo.QueueEntry
import com.karaokebox.data.repo.QueueState
import com.karaokebox.data.repo.SettingsStore
import com.karaokebox.data.yt.isYoutube
import com.karaokebox.data.yt.youtubeId
import com.karaokebox.player.audio.StudioPreset
import com.karaokebox.ui.overlay.StageTheme

data class SongDto(
    val id: Long, val code: String?, val title: String, val artist: String,
    val ext: String, val durationMs: Long,
    val source: String = "LOCAL",          // LOCAL | YOUTUBE
    val videoId: String? = null            // chỉ có khi source = YOUTUBE
)

data class QueueItemDto(
    val itemId: Long, val position: Int, val addedBy: String, val addedAt: Long, val song: SongDto
)

data class PlayerSnapshot(
    val status: String = "IDLE",          // IDLE | LOADING | PLAYING | PAUSED | ENDED
    val nowPlaying: SongDto? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val vocalOn: Boolean = true,
    val audioMode: String = "NONE",       // TRACK | LR_LEFT_VOCAL | LR_RIGHT_VOCAL | NONE
    val canSeparateVocal: Boolean = false,
    val volume: Int = 0,
    val maxVolume: Int = 100,
    val serverTime: Long = System.currentTimeMillis(),
    val keyShift: Int = 0,                // tone: -6..+6 nửa cung
    val tempoPct: Int = 100               // tốc độ: 70..130 (%)
)

data class ToastEvent(val kind: String, val message: String, val byName: String? = null, val song: SongDto? = null)

data class ThemeModeDto(val key: String, val label: String)

data class AudioFxModeDto(val key: String, val label: String, val note: String)

/** Các cài đặt chỉnh được ngay lúc đang hát (điện thoại hiện bảng Cài đặt, TV có bảng nhanh). */
data class LiveSettingsDto(
    val leftIsVocal: Boolean,             // file 1 track stereo: kênh TRÁI là ca sĩ
    val fairQueue: Boolean,
    val youtubeMaxHeight: Int,
    val stageTheme: String,               // AUTO | DEFAULT | TET | NOEL | BIRTHDAY
    val stageThemeNow: String,            // tên giao diện đang thực sự dùng hôm nay (khi để AUTO)
    val themeModes: List<ThemeModeDto>,
    val heightOptions: List<Int>,
    val audioFx: String,                  // OFF | STUDIO | HALL | LIVE | BASS | VOCAL
    val audioFxLevel: Int,                // cường độ hiệu ứng 0..100 (%)
    val audioFxModes: List<AudioFxModeDto>,
    val guestCanSkip: Boolean = true      // khách được qua bài của người khác (chủ phòng luôn được)
)

fun SettingsStore.toLiveDto() = LiveSettingsDto(
    leftIsVocal = leftIsVocal,
    fairQueue = fairQueue,
    youtubeMaxHeight = youtubeMaxHeight,
    stageTheme = stageThemeMode,
    stageThemeNow = StageTheme.resolve(stageThemeMode).label,
    themeModes = StageTheme.MODES.map { ThemeModeDto(it.first, it.second) },
    heightOptions = SettingsStore.YT_HEIGHTS,
    audioFx = audioFx,
    audioFxLevel = audioFxLevel,
    audioFxModes = StudioPreset.ALL.map { AudioFxModeDto(it.key, it.label, it.note) },
    guestCanSkip = guestCanSkip
)

data class ScanProgress(val scanning: Boolean = false, val scanned: Int = 0, val found: Int = 0, val total: Int = 0)

data class ServerInfo(val ip: String, val port: Int) {
    val url: String get() = "http://$ip:$port"
}

class ApiException(val code: String, message: String, val http: Int = 400, cause: Throwable? = null) : Exception(message, cause)

fun SongEntity.toDto() =
    if (isYoutube) SongDto(id, code, title, artist, extension, durationMs, "YOUTUBE", youtubeId)
    else SongDto(id, code, title, artist, extension, durationMs)
fun QueueEntry.toDto(position: Int) = QueueItemDto(itemId, position, addedBy, addedAt, song.toDto())
fun QueueState.toDtos(): List<QueueItemDto> = items.mapIndexed { i, e -> e.toDto(i) }
