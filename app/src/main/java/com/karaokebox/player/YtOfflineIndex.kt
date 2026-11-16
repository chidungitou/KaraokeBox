package com.karaokebox.player

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.karaokebox.data.yt.YtDiag
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Sổ ghi nhớ: bài YouTube nào đã phát qua cache và dùng khóa cache nào (video + tiếng).
 * Có sổ này, bài đã nằm ĐỦ trong cache được phát thẳng mà không cần lấy link YouTube (không cần Internet,
 * không gửi request nào, nên cũng không bị YouTube chặn). Sổ chỉ là gợi ý: trước khi dùng luôn kiểm tra cache còn đủ dữ liệu.
 */
object YtOfflineIndex {
    data class Entry(val vKey: String, val aKey: String, val height: Int, val at: Long)

    private const val MAX_ENTRIES = 1500
    private val map = ConcurrentHashMap<String, Entry>()
    @Volatile private var loaded = false
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "yt-offline-index").apply { isDaemon = true } }

    private fun file(ctx: Context) = File(ctx.filesDir, "yt_offline.json")

    @Synchronized
    private fun ensureLoaded(ctx: Context) {
        if (loaded) return
        try {
            val f = file(ctx)
            if (f.exists()) {
                val type = object : TypeToken<Map<String, Entry>>() {}.type
                val m: Map<String, Entry>? = Gson().fromJson(f.readText(), type)
                m?.forEach { (k, v) -> map[k] = v }
            }
        } catch (e: Exception) {
            YtDiag.note("Không đọc được sổ bài đã lưu: ${e.javaClass.simpleName}: ${e.message}")
        }
        loaded = true
    }

    fun get(ctx: Context, videoId: String): Entry? {
        ensureLoaded(ctx)
        return map[videoId]
    }

    fun record(ctx: Context, videoId: String, vKey: String, aKey: String, height: Int) {
        ensureLoaded(ctx)
        val old = map[videoId]
        if (old != null && old.vKey == vKey && old.aKey == aKey && old.height == height) return
        map[videoId] = Entry(vKey, aKey, height, System.currentTimeMillis())
        saveAsync(ctx)
    }

    /** Bài này phát từ cache bị lỗi: bỏ khỏi sổ để lần sau đi đường YouTube bình thường (và ghi lại khi phát được). */
    fun forget(ctx: Context, videoId: String) {
        ensureLoaded(ctx)
        if (map.remove(videoId) != null) saveAsync(ctx)
    }

    private fun saveAsync(ctx: Context) {
        val f = file(ctx)
        io.execute {
            try {
                if (map.size > MAX_ENTRIES) {
                    map.entries.sortedBy { it.value.at }.take(map.size - MAX_ENTRIES).forEach { map.remove(it.key) }
                }
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeText(Gson().toJson(HashMap(map)))
                if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            } catch (e: Exception) {
                YtDiag.note("Không ghi được sổ bài đã lưu: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }
}
