package com.karaokebox.data.repo

import com.karaokebox.data.db.FavoriteDao
import com.karaokebox.data.db.FavoriteEntity
import com.karaokebox.data.db.SongDao
import com.karaokebox.data.db.SongEntity
import com.karaokebox.data.yt.YT_PREFIX
import com.karaokebox.player.YtCache
import kotlinx.coroutines.CancellationException

/** Danh sách bài hát yêu thích RIÊNG TỪNG ĐIỆN THOẠI (theo clientId), lưu bền trong database của box. */
class FavoriteRepository(private val dao: FavoriteDao, private val songDao: SongDao) {

    /**
     * Yêu thích cũ (trước khi có tính năng riêng từng người) được giao cho điện thoại đầu tiên kết nối,
     * để người dùng không mất dữ liệu. Chỉ chạy được một lần vì sau đó không còn dòng clientId = "".
     */
    private suspend fun adoptLegacy(clientId: String) {
        if (clientId.isEmpty()) return
        dao.adoptLegacy(clientId)
        dao.dropLegacy()
    }

    suspend fun list(clientId: String): List<SongEntity> {
        adoptLegacy(clientId)
        return dao.listSongs(clientId)
    }

    suspend fun add(clientId: String, song: SongEntity) {
        adoptLegacy(clientId)
        dao.add(FavoriteEntity(clientId, song.filePath))
        if (song.filePath.startsWith(YT_PREFIX)) refreshPinned()
    }

    /** Chỉ bỏ khỏi danh sách yêu thích của điện thoại này; KHÔNG xóa bài khỏi thư viện / ổ đĩa. */
    suspend fun remove(clientId: String, songId: Long) {
        adoptLegacy(clientId)
        songDao.getById(songId)?.let {
            dao.remove(clientId, it.filePath)
            if (it.filePath.startsWith(YT_PREFIX)) refreshPinned()
        }
    }

    /**
     * Cập nhật danh sách bài YouTube cần ưu tiên giữ trong cache khi đầy: bài trong Yêu thích của bất kỳ điện thoại nào
     * và bài đã hát từ [PINNED_MIN_PLAYS] lần trở lên.
     */
    suspend fun refreshPinned() {
        try {
            val ids = HashSet<String>()
            dao.youtubePaths().forEach { ids.add(it.removePrefix(YT_PREFIX)) }
            songDao.frequentYoutubePaths(PINNED_MIN_PLAYS).forEach { ids.add(it.removePrefix(YT_PREFIX)) }
            YtCache.setPinned(ids)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val PINNED_MIN_PLAYS = 3
    }
}
