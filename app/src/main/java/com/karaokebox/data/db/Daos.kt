package com.karaokebox.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.sqlite.db.SupportSQLiteQuery

@Dao
interface SongDao {
    /** Câu lệnh tìm kiếm được dựng động (nhiều từ khóa) trong SongRepository. */
    @RawQuery
    suspend fun searchRaw(query: SupportSQLiteQuery): List<SongEntity>

    @RawQuery
    suspend fun searchCountRaw(query: SupportSQLiteQuery): Int

    /** Chỉ đếm bài trong máy (không tính bài YouTube đã thêm). */
    @Query("SELECT COUNT(*) FROM songs WHERE extension != 'yt'")
    suspend fun count(): Int

    @Query("SELECT * FROM songs WHERE filePath = :path LIMIT 1")
    suspend fun findByPath(path: String): SongEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(song: SongEntity): Long

    @Query("SELECT id, filePath, fileSize, lastModified FROM songs WHERE folderId = :folderId")
    suspend fun fingerprints(folderId: Long): List<SongFingerprint>

    @Query("SELECT COUNT(*) FROM songs WHERE folderId = :folderId")
    suspend fun countInFolder(folderId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(songs: List<SongEntity>)

    @Query("DELETE FROM songs WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM songs WHERE folderId = :folderId")
    suspend fun deleteByFolder(folderId: Long)

    @Query("SELECT * FROM songs WHERE id = :id")
    suspend fun getById(id: Long): SongEntity?

    /** Đường dẫn ("yt:<videoId>") các bài YouTube đã hát từ [minPlays] lần trở lên: được ưu tiên giữ trong cache. */
    @Query("SELECT filePath FROM songs WHERE extension = 'yt' AND playCount >= :minPlays")
    suspend fun frequentYoutubePaths(minPlays: Int): List<String>

    @Query("UPDATE songs SET playCount = playCount + 1, lastPlayedAt = :t WHERE id = :id")
    suspend fun markPlayed(id: Long, t: Long)

    @Query("UPDATE songs SET audioMode = :mode WHERE id = :id")
    suspend fun updateAudioMode(id: Long, mode: String)

    @Query("UPDATE songs SET durationMs = :d WHERE id = :id")
    suspend fun updateDuration(id: Long, d: Long)
}

@Dao
interface FolderDao {
    @Query("SELECT * FROM music_folders ORDER BY id")
    suspend fun getAll(): List<MusicFolderEntity>

    @Query("SELECT COUNT(*) FROM music_folders")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(folder: MusicFolderEntity): Long

    @Query("DELETE FROM music_folders WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE music_folders SET lastScanAt = :t, songCount = :n WHERE id = :id")
    suspend fun updateStats(id: Long, t: Long, n: Int)
}

@Dao
abstract class QueueDao {
    @Query("SELECT * FROM queue_items ORDER BY position")
    abstract suspend fun getAll(): List<QueueItemEntity>

    @Query("DELETE FROM queue_items")
    abstract suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAll(items: List<QueueItemEntity>)

    @Query("SELECT COALESCE(MAX(id), 0) FROM queue_items")
    abstract suspend fun maxId(): Long

    @Transaction
    open suspend fun replaceAll(items: List<QueueItemEntity>) {
        clear()
        insertAll(items)
    }
}

@Dao
interface HistoryDao {
    @Insert
    suspend fun insert(h: PlayHistoryEntity)
}

@Dao
interface FavoriteDao {
    /** Bài yêu thích mới thêm xếp trên cùng. Bài đã bị xóa khỏi thư viện sẽ tự không hiện (JOIN). */
    @Query("SELECT s.* FROM songs s INNER JOIN favorites f ON f.filePath = s.filePath WHERE f.clientId = :clientId ORDER BY f.addedAt DESC")
    suspend fun listSongs(clientId: String): List<SongEntity>

    /** Mọi bài YouTube nằm trong Yêu thích của bất kỳ điện thoại nào (để ưu tiên giữ trong cache). */
    @Query("SELECT DISTINCT filePath FROM favorites WHERE filePath LIKE 'yt:%'")
    suspend fun youtubePaths(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(f: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE clientId = :clientId AND filePath = :path")
    suspend fun remove(clientId: String, path: String)

    /** Chuyển yêu thích cũ (clientId = "") cho điện thoại này; bài trùng sẵn có thì bỏ qua rồi xóa bản cũ. */
    @Query("UPDATE OR IGNORE favorites SET clientId = :clientId WHERE clientId = ''")
    suspend fun adoptLegacy(clientId: String)

    @Query("DELETE FROM favorites WHERE clientId = ''")
    suspend fun dropLegacy()
}
