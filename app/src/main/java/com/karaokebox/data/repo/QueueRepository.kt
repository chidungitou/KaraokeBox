package com.karaokebox.data.repo

import com.karaokebox.data.db.QueueDao
import com.karaokebox.data.db.QueueItemEntity
import com.karaokebox.data.db.SongDao
import com.karaokebox.data.db.SongEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * [ownerId] = mã điện thoại đã thêm bài (để xếp hàng công bằng giữa các máy; hai máy trùng tên hiển thị vẫn là hai người).
 * Không lưu xuống DB: hàng chờ khôi phục sau khi mở lại app sẽ dùng tên hiển thị làm mã.
 */
data class QueueEntry(val itemId: Long, val song: SongEntity, val addedBy: String, val addedAt: Long, val ownerId: String = addedBy)
data class QueueState(val version: Long = 0, val items: List<QueueEntry> = emptyList())

/** Hàng đợi giữ trong RAM (StateFlow), ghi xuống DB sau mỗi thay đổi để khôi phục khi mở lại. */
class QueueRepository(private val dao: QueueDao, private val songDao: SongDao) {
    private val mutex = Mutex()
    private val ids = AtomicLong(0)
    private val _state = MutableStateFlow(QueueState())
    val state: StateFlow<QueueState> = _state

    suspend fun load() = mutex.withLock {
        withContext(Dispatchers.IO) {
            ids.set(dao.maxId())
            val list = dao.getAll().mapNotNull { q ->
                songDao.getById(q.songId)?.let { QueueEntry(q.id, it, q.addedBy, q.addedAt) }
            }
            _state.value = QueueState(1, list)
        }
    }

    private suspend fun commit(items: List<QueueEntry>) {
        _state.value = QueueState(_state.value.version + 1, items)
        withContext(Dispatchers.IO) {
            dao.replaceAll(items.mapIndexed { i, e -> QueueItemEntity(e.itemId, e.song.id, i, e.addedBy, e.addedAt) })
        }
    }

    /**
     * priority = chen lên đầu. Ngược lại: nếu [fair] thì xếp xen kẽ theo người thêm (xem [fairIndex]), không thì cuối hàng.
     * [nowPlayingOwner] = chủ của bài đang hát (bài đó cũng được tính là một lượt của họ).
     */
    suspend fun add(
        song: SongEntity, by: String, priority: Boolean,
        ownerId: String = by, fair: Boolean = false, nowPlayingOwner: String? = null
    ): QueueEntry = mutex.withLock {
        val e = QueueEntry(ids.incrementAndGet(), song, by, System.currentTimeMillis(), ownerId)
        val cur = _state.value.items
        commit(
            when {
                priority -> listOf(e) + cur
                fair -> cur.toMutableList().also { it.add(fairIndex(cur, ownerId, nowPlayingOwner), e) }
                else -> cur + e
            }
        )
        e
    }

    /**
     * Xếp hàng công bằng theo "vòng": bài thứ k đang chờ của mỗi người thuộc vòng k (bài đang hát tính là vòng 0 của chủ nó).
     * Bài mới chèn ngay TRƯỚC bài đầu tiên có vòng lớn hơn vòng của nó, nên trong cùng một vòng ai thêm trước hát trước.
     * VD: A thêm a1 a2 a3, rồi B thêm b1 b2 => a1 b1 a2 b2 a3.
     */
    private fun fairIndex(cur: List<QueueEntry>, owner: String, nowPlayingOwner: String?): Int {
        // vòng của bài mới = số lượt người này đã có (bài đang hát + các bài đang chờ)
        var newRound = if (owner == nowPlayingOwner) 1 else 0
        for (e in cur) if (e.ownerId == owner) newRound++
        // duyệt hàng chờ, tính vòng của từng bài hiện có theo cùng cách
        val counts = HashMap<String, Int>()
        if (nowPlayingOwner != null) counts[nowPlayingOwner] = 1
        for ((i, e) in cur.withIndex()) {
            val r = counts[e.ownerId] ?: 0
            if (r > newRound) return i
            counts[e.ownerId] = r + 1
        }
        return cur.size
    }

    /** Đưa lại một bài vào hàng chờ ở vị trí [index] (dùng khi bài bị ngắt vì mất mạng). */
    suspend fun insertAt(entry: QueueEntry, index: Int): QueueEntry = mutex.withLock {
        val e = entry.copy(itemId = ids.incrementAndGet())
        val list = _state.value.items.toMutableList()
        list.add(index.coerceIn(0, list.size), e)
        commit(list)
        e
    }

    suspend fun remove(itemId: Long): Boolean = mutex.withLock {
        val cur = _state.value.items
        if (cur.none { it.itemId == itemId }) return@withLock false
        commit(cur.filter { it.itemId != itemId })
        true
    }

    suspend fun moveToTop(itemId: Long): Boolean = mutex.withLock {
        val cur = _state.value.items
        val e = cur.firstOrNull { it.itemId == itemId } ?: return@withLock false
        commit(listOf(e) + cur.filter { it.itemId != itemId })
        true
    }

    /** toIndex = vị trí trong danh sách SAU khi đã gỡ mục ra. */
    suspend fun move(itemId: Long, toIndex: Int): Boolean = mutex.withLock {
        val cur = _state.value.items
        val e = cur.firstOrNull { it.itemId == itemId } ?: return@withLock false
        val rest = cur.filter { it.itemId != itemId }.toMutableList()
        rest.add(toIndex.coerceIn(0, rest.size), e)
        commit(rest)
        true
    }

    suspend fun clear() = mutex.withLock { commit(emptyList()) }

    suspend fun popFirst(): QueueEntry? = mutex.withLock {
        val cur = _state.value.items
        val first = cur.firstOrNull() ?: return@withLock null
        commit(cur.drop(1))
        first
    }
}
