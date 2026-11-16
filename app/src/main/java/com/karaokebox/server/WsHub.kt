package com.karaokebox.server

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.karaokebox.data.repo.QueueRepository
import com.karaokebox.data.repo.SettingsStore
import com.karaokebox.data.scan.LibraryScanner
import com.karaokebox.player.PlaybackController
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/** Quản lý các kết nối WebSocket và phát (broadcast) trạng thái thời gian thực. */
class WsHub(
    private val controller: PlaybackController,
    private val queue: QueueRepository,
    private val scanner: LibraryScanner,
    private val settings: SettingsStore,
    private val room: RoomOwner
) {
    private companion object {
        /** Cùng một điện thoại chỉ được chào lại sau ngần này thời gian (nối lại WebSocket không tính). */
        const val JOIN_GREET_COOLDOWN_MS = 30 * 60_000L
        /**
         * Điện thoại KHÔNG còn socket nhưng vẫn gọi HTTP (trang đang mở, đang chờ nối lại WebSocket, poll mỗi 3 giây)
         * thì còn được coi là "trong phòng" thêm ngần này thời gian. Rất ngắn để thoát Zalo/đóng trang là TV biết ngay.
         */
        const val PRESENCE_TTL_MS = 8_000L
        /** Socket im lặng quá ngần này thì đóng (web ping mỗi 15 giây → 2 lần mất ping là coi như đã đi). */
        const val SOCKET_TIMEOUT_MS = 35_000L
    }

    private val gson = Gson()
    private val clients = CopyOnWriteArraySet<Client>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _clientCount = MutableStateFlow(0)
    /** Số điện thoại đang kết nối WebSocket (PlayerActivity dùng để chuyển màn hình QR / màn hình chờ). */
    val clientCount: StateFlow<Int> = _clientCount

    private val _joins = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Tên điện thoại vừa vào phòng (PlayerActivity dùng để hiện lời chào + vòng sáng quanh QR). */
    val joins: SharedFlow<String> = _joins

    /** clientId -> lần chào gần nhất. Điện thoại tắt/mở màn hình rồi nối lại WebSocket thì KHÔNG chào lại. */
    private val greetedAt = ConcurrentHashMap<String, Long>()

    private fun announceJoin(c: Client) {
        val id = c.clientId
        if (id.isEmpty()) return
        val now = System.currentTimeMillis()
        if (now - (greetedAt[id] ?: 0L) < JOIN_GREET_COOLDOWN_MS) return
        greetedAt[id] = now
        if (greetedAt.size > 200) greetedAt.entries.removeAll { now - it.value > JOIN_GREET_COOLDOWN_MS }
        _joins.tryEmit(c.name.trim().ifEmpty { "Khách" }.take(30))
    }

    /** clientId -> lần hoạt động gần nhất. Tách khỏi WebSocket: điện thoại tắt màn hình (socket đứt) vẫn còn "trong phòng". */
    private val presence = ConcurrentHashMap<String, Long>()

    /** Đánh dấu điện thoại này vừa hoạt động (HTTP, ping, hello...). Phòng chưa có chủ thì điện thoại đầu tiên thành chủ phòng. */
    fun touch(clientId: String, name: String = "") {
        if (clientId.isEmpty()) return
        val claimed = room.onActivity(clientId, name)
        val isNew = presence.put(clientId, System.currentTimeMillis()) == null
        if (isNew) refreshCount()
        if (claimed) controller.announce("${name.trim().ifEmpty { "Khách" }} là chủ phòng")
    }

    /** Các điện thoại đang trong phòng (dùng khi "Đặt lại chủ phòng": các máy này không tự nhận lại quyền). */
    fun presentIds(): Set<String> {
        val ids = HashSet<String>()
        for (c in clients) if (c.clientId.isNotEmpty()) ids.add(c.clientId)
        val now = System.currentTimeMillis()
        for ((id, t) in presence) if (now - t < PRESENCE_TTL_MS) ids.add(id)
        return ids
    }

    /** Báo cho từng điện thoại biết nó có phải chủ phòng không (để hiện biểu tượng và cài đặt cho đúng). */
    private fun pushRole(c: Client) {
        val st = room.state.value
        try {
            c.send(envelope("room", mapOf(
                "isOwner" to room.isOwner(c.clientId),
                "hasOwner" to (st.ownerId != null),
                "ownerName" to st.ownerName
            )))
        } catch (_: Exception) {}
    }

    /** Số người đang "trong phòng" = số clientId còn hạn (hoặc đang có socket) + socket chưa khai báo clientId. */
    private fun refreshCount() {
        val now = System.currentTimeMillis()
        val ids = HashSet<String>()
        var anon = 0
        for (c in clients) { if (c.clientId.isEmpty()) anon++ else ids.add(c.clientId) }
        for ((id, t) in presence) if (now - t < PRESENCE_TTL_MS) ids.add(id)
        _clientCount.value = ids.size + anon
    }

    private fun addClient(c: Client) { clients.add(c); refreshCount() }

    private fun removeClient(c: Client) {
        clients.remove(c)
        // Socket cuối cùng của điện thoại này đã đóng (thoát Zalo, đóng tab, tắt trình duyệt...) → rời phòng NGAY,
        // không chờ hết hạn. Nếu trang vẫn còn mở và nối lại, hello/HTTP kế tiếp sẽ tự đưa nó vào lại.
        val id = c.clientId
        if (id.isNotEmpty() && clients.none { it.clientId == id }) presence.remove(id)
        refreshCount()
    }

    /** Điện thoại báo chủ động đã rời (pagehide trên web). */
    fun leave(clientId: String) {
        if (clientId.isEmpty()) return
        presence.remove(clientId)
        clients.filter { it.clientId == clientId }.forEach {
            clients.remove(it)
            try { it.close(NanoWSD.WebSocketFrame.CloseCode.GoingAway, "bye", false) } catch (_: Exception) {}
        }
        refreshCount()
    }

    fun start() {
        scope.launch { controller.snapshot.collect { broadcast("player.state", it) } }
        scope.launch {
            queue.state.collect { broadcast("queue.updated", mapOf("version" to it.version, "items" to it.toDtos())) }
        }
        scope.launch { controller.toasts.collect { broadcast("toast", it) } }
        scope.launch { room.state.collect { for (c in clients) pushRole(c) } }   // chủ phòng đổi (đặt lại, nhường, nhận mới)
        scope.launch { controller.ytStatus.collect { broadcast("yt.status", it) } }
        scope.launch { scanner.progress.collect { broadcast("library.progress", it) } }
        // Cài đặt đổi (từ điện thoại nào hoặc từ TV) → mọi điện thoại cập nhật bảng Cài đặt ngay
        scope.launch { settings.changes.collect { broadcast("settings.changed", settings.toLiveDto()) } }
        scope.launch {
            while (true) {   // dọn socket chết (im lặng quá SOCKET_TIMEOUT_MS) và người đã rời phòng (quá PRESENCE_TTL_MS)
                delay(1_000)
                val now = System.currentTimeMillis()
                clients.filter { now - it.lastSeen > SOCKET_TIMEOUT_MS }.forEach {
                    removeClient(it)
                    try { it.close(NanoWSD.WebSocketFrame.CloseCode.GoingAway, "timeout", false) } catch (_: Exception) {}
                }
                presence.entries.removeAll { now - it.value >= PRESENCE_TTL_MS }
                refreshCount()
            }
        }
    }

    fun createSocket(handshake: NanoHTTPD.IHTTPSession): NanoWSD.WebSocket = Client(handshake)

    private fun envelope(type: String, payload: Any): String =
        gson.toJson(mapOf("type" to type, "ts" to System.currentTimeMillis(), "payload" to payload))

    fun broadcast(type: String, payload: Any) {
        if (clients.isEmpty()) return
        val text = envelope(type, payload)
        for (c in clients) {
            try { c.send(text) } catch (e: Exception) { removeClient(c) }
        }
    }

    /** Chỉ gửi cho các kết nối WebSocket của một điện thoại (theo clientId). */
    fun sendToClient(clientId: String, type: String, payload: Any) {
        if (clientId.isEmpty()) return
        val targets = clients.filter { it.clientId == clientId }
        if (targets.isEmpty()) return
        val text = envelope(type, payload)
        for (c in targets) {
            try { c.send(text) } catch (e: Exception) { removeClient(c) }
        }
    }

    private fun welcome(): String {
        val q = queue.state.value
        val snap = mapOf("player" to controller.snapshot.value, "queue" to q.toDtos(), "queueVersion" to q.version)
        return envelope("welcome", mapOf("serverTime" to System.currentTimeMillis(), "snapshot" to snap))
    }

    inner class Client(handshake: NanoHTTPD.IHTTPSession) : NanoWSD.WebSocket(handshake) {
        @Volatile var lastSeen = System.currentTimeMillis()
        @Volatile var name: String = ""
        @Volatile var clientId: String = ""

        override fun onOpen() {
            addClient(this)
            try { send(welcome()) } catch (_: IOException) {}
        }

        override fun onClose(code: NanoWSD.WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
            removeClient(this)
        }

        override fun onMessage(message: NanoWSD.WebSocketFrame) {
            lastSeen = System.currentTimeMillis()
            try {
                val obj = JsonParser.parseString(message.textPayload).asJsonObject
                when (obj.get("type")?.asString) {
                    "hello" -> {
                        val p = obj.getAsJsonObject("payload")
                        name = p?.get("name")?.asString ?: ""
                        clientId = (p?.get("clientId")?.asString ?: "")
                            .filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(64)
                        touch(clientId, name)
                        refreshCount()
                        send(welcome())
                        pushRole(this)
                        announceJoin(this)
                    }
                    "bye" -> leave(clientId)
                    "ping" -> { if (clientId.isNotEmpty()) touch(clientId, name); send(envelope("pong", emptyMap<String, Any>())) }
                }
            } catch (_: Exception) {
            }
        }

        override fun onPong(pong: NanoWSD.WebSocketFrame?) { lastSeen = System.currentTimeMillis() }

        override fun onException(exception: IOException?) { removeClient(this) }
    }
}
