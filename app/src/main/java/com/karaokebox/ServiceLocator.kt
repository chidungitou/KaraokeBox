package com.karaokebox

import android.app.Application
import com.karaokebox.core.net.NetworkUtils
import com.karaokebox.data.db.AppDatabase
import com.karaokebox.data.repo.FavoriteRepository
import com.karaokebox.data.repo.QueueRepository
import com.karaokebox.data.repo.SettingsStore
import com.karaokebox.data.repo.SongRepository
import com.karaokebox.data.scan.LibraryScanner
import com.karaokebox.data.yt.YoutubeRepository
import com.karaokebox.player.PlaybackController
import com.karaokebox.server.KaraokeServer
import com.karaokebox.server.RoomOwner
import com.karaokebox.server.Router
import com.karaokebox.server.ServerInfo
import com.karaokebox.server.WsHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.IOException

/** Singleton thủ công (không dùng Hilt/Koin để khởi động nhanh trên chip yếu). */
object ServiceLocator {
    lateinit var app: Application; private set
    lateinit var db: AppDatabase; private set
    lateinit var settings: SettingsStore; private set
    lateinit var songRepo: SongRepository; private set
    lateinit var queueRepo: QueueRepository; private set
    lateinit var favorites: FavoriteRepository; private set
    lateinit var scanner: LibraryScanner; private set
    lateinit var youtube: YoutubeRepository; private set
    lateinit var controller: PlaybackController; private set
    lateinit var hub: WsHub; private set
    val room = RoomOwner()
    lateinit var router: Router; private set

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** true khi ai đó đang GIỮ nút "Hiện QR" trên điện thoại → TV hiện QR rõ; thả tay thì mờ lại. */
    val qrHold = MutableStateFlow(false)

    private val _serverInfo = MutableStateFlow<ServerInfo?>(null)
    val serverInfo: StateFlow<ServerInfo?> = _serverInfo

    private var server: KaraokeServer? = null
    private var port = 8080
    private var ipJobStarted = false

    fun init(application: Application) {
        app = application
        db = AppDatabase.create(app)
        settings = SettingsStore(app)
        songRepo = SongRepository(db.songDao())
        queueRepo = QueueRepository(db.queueDao(), db.songDao())
        favorites = FavoriteRepository(db.favoriteDao(), db.songDao())
        scanner = LibraryScanner(db, settings)
        youtube = YoutubeRepository(settings)
        controller = PlaybackController(app, songRepo, queueRepo, db.historyDao(), settings, youtube, appScope)
        hub = WsHub(controller, queueRepo, scanner, settings, room)
        router = Router(app)
        hub.start()
        appScope.launch { queueRepo.load() }
        // Bài Yêu thích / hát nhiều được ưu tiên giữ trong cache YouTube; cập nhật lúc khởi động và mỗi 10 phút
        appScope.launch(Dispatchers.IO) {
            while (true) {
                favorites.refreshPinned()
                delay(10 * 60_000L)
            }
        }
    }

    @Synchronized
    fun startServer() {
        if (server != null) return
        val first = settings.preferredPort
        for (p in first..first + 10) {
            try {
                val s = KaraokeServer(p)
                s.start(60_000, false)   // timeout 60s; client WS ping mỗi 15s nên không bị ngắt
                server = s
                port = p
                break
            } catch (e: IOException) {
                // cổng bận → thử cổng kế tiếp
            }
        }
        publishIp()
        if (!ipJobStarted) {
            ipJobStarted = true
            appScope.launch {
                while (true) {
                    delay(10_000)
                    publishIp()
                }
            }
        }
    }

    private fun publishIp() {
        val ip = NetworkUtils.localIp() ?: "127.0.0.1"
        val cur = _serverInfo.value
        if (cur == null || cur.ip != ip || cur.port != port) _serverInfo.value = ServerInfo(ip, port)
    }

    @Synchronized
    fun stopServer() {
        server?.stop()
        server = null
    }
}
