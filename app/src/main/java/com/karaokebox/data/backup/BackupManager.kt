package com.karaokebox.data.backup

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.karaokebox.ServiceLocator
import com.karaokebox.core.text.TitleCleaner
import com.karaokebox.data.yt.YT_PREFIX
import com.karaokebox.data.yt.YoutubeRepository
import com.karaokebox.data.yt.YtVideo

/**
 * Sao lưu / khôi phục bài Yêu thích của MỘT điện thoại. File sao lưu được tải về chính điện thoại đó (không nằm trong
 * app của box) nên còn nguyên khi gỡ cài lại app hay đổi box. Khôi phục là GỘP: chỉ thêm bài, không xóa bài đang có.
 *
 * Bài trong máy lưu theo đường dẫn file (vẫn khớp sau khi quét lại thư viện); bài YouTube lưu thêm tên, kênh, độ dài
 * để dựng lại bản ghi bài hát mà không cần hỏi YouTube.
 */
object BackupManager {
    const val FORMAT = 1
    const val MAX_FAVORITES = 5000

    /** Các cài đặt "chỉnh lúc hát" được sao lưu (cùng tên khóa với API /api/settings). Thư mục nhạc, cổng... là cài đặt quản trị nên không có. */
    val SETTING_KEYS = listOf("leftIsVocal", "fairQueue", "guestCanSkip", "youtubeMaxHeight", "stageTheme", "audioFx", "audioFxLevel")

    data class Item(val path: String, val title: String, val artist: String, val durationMs: Long, val addedAt: Long)

    /** added: thêm mới; existed: đã có sẵn; skipped: mục lỗi bị bỏ qua; notInLibrary: bài trong máy chưa thấy trong thư viện (hiện lại sau khi quét). */
    data class Result(val added: Int, val existed: Int, val skipped: Int, val notInLibrary: Int)

    /** Gồm cả bài chưa có trong thư viện lúc này (vd vừa cài lại, chưa quét), khác với danh sách hiển thị. Mới thêm xếp trước. */
    suspend fun exportFavorites(clientId: String): List<Item> {
        val songDao = ServiceLocator.db.songDao()
        return ServiceLocator.favorites.entries(clientId).map { e ->
            val s = songDao.findByPath(e.filePath)
            Item(e.filePath, s?.title.orEmpty(), s?.artist.orEmpty(), s?.durationMs ?: 0L, e.addedAt)
        }
    }

    suspend fun importFavorites(clientId: String, arr: JsonArray): Result {
        val sl = ServiceLocator
        val songDao = sl.db.songDao()
        val now = System.currentTimeMillis()
        var added = 0
        var existed = 0
        var skipped = 0
        var notInLibrary = 0
        for (el in arr) {
            val o = if (el.isJsonObject) el.asJsonObject else null
            val path = o?.str("path")?.trim().orEmpty()
            if (o == null || path.isEmpty() || path.length > 1024 || path.indexOf('\u0000') >= 0) { skipped++; continue }
            val addedAt = o.long("addedAt").let { if (it <= 0L || it > now) now else it }

            if (path.startsWith(YT_PREFIX)) {
                val vid = path.removePrefix(YT_PREFIX)
                if (!YoutubeRepository.VIDEO_ID_VALID.matches(vid)) { skipped++; continue }
                val title = o.str("title").trim().ifBlank { "Video YouTube" }.take(200).let { TitleCleaner.clean(it) }
                val channel = o.str("artist").trim().ifBlank { "YouTube" }.take(100)
                val dur = o.long("durationMs").coerceIn(0L, 6L * 3600 * 1000)
                sl.songRepo.upsertYoutube(YtVideo(vid, title, channel, dur))   // dựng lại bản ghi để bài hiện ngay trong Yêu thích
            } else if (songDao.findByPath(path) == null) {
                notInLibrary++   // vẫn lưu: bài sẽ tự hiện khi thư viện quét thấy đúng file này
            }
            if (sl.favorites.addPath(clientId, path, addedAt)) added++ else existed++
        }
        sl.favorites.refreshPinned()   // bài YouTube mới vào Yêu thích được ưu tiên giữ trong cache
        return Result(added, existed, skipped, notInLibrary)
    }

    private fun JsonObject.str(k: String): String = prim(k)?.let { try { it.asString } catch (e: Exception) { null } }.orEmpty()
    private fun JsonObject.long(k: String): Long = prim(k)?.let { try { it.asLong } catch (e: Exception) { null } } ?: 0L
    private fun JsonObject.prim(k: String): JsonElement? = get(k)?.takeIf { it.isJsonPrimitive }
}
