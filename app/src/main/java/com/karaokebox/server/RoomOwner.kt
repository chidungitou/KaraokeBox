package com.karaokebox.server

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class RoomState(val ownerId: String? = null, val ownerName: String = "")

/**
 * Chủ phòng = điện thoại đầu tiên kết nối khi phòng chưa có chủ (TV đang hiện QR to). Không dùng mã PIN:
 * điện thoại được nhận lại bằng mã định danh (clientId) lưu trong trình duyệt, nên thoát Zalo rồi quét lại
 * (QR to hay QR nhỏ đều được) vẫn là chủ phòng.
 *
 * Hết hiệu lực khi: (1) thoát app trên box, (2) "Đặt lại chủ phòng" trên TV, (3) chủ phòng vắng quá [OWNER_IDLE_MS].
 * Chỉ giữ trong RAM nên mỗi lần mở lại app là một buổi hát mới.
 */
class RoomOwner {
    companion object {
        /** Chủ phòng không có hoạt động nào quá ngần này thì nhường cho người kết nối tiếp theo. */
        const val OWNER_IDLE_MS = 3 * 60 * 60_000L
    }

    private val lock = Any()
    private val _state = MutableStateFlow(RoomState())
    val state: StateFlow<RoomState> = _state
    private var lastActive = 0L

    /** Sau "Đặt lại chủ phòng": các máy đang trong phòng lúc đó không tự nhận lại quyền (chỉ máy mới quét mới được). */
    private val barred = HashSet<String>()

    fun isOwner(id: String): Boolean = id.isNotEmpty() && _state.value.ownerId == id

    /** Gọi mỗi khi một điện thoại có hoạt động. Trả về true nếu điện thoại này VỪA trở thành chủ phòng. */
    fun onActivity(id: String, name: String): Boolean {
        if (id.isEmpty() || id == "unknown") return false
        return synchronized(lock) {
            val now = System.currentTimeMillis()
            var cur = _state.value
            val n = name.trim()
            if (cur.ownerId == id) {
                lastActive = now
                if (n.isNotEmpty() && n != cur.ownerName) _state.value = cur.copy(ownerName = n)
                return@synchronized false
            }
            if (cur.ownerId != null && now - lastActive > OWNER_IDLE_MS) {   // chủ phòng vắng quá lâu → nhường
                _state.value = RoomState()
                cur = _state.value
            }
            if (cur.ownerId == null && id !in barred) {
                barred.clear()
                lastActive = now
                _state.value = RoomState(id, n)
                true
            } else {
                false
            }
        }
    }

    /** Bỏ chủ phòng. [barIds] = các máy đang trong phòng, không được tự nhận chủ phòng lại (để người mới quét QR nhận). */
    fun reset(barIds: Collection<String> = emptyList()) {
        synchronized(lock) {
            barred.clear()
            barred.addAll(barIds)
            lastActive = 0L
            _state.value = RoomState()
        }
    }
}
