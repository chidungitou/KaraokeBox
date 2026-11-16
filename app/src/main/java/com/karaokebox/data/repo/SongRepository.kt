package com.karaokebox.data.repo

import androidx.sqlite.db.SimpleSQLiteQuery
import com.karaokebox.core.text.VietnameseNormalizer
import com.karaokebox.data.db.SongDao
import com.karaokebox.data.db.SongEntity
import com.karaokebox.data.yt.YT_EXT
import com.karaokebox.data.yt.YT_PREFIX
import com.karaokebox.data.yt.YtVideo

class SongRepository(private val dao: SongDao) {
    /**
     * Trả về (tổng số kết quả, trang kết quả). Tự chuẩn hóa chuỗi tìm kiếm (không dấu, chữ thường).
     * Mỗi TỪ KHÓA phải khớp đầu một từ trong tên bài HOẶC tên ca sĩ, nên gõ được:
     * "lac troi" (tên bài), "son tung" (ca sĩ), "lac troi son tung" (cả hai), "lt" (chữ cái đầu), "12345" (mã số).
     */
    suspend fun search(raw: String, limit: Int, offset: Int): Pair<Int, List<SongEntity>> {
        val q = VietnameseNormalizer.normalize(raw)
        val tokens = q.split(' ').filter { it.isNotEmpty() }.take(6)
        val args = ArrayList<Any?>()
        val where = StringBuilder("extension != 'yt'")
        if (tokens.isNotEmpty()) {
            val initials = tokens.joinToString("")
            where.append(" AND (code = ?")
            args.add(q)
            where.append(" OR (")
            tokens.forEachIndexed { i, t ->
                if (i > 0) where.append(" AND ")
                where.append("(' ' || titleNorm || ' ' || artistNorm) LIKE ?")
                args.add("% $t%")
            }
            where.append(") OR titleInitials LIKE ? OR artistInitials LIKE ?)")
            args.add("$initials%")
            args.add("$initials%")
        }

        val countQ = SimpleSQLiteQuery("SELECT COUNT(*) FROM songs WHERE $where", args.toTypedArray())

        val order = StringBuilder("ORDER BY ")
        val orderArgs = ArrayList<Any?>()
        if (tokens.isNotEmpty()) {
            val initials = tokens.joinToString("")
            order.append(
                "CASE WHEN code = ? THEN 0 " +
                    "WHEN titleNorm = ? THEN 1 " +
                    "WHEN titleNorm LIKE ? THEN 2 " +
                    "WHEN artistNorm = ? THEN 3 " +
                    "WHEN artistNorm LIKE ? THEN 4 " +
                    "WHEN titleInitials = ? THEN 5 " +
                    "WHEN titleInitials LIKE ? THEN 6 " +
                    "ELSE 7 END, "
            )
            orderArgs.addAll(listOf(q, q, "$q%", q, "$q%", initials, "$initials%"))
        }
        order.append("playCount DESC, titleNorm ASC LIMIT ? OFFSET ?")
        orderArgs.add(limit.coerceIn(1, 100))
        orderArgs.add(offset.coerceAtLeast(0))

        val listQ = SimpleSQLiteQuery("SELECT * FROM songs WHERE $where $order", (args + orderArgs).toTypedArray())
        return dao.searchCountRaw(countQ) to dao.searchRaw(listQ)
    }

    /** Lưu (hoặc lấy lại) bản ghi cho video YouTube để dùng chung hàng đợi với bài trong máy. */
    suspend fun upsertYoutube(v: YtVideo): SongEntity {
        val path = YT_PREFIX + v.videoId
        val tn = VietnameseNormalizer.normalize(v.title)
        val an = VietnameseNormalizer.normalize(v.channel)
        dao.findByPath(path)?.let { old ->
            if (old.title == v.title && old.artist == v.channel) return old
            // cập nhật tiêu đề (vd: bản ghi cũ bị lỗi font) mà vẫn giữ nguyên id
            val fixed = old.copy(
                title = v.title, artist = v.channel, titleNorm = tn, artistNorm = an,
                titleInitials = VietnameseNormalizer.initials(tn), artistInitials = VietnameseNormalizer.initials(an),
                fileName = v.title, durationMs = v.durationMs
            )
            dao.upsertAll(listOf(fixed))
            return fixed
        }
        dao.insertIgnore(
            SongEntity(
                code = null, title = v.title, artist = v.channel,
                titleNorm = tn, artistNorm = an,
                titleInitials = VietnameseNormalizer.initials(tn),
                artistInitials = VietnameseNormalizer.initials(an),
                filePath = path, fileName = v.title, extension = YT_EXT,
                fileSize = 0, lastModified = 0, folderId = 0, durationMs = v.durationMs
            )
        )
        return dao.findByPath(path)!!
    }

    suspend fun getById(id: Long) = dao.getById(id)
    suspend fun count() = dao.count()
    suspend fun markPlayed(id: Long) = dao.markPlayed(id, System.currentTimeMillis())
    suspend fun updateAudioMode(id: Long, mode: String) = dao.updateAudioMode(id, mode)
    suspend fun updateDuration(id: Long, d: Long) = dao.updateDuration(id, d)
}
