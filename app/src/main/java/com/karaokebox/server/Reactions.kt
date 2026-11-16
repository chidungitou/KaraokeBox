package com.karaokebox.server

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Nút cảm xúc trên điện thoại (tim, pháo, vỗ tay, quẩy) → hiệu ứng bay lên màn hình TV.
 * Có hai lớp giới hạn để box yếu không bị lag khi nhiều người bấm dồn:
 *  - mỗi điện thoại: tối thiểu [PER_CLIENT_MS] giữa hai lần bấm;
 *  - cả phòng: tối đa [GLOBAL_MAX] hiệu ứng mỗi giây (phần dư bị bỏ lặng lẽ).
 * Màn hình TV còn giới hạn riêng số hiệu ứng hiện cùng lúc (xem ReactionView).
 */
object Reactions {
    const val PER_CLIENT_MS = 250L
    const val GLOBAL_MAX = 8
    val KINDS = setOf("heart", "firework", "clap", "party")

    private val _flow = MutableSharedFlow<String>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val flow: SharedFlow<String> = _flow

    private val lastByClient = ConcurrentHashMap<String, Long>()
    private val lock = Any()
    private var windowStart = 0L
    private var windowCount = 0

    /** true nếu hiệu ứng được gửi lên TV, false nếu bị giới hạn tốc độ bỏ qua. */
    fun send(clientId: String, kind: String): Boolean {
        val now = System.currentTimeMillis()
        val last = lastByClient[clientId] ?: 0L
        if (now - last < PER_CLIENT_MS) return false
        synchronized(lock) {
            if (now - windowStart >= 1000L) { windowStart = now; windowCount = 0 }
            if (windowCount >= GLOBAL_MAX) return false
            windowCount++
        }
        lastByClient[clientId] = now
        if (lastByClient.size > 200) lastByClient.entries.removeAll { now - it.value > 60_000L }
        return _flow.tryEmit(kind)
    }
}
