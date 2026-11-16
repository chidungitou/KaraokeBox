package com.karaokebox.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "songs",
    indices = [
        Index(value = ["filePath"], unique = true),
        Index(value = ["titleNorm"]),
        Index(value = ["artistNorm"]),
        Index(value = ["titleInitials"]),
        Index(value = ["code"]),
        Index(value = ["folderId"])
    ]
)
data class SongEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val code: String?,
    val title: String,
    val artist: String,
    val titleNorm: String,
    val artistNorm: String,
    val titleInitials: String,
    val artistInitials: String,
    val filePath: String,
    val fileName: String,
    val extension: String,
    val fileSize: Long,
    val lastModified: Long,
    val folderId: Long,
    /** AUTO | SWAPPED  (SWAPPED = đảo kênh/track ca sĩ <-> beat cho riêng bài này) */
    val audioMode: String = "AUTO",
    val vocalTrackIndex: Int = -1,
    val beatTrackIndex: Int = -1,
    val durationMs: Long = 0,
    val playCount: Int = 0,
    val lastPlayedAt: Long = 0,
    val addedAt: Long = System.currentTimeMillis()
)

data class SongFingerprint(val id: Long, val filePath: String, val fileSize: Long, val lastModified: Long)

@Entity(tableName = "music_folders", indices = [Index(value = ["path"], unique = true)])
data class MusicFolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val path: String,
    val enabled: Boolean = true,
    val lastScanAt: Long = 0,
    val songCount: Int = 0
)

@Entity(tableName = "queue_items", indices = [Index(value = ["position"]), Index(value = ["songId"])])
data class QueueItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: Long,
    val position: Int,
    val addedBy: String,
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "play_history", indices = [Index(value = ["songId"]), Index(value = ["playedAt"])])
data class PlayHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: Long,
    val playedAt: Long = System.currentTimeMillis(),
    val completed: Boolean = false
)

/**
 * Bài hát yêu thích, RIÊNG TỪNG ĐIỆN THOẠI. Khóa kép (clientId, filePath): clientId là mã định danh
 * điện thoại (web app tự tạo, gửi qua header X-Client-Id), filePath là đường dẫn file (hoặc "yt:<videoId>")
 * nên vẫn còn nguyên khi quét lại thư viện.
 * clientId = "" là yêu thích cũ từ trước khi có tính năng này (sẽ được điện thoại kết nối đầu tiên nhận lại).
 */
@Entity(tableName = "favorites", primaryKeys = ["clientId", "filePath"])
data class FavoriteEntity(
    val clientId: String,
    val filePath: String,
    val addedAt: Long = System.currentTimeMillis()
)
