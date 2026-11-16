package com.karaokebox.data.scan

import com.karaokebox.core.text.VietnameseNormalizer
import com.karaokebox.data.db.AppDatabase
import com.karaokebox.data.db.SongEntity
import com.karaokebox.data.repo.SettingsStore
import com.karaokebox.server.ScanProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Quét tăng dần: chỉ ghi file mới/đổi, xóa bản ghi của file không còn tồn tại. */
class LibraryScanner(private val db: AppDatabase, private val settings: SettingsStore) {
    companion object {
        val EXTS = setOf("mkv", "mp4", "vob", "avi", "mpg", "mpeg", "ts", "m2ts", "dat")
        private const val BATCH = 500
    }

    private val running = AtomicBoolean(false)
    private val _progress = MutableStateFlow(ScanProgress())
    val progress: StateFlow<ScanProgress> = _progress
    val isRunning get() = running.get()

    /** @return false nếu đang có một lượt quét khác. */
    suspend fun scanAll(): Boolean = withContext(Dispatchers.IO) {
        if (!running.compareAndSet(false, true)) return@withContext false
        try {
            _progress.value = ScanProgress(scanning = true)
            var scanned = 0
            var found = 0
            val swap = settings.swapTitleArtist
            val songDao = db.songDao()

            for (folder in db.folderDao().getAll().filter { it.enabled }) {
                val root = File(folder.path)
                if (!root.isDirectory) continue
                val known = songDao.fingerprints(folder.id).associateBy { it.filePath }
                val seen = HashSet<String>()
                val batch = ArrayList<SongEntity>()

                for (f in root.walkTopDown().onFail { _, _ -> }) {
                    if (!f.isFile || f.name.startsWith(".")) continue
                    val ext = FileNameParser.ext(f)
                    if (ext !in EXTS) continue
                    scanned++
                    seen.add(f.path)
                    val old = known[f.path]
                    val size = f.length()
                    val mod = f.lastModified()
                    if (old == null || old.fileSize != size || old.lastModified != mod) {
                        batch.add(build(f, folder.id, old?.id ?: 0L, size, mod, ext, swap))
                        found++
                        if (batch.size >= BATCH) {
                            songDao.upsertAll(batch.toList())
                            batch.clear()
                        }
                    }
                    if (scanned % 100 == 0) _progress.value = ScanProgress(true, scanned, found, 0)
                }
                if (batch.isNotEmpty()) songDao.upsertAll(batch.toList())

                val gone = known.values.filter { it.filePath !in seen }.map { it.id }
                gone.chunked(500).forEach { songDao.deleteByIds(it) }
                db.folderDao().updateStats(folder.id, System.currentTimeMillis(), songDao.countInFolder(folder.id))
            }
            _progress.value = ScanProgress(false, scanned, found, scanned)
            true
        } finally {
            running.set(false)
            _progress.value = _progress.value.copy(scanning = false)
        }
    }

    private fun build(f: File, folderId: Long, id: Long, size: Long, mod: Long, ext: String, swap: Boolean): SongEntity {
        val p = FileNameParser.parse(f.name, swap)
        val tn = VietnameseNormalizer.normalize(p.title)
        val an = VietnameseNormalizer.normalize(p.artist)
        return SongEntity(
            id = id, code = p.code, title = p.title, artist = p.artist,
            titleNorm = tn, artistNorm = an,
            titleInitials = VietnameseNormalizer.initials(tn),
            artistInitials = VietnameseNormalizer.initials(an),
            filePath = f.path, fileName = f.name, extension = ext,
            fileSize = size, lastModified = mod, folderId = folderId
        )
    }
}
