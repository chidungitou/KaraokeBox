package com.karaokebox.server

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.karaokebox.ServiceLocator
import com.karaokebox.BuildConfig
import com.karaokebox.core.text.TitleCleaner
import com.karaokebox.data.repo.SettingsStore
import com.karaokebox.data.update.AppInstaller
import com.karaokebox.data.update.AppUpdater
import com.karaokebox.data.yt.YoutubeRepository
import com.karaokebox.data.yt.YtDiag
import com.karaokebox.data.yt.YtVideo
import com.karaokebox.player.YtCache
import com.karaokebox.player.audio.StudioPreset
import com.karaokebox.ui.overlay.StageTheme
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.URLDecoder

/**
 * REST API. Chạy trên luồng của NanoHTTPD; lệnh điều khiển được chuyển sang luồng Main
 * bằng onMain vì ExoPlayer chỉ an toàn trên Main. onMain có thời hạn [MAIN_TIMEOUT_MS]: nếu luồng Main đang kẹt
 * thì request trả lỗi 503 thay vì treo vĩnh viễn một luồng của NanoHTTPD (hết luồng là web remote đứng hẳn).
 */
class Router(private val ctx: Context) {
    private val gson = Gson()
    private val sl = ServiceLocator

    private val reItem = Regex("^/api/queue/(\\d+)$")
    private val reTop = Regex("^/api/queue/(\\d+)/top$")
    private val reFav = Regex("^/api/favorites/(\\d+)$")
    private val reFolderDel = Regex("^/api/library/folder/(\\d+)$")

    fun handle(session: IHTTPSession): Response {
        val uri = session.uri.let { if (it.length > 1) it.trimEnd('/') else it }
        if (!uri.startsWith("/api")) return StaticAssetHandler.serve(ctx, uri)
        return try {
            api(session, uri)
        } catch (e: ApiException) {
            error(e.code, e.message ?: "", e.http)
        } catch (e: Exception) {
            error("INTERNAL", e.message ?: "Lỗi nội bộ", 500)
        }
    }

    private companion object {
        const val MAIN_TIMEOUT_MS = 5_000L
        /** Body JSON của web remote chỉ vài trăm byte; chặn sớm các request lớn bất thường. */
        const val MAX_BODY_BYTES = 64 * 1024L
    }

    /**
     * Chạy [block] trên luồng Main, tối đa [MAIN_TIMEOUT_MS]. withTimeout đặt NGOÀI withContext(Main) để vẫn nổ
     * được cả khi Main bận tới mức block chưa kịp bắt đầu (đặt bên trong thì không bao giờ được chạy).
     */
    private fun <T> onMain(block: suspend () -> T): T = try {
        runBlocking { withTimeout(MAIN_TIMEOUT_MS) { withContext(Dispatchers.Main) { block() } } }
    } catch (e: TimeoutCancellationException) {
        throw ApiException("BUSY", "Box đang bận, thử lại sau giây lát", 503)
    }

    private fun api(s: IHTTPSession, uri: String): Response {
        val m = s.method
        val body: JsonObject = if (m == Method.POST) readBody(s) else JsonObject()
        val by = clientName(s)
        val cid = clientId(s)
        // Mọi request từ web remote đều tính là "còn ở trong phòng" (xem WsHub.touch)
        if (s.headers["x-client-id"].isNullOrBlank().not() && uri != "/api/leave") sl.hub.touch(cid, by)
        val c = sl.controller

        return when {
            m == Method.GET && uri == "/api/info" -> ok(
                mapOf(
                    "appVersion" to BuildConfig.VERSION_NAME, "newpipeVersion" to BuildConfig.NEWPIPE_VERSION, "youtube" to true,
                    "songCount" to runBlocking(Dispatchers.IO) { sl.songRepo.count() },
                    "serverTime" to System.currentTimeMillis(),
                    "ip" to sl.serverInfo.value?.ip, "port" to sl.serverInfo.value?.port
                )
            )
            m == Method.GET && uri == "/api/state" -> {
                val q = sl.queueRepo.state.value
                ok(mapOf("player" to c.snapshot.value, "queue" to q.toDtos(), "queueVersion" to q.version))
            }
            m == Method.GET && uri == "/api/songs" -> {
                val q = s.parameters["q"]?.firstOrNull() ?: ""
                val limit = s.parameters["limit"]?.firstOrNull()?.toIntOrNull() ?: 30
                val offset = s.parameters["offset"]?.firstOrNull()?.toIntOrNull() ?: 0
                val (total, items) = runBlocking(Dispatchers.IO) { sl.songRepo.search(q, limit, offset) }
                ok(mapOf("total" to total, "limit" to limit, "offset" to offset, "items" to items.map { it.toDto() }))
            }
            m == Method.GET && uri == "/api/youtube/search" -> {
                val q = s.parameters["q"]?.firstOrNull()?.trim().orEmpty()
                val cursor = s.parameters["cursor"]?.firstOrNull()?.takeIf { it.isNotBlank() }
                if (cursor == null && q.length < 2) throw ApiException("BAD_REQUEST", "Nhập ít nhất 2 ký tự để tìm trên YouTube")
                val page = runBlocking(Dispatchers.IO) { sl.youtube.search(q.take(100), cursor) }
                ok(mapOf(
                    "cursor" to page.cursor,
                    "items" to page.items.map {
                        mapOf("videoId" to it.videoId, "title" to it.title, "channel" to it.channel, "durationMs" to it.durationMs)
                    }
                ))
            }
            m == Method.GET && uri == "/api/youtube/suggest" -> {
                val q = s.parameters["q"]?.firstOrNull()?.trim().orEmpty().take(100)
                val list = if (q.length < 2) emptyList() else runBlocking(Dispatchers.IO) { sl.youtube.suggest(q) }
                ok(mapOf("items" to list))
            }
            // Sức khỏe YouTube + nhật ký chẩn đoán (xem http://<ip>:8080/api/youtube/diag trên điện thoại)
            m == Method.GET && uri == "/api/youtube/status" -> ok(
                mapOf(
                    "status" to sl.youtube.status.value,
                    "cacheBytes" to YtCache.usedBytes(),
                    "cacheMb" to sl.settings.youtubeCacheMb
                )
            )
            m == Method.POST && uri == "/api/youtube/check" -> ok(mapOf("status" to runBlocking(Dispatchers.IO) { sl.youtube.checkNow() }))
            // Nhật ký có tên bài, lỗi, vết stack: chỉ chủ phòng xem. Mở trực tiếp bằng trình duyệt không gửi được header
            // nên chấp nhận thêm ?cid=<mã máy> (web remote tự gắn vào liên kết "Xem nhật ký lỗi YouTube" trong Cài đặt).
            m == Method.GET && uri == "/api/youtube/diag" -> {
                val qcid = (s.parameters["cid"]?.firstOrNull() ?: "").filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(64)
                if (!sl.room.isOwner(cid) && !sl.room.isOwner(qcid)) throw forbidden("Chỉ chủ phòng xem được nhật ký lỗi")
                text(YtDiag.read())
            }
            m == Method.POST && uri == "/api/youtube/queue" -> {
                val vid = body.get("videoId")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                if (!YoutubeRepository.VIDEO_ID_VALID.matches(vid)) throw ApiException("BAD_REQUEST", "videoId không hợp lệ")
                val title = body.get("title")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty().ifBlank { "Video YouTube" }.take(200).let { TitleCleaner.clean(it) }
                val channel = body.get("channel")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty().ifBlank { "YouTube" }.take(100)
                val dur = (body.long("durationMs") ?: 0L).coerceIn(0L, 6L * 3600 * 1000)
                val priority = body.bool("priority") ?: false
                val e = onMain { c.enqueueYoutube(YtVideo(vid, title, channel, dur), priority, by, cid) }
                ok(mapOf("itemId" to e.itemId, "position" to queuePosition(e.itemId)))
            }
            m == Method.GET && uri == "/api/queue" -> {
                val q = sl.queueRepo.state.value
                ok(mapOf("version" to q.version, "items" to q.toDtos()))
            }

            m == Method.POST && uri == "/api/queue" -> {
                val songId = body.long("songId") ?: throw ApiException("BAD_REQUEST", "Thiếu songId")
                val priority = body.bool("priority") ?: false
                val e = onMain { c.enqueue(songId, priority, by, cid) }
                ok(mapOf("itemId" to e.itemId, "position" to queuePosition(e.itemId)))
            }
            m == Method.POST && uri == "/api/queue/move" -> {
                val id = body.long("itemId") ?: throw ApiException("BAD_REQUEST", "Thiếu itemId")
                requireOwnerOrItemOwner(cid, id)
                val to = body.long("toIndex")?.toInt() ?: throw ApiException("BAD_REQUEST", "Thiếu toIndex")
                if (!onMain { c.moveItem(id, to, by) }) throw notFoundItem()
                ok()
            }
            m == Method.POST && reTop.matches(uri) -> {
                val id = reTop.matchEntire(uri)!!.groupValues[1].toLong()
                requireOwnerOrItemOwner(cid, id)
                if (!onMain { c.moveToTop(id, by) }) throw notFoundItem()
                ok()
            }
            m == Method.DELETE && uri == "/api/queue" -> { requireOwner(cid); onMain { sl.queueRepo.clear() }; ok() }
            m == Method.DELETE && reItem.matches(uri) -> {
                val id = reItem.matchEntire(uri)!!.groupValues[1].toLong()
                requireOwnerOrItemOwner(cid, id)
                if (!onMain { sl.queueRepo.remove(id) }) throw notFoundItem()
                ok()
            }

            // Qua bài: chủ phòng luôn được; khách được nếu Cài đặt cho phép (mặc định có) hoặc đó là bài của chính mình.
            m == Method.POST && uri == "/api/player/next" -> { requireCurrentControl(cid, skipLike = true); onMain { c.next() }; ok() }
            // Tạm dừng / tiếp tục / hát lại / tua: chủ phòng, hoặc người đã chọn bài đang hát. Chưa có bài thì ai cũng bấm hát được.
            m == Method.POST && uri == "/api/player/toggle" -> { requireCurrentControl(cid, skipLike = false); onMain { c.toggle() }; ok() }
            m == Method.POST && uri == "/api/player/play" -> { requireCurrentControl(cid, skipLike = false); onMain { c.play() }; ok() }
            m == Method.POST && uri == "/api/player/pause" -> { requireCurrentControl(cid, skipLike = false); onMain { c.pause() }; ok() }
            m == Method.POST && uri == "/api/player/replay" -> { requireCurrentControl(cid, skipLike = false); onMain { c.replay() }; ok() }
            // Tone (nửa cung) và tốc độ (%): body {delta} để cộng/trừ hoặc {value} để đặt thẳng
            m == Method.POST && uri == "/api/player/key" -> {
                onMain { c.setKey(body.long("delta")?.toInt(), body.long("value")?.toInt()) }; ok()
            }
            m == Method.POST && uri == "/api/player/tempo" -> {
                onMain { c.setTempo(body.long("delta")?.toInt(), body.long("value")?.toInt()) }; ok()
            }
            m == Method.POST && uri == "/api/player/seek" -> {
                val ms = body.long("positionMs") ?: throw ApiException("BAD_REQUEST", "Thiếu positionMs")
                requireCurrentControl(cid, skipLike = false)
                onMain { c.seek(ms) }; ok()
            }
            // Nút "Hiện QR" trên điện thoại: giữ = on:true, thả = on:false
            m == Method.POST && uri == "/api/leave" -> { sl.hub.leave(cid); ok() }
            // Nút cảm xúc: tim / pháo / vỗ tay / quẩy bay lên TV (ai cũng bấm được; chỉ khi đang có bài hát)
            m == Method.POST && uri == "/api/react" -> {
                val kind = body.str("kind") ?: throw ApiException("BAD_REQUEST", "Thiếu kind")
                if (kind !in Reactions.KINDS) throw ApiException("BAD_REQUEST", "Loại cảm xúc không hợp lệ")
                if (!c.hasCurrent) throw ApiException("NOTHING_PLAYING", "Chưa có bài đang hát")
                Reactions.send(cid, kind)   // bấm dồn quá nhanh thì bỏ qua lặng lẽ (tránh lag TV)
                ok()
            }
            m == Method.POST && uri == "/api/qr/show" -> { sl.qrHold.value = body.bool("on") ?: false; ok() }

            // ---- Bài hát yêu thích ----
            m == Method.GET && uri == "/api/favorites" ->
                ok(mapOf("items" to runBlocking(Dispatchers.IO) { sl.favorites.list(cid) }.map { it.toDto() }))
            m == Method.POST && uri == "/api/favorites" -> {
                val vid = body.get("videoId")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                runBlocking(Dispatchers.IO) {
                    if (vid.isNotEmpty()) {
                        if (!YoutubeRepository.VIDEO_ID_VALID.matches(vid)) throw ApiException("BAD_REQUEST", "videoId không hợp lệ")
                        val title = body.get("title")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty().ifBlank { "Video YouTube" }.take(200).let { TitleCleaner.clean(it) }
                        val channel = body.get("channel")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty().ifBlank { "YouTube" }.take(100)
                        val dur = (body.long("durationMs") ?: 0L).coerceIn(0L, 6L * 3600 * 1000)
                        sl.favorites.add(cid, sl.songRepo.upsertYoutube(YtVideo(vid, title, channel, dur)))
                    } else {
                        val id = body.long("songId") ?: throw ApiException("BAD_REQUEST", "Thiếu songId")
                        val song = sl.songRepo.getById(id) ?: throw ApiException("SONG_NOT_FOUND", "Không tìm thấy bài hát", 404)
                        sl.favorites.add(cid, song)
                    }
                }
                favoritesChanged(cid)
            }
            m == Method.DELETE && reFav.matches(uri) -> {
                val id = reFav.matchEntire(uri)!!.groupValues[1].toLong()
                runBlocking(Dispatchers.IO) { sl.favorites.remove(cid, id) }
                favoritesChanged(cid)
            }
            m == Method.POST && uri == "/api/volume" -> {
                requireOwner(cid)
                onMain { c.setVolume(body.long("level")?.toInt(), body.long("delta")?.toInt()) }; ok()
            }

            // ---- Cài đặt chỉnh được lúc đang hát (TV đổi theo ngay, mọi điện thoại cùng cập nhật qua WebSocket) ----
            m == Method.GET && uri == "/api/settings" -> ok(sl.settings.toLiveDto())
            m == Method.GET && uri == "/api/room" -> ok(roomInfo(cid))
            m == Method.POST && uri == "/api/settings" -> { requireOwner(cid); ok(applyLiveSettings(body, by)) }
            m == Method.POST && uri == "/api/settings/reset" -> { requireOwner(cid); ok(resetLiveSettings(by)) }
            m == Method.POST && uri == "/api/app/update/check" -> ok(checkAppUpdate())
            m == Method.GET && uri == "/api/app/update/status" -> ok(appUpdateStatus())
            m == Method.POST && uri == "/api/app/update/install" -> { requireOwner(cid); ok(startAppInstall(by)) }

            m == Method.GET && uri == "/api/library" -> {
                val folders = runBlocking(Dispatchers.IO) { sl.db.folderDao().getAll() }
                ok(mapOf("folders" to folders, "scan" to sl.scanner.progress.value))
            }
            m == Method.POST && uri == "/api/library/scan" -> {
                requireLocal(s)
                if (sl.scanner.isRunning) throw ApiException("SCAN_IN_PROGRESS", "Đang quét thư viện", 409)
                sl.appScope.launch(Dispatchers.IO) { sl.scanner.scanAll() }
                ok()
            }
            m == Method.POST && uri == "/api/library/folder" -> {
                requireLocal(s)
                val path = body.get("path")?.asString ?: throw ApiException("BAD_REQUEST", "Thiếu path")
                if (!java.io.File(path).isDirectory) throw ApiException("BAD_REQUEST", "Thư mục không tồn tại")
                runBlocking(Dispatchers.IO) { sl.db.folderDao().insert(com.karaokebox.data.db.MusicFolderEntity(path = path)) }
                sl.appScope.launch(Dispatchers.IO) { sl.scanner.scanAll() }
                ok()
            }
            m == Method.DELETE && reFolderDel.matches(uri) -> {
                requireLocal(s)
                val id = reFolderDel.matchEntire(uri)!!.groupValues[1].toLong()
                runBlocking(Dispatchers.IO) {
                    sl.db.songDao().deleteByFolder(id)
                    sl.db.folderDao().delete(id)
                }
                ok()
            }
            else -> throw ApiException("BAD_REQUEST", "Không có API này: $uri", 404)
        }
    }

    // ------------------------------------------------------------ tiện ích

    /**
     * Trả danh sách yêu thích mới nhất cho người thao tác. Yêu thích là riêng từng điện thoại nên chỉ báo
     * cho các kết nối WebSocket cùng clientId (vd: nhiều tab trên cùng một điện thoại), KHÔNG phát cho máy khác.
     */
    private fun favoritesChanged(cid: String): Response {
        val items = runBlocking(Dispatchers.IO) { sl.favorites.list(cid) }.map { it.toDto() }
        sl.hub.sendToClient(cid, "favorites.updated", mapOf("items" to items))
        return ok(mapOf("items" to items))
    }

    /** Vị trí (bắt đầu từ 1) của bài vừa thêm trong hàng chờ; 0 nếu nó đã được hát ngay. Hàng chờ xếp công bằng nên không phải lúc nào cũng ở cuối. */
    private fun queuePosition(itemId: Long): Int =
        sl.queueRepo.state.value.items.indexOfFirst { it.itemId == itemId } + 1

    /**
     * Đổi cài đặt "chỉnh lúc hát". Body là JSON một phần, chỉ nhận: leftIsVocal, fairQueue (bool),
     * youtubeMaxHeight (360/480/720/1080), stageTheme (AUTO/DEFAULT/TET/NOEL/BIRTHDAY),
     * audioFx (OFF/STUDIO/HALL/LIVE/BASS/VOCAL), audioFxLevel (0..100), guestCanSkip (bool).
     * Chỉ chủ phòng được gọi (kiểm tra ở nơi gọi); TV có bảng cài đặt nhanh riêng nên không bị chặn.
     * Kiểm tra hợp lệ TẤT CẢ trước khi ghi nên request sai thì không đổi gì.
     * Thư mục nhạc, quét lại, cổng... là cài đặt quản trị: cố ý KHÔNG mở qua đây.
     */
    private fun applyLiveSettings(body: JsonObject, by: String): LiveSettingsDto {
        val st = sl.settings
        val vocal = body.bool("leftIsVocal")
        val fair = body.bool("fairQueue")
        val height = body.int("youtubeMaxHeight")
        val theme = body.str("stageTheme")
        val fx = body.str("audioFx")
        val fxLevel = body.int("audioFxLevel")
        val guestSkip = body.bool("guestCanSkip")
        if (vocal == null && fair == null && height == null && theme == null && fx == null && fxLevel == null && guestSkip == null) {
            throw ApiException("BAD_REQUEST", "Không có mục cài đặt nào để đổi")
        }
        if (height != null && height !in SettingsStore.YT_HEIGHTS) {
            throw ApiException("BAD_REQUEST", "Chất lượng YouTube phải là ${SettingsStore.YT_HEIGHTS.joinToString("/")}")
        }
        if (fx != null && !StudioPreset.isValid(fx)) {
            throw ApiException("BAD_REQUEST", "Hiệu ứng âm thanh không hợp lệ")
        }
        if (fxLevel != null && fxLevel !in 0..100) {
            throw ApiException("BAD_REQUEST", "Cường độ hiệu ứng phải từ 0 đến 100")
        }
        val themeLabel = theme?.let { t ->
            StageTheme.MODES.firstOrNull { it.first == t }?.second
                ?: throw ApiException("BAD_REQUEST", "Giao diện màn hình chờ không hợp lệ")
        }

        val changed = mutableListOf<String>()
        if (vocal != null && vocal != st.leftIsVocal) {
            st.leftIsVocal = vocal
            changed += "kênh ca sĩ: " + (if (vocal) "TRÁI" else "PHẢI")
        }
        if (fair != null && fair != st.fairQueue) {
            st.fairQueue = fair
            changed += "xếp hàng công bằng " + (if (fair) "BẬT" else "TẮT")
        }
        if (height != null && height != st.youtubeMaxHeight) {
            st.youtubeMaxHeight = height
            changed += "YouTube ${height}p (từ bài sau)"
        }
        if (theme != null && theme != st.stageThemeMode) {
            st.stageThemeMode = theme
            changed += "giao diện chờ: $themeLabel"
        }
        if (fx != null && fx != st.audioFx) {
            st.audioFx = fx
            changed += "hiệu ứng âm thanh: " + StudioPreset.fromKey(fx).label
        }
        if (guestSkip != null && guestSkip != st.guestCanSkip) {
            st.guestCanSkip = guestSkip
            changed += "khách qua bài người khác " + (if (guestSkip) "BẬT" else "TẮT")
        }
        if (fxLevel != null && fxLevel != st.audioFxLevel) {
            st.audioFxLevel = fxLevel
            changed += "cường độ hiệu ứng $fxLevel%"
        }
        if (changed.isNotEmpty()) sl.controller.announce("$by đã đổi ${changed.joinToString("; ")}")
        return st.toLiveDto()
    }

    /** Đặt lại các cài đặt chỉnh lúc hát về mặc định (nút "Đặt lại mặc định" trong Cài đặt trên điện thoại). Chỉ chủ phòng. */
    private fun resetLiveSettings(by: String): LiveSettingsDto {
        sl.settings.resetLiveDefaults()
        sl.controller.announce("$by đã đặt lại cài đặt về mặc định")
        return sl.settings.toLiveDto()
    }

    /** Trạng thái tải/cài bản mới + bản mới nhất đã biết (không gọi mạng). */
    private fun appUpdateStatus(): Map<String, Any?> {
        val st = AppInstaller.state.value
        val rel = AppUpdater.known(sl.settings)
        return mapOf(
            "phase" to st.phase, "percent" to st.percent, "message" to st.message,
            "newer" to (rel != null && AppUpdater.isNewer(rel)),
            "latest" to rel?.label,
            "current" to BuildConfig.VERSION_NAME
        )
    }

    /** Nút "Tải và cài bản mới" (chủ phòng): box tải APK về rồi mở trình cài đặt của Android trên TV, người dùng bấm "Cài đặt" ở đó. */
    private fun startAppInstall(by: String): Map<String, Any?> {
        val rel = AppUpdater.known(sl.settings)
        if (rel == null || !AppUpdater.isNewer(rel)) {
            throw ApiException("NO_UPDATE", "Chưa có bản mới. Hãy bấm Kiểm tra cập nhật trước")
        }
        if (!AppInstaller.start(sl.appScope, ctx, sl.settings, rel)) {
            throw ApiException("BUSY", "Đang tải bản mới rồi, đợi một chút", 409)
        }
        sl.controller.announce("$by đang tải bản mới ${rel.label} cho box")
        return appUpdateStatus()
    }

    /**
     * Hỏi GitHub Releases xem có bản app mới không (nút "Kiểm tra cập nhật" trên điện thoại). Giãn tối thiểu 15 giây giữa hai
     * lần gọi mạng để bấm dồn không làm GitHub chặn (60 request/giờ cho mỗi IP). Việc tải và cài vẫn làm trên TV
     * vì Android bắt người dùng bấm "Cài đặt" ngay trên màn hình của box.
     */
    private fun checkAppUpdate(): Map<String, Any?> {
        val r = runBlocking(Dispatchers.IO) { AppUpdater.check(sl.settings, 15_000L) }
        val rel = r.release
        return mapOf(
            "current" to BuildConfig.VERSION_NAME,
            "newpipe" to BuildConfig.NEWPIPE_VERSION,
            "latest" to rel?.label,
            "latestTag" to rel?.tag,
            "newer" to (rel != null && AppUpdater.isNewer(rel)),
            "checkedAt" to sl.settings.appCheckedAt,
            "error" to r.error
        )
    }

    // ------------------------------------------------------------ phân quyền (chủ phòng / khách)

    private fun forbidden(msg: String = "Chỉ chủ phòng mới dùng được") = ApiException("FORBIDDEN", msg, 403)

    /**
     * Cài đặt quản trị (thư mục nhạc, quét lại) chỉ làm trên TV, đúng như README. NanoHTTPD gộp mọi địa chỉ loopback
     * thành "127.0.0.1"; điện thoại trong LAN luôn đến từ IP khác nên bị chặn, kể cả chủ phòng.
     */
    private fun requireLocal(s: IHTTPSession) {
        if (s.remoteIpAddress != "127.0.0.1") throw forbidden("Thư mục nhạc và quét lại chỉ chỉnh được trên TV (nút Vàng)")
    }

    private fun requireOwner(cid: String) {
        if (!sl.room.isOwner(cid)) throw forbidden()
    }

    /** Chủ phòng làm được với mọi bài; khách chỉ với bài do chính mình chọn. Không thấy mục thì để lệnh phía sau báo "không tìm thấy". */
    private fun requireOwnerOrItemOwner(cid: String, itemId: Long) {
        if (sl.room.isOwner(cid)) return
        val e = sl.queueRepo.state.value.items.firstOrNull { it.itemId == itemId } ?: return
        if (e.ownerId != cid) throw forbidden("Chỉ chủ phòng hoặc người chọn bài này mới dùng được")
    }

    /**
     * Điều khiển bài đang hát. Chủ phòng luôn được; chưa có bài thì ai cũng bắt đầu hát được; người chọn bài đang hát được;
     * riêng "qua bài" ([skipLike]) khách còn được khi Cài đặt cho phép khách qua bài người khác.
     */
    private fun requireCurrentControl(cid: String, skipLike: Boolean) {
        if (sl.room.isOwner(cid)) return
        val c = sl.controller
        if (!c.hasCurrent || c.currentOwnerId == cid) return
        if (skipLike && sl.settings.guestCanSkip) return
        throw forbidden("Chỉ chủ phòng hoặc người chọn bài này mới dùng được")
    }

    private fun roomInfo(cid: String): Map<String, Any?> {
        val st = sl.room.state.value
        return mapOf("isOwner" to sl.room.isOwner(cid), "hasOwner" to (st.ownerId != null), "ownerName" to st.ownerName)
    }

    private fun notFoundItem() = ApiException("ITEM_NOT_FOUND", "Không tìm thấy mục trong danh sách chờ", 404)

    private fun clientName(s: IHTTPSession): String {
        val raw = s.headers["x-client-name"]?.trim().orEmpty()
        val decoded = try { URLDecoder.decode(raw, "UTF-8") } catch (_: Exception) { raw }
        return decoded.ifBlank { "Khách" }.take(30)
    }

    /** Mã định danh điện thoại (web app gửi qua header X-Client-Id). Thiếu/không hợp lệ -> "unknown". */
    private fun clientId(s: IHTTPSession): String {
        val raw = s.headers["x-client-id"]?.trim().orEmpty()
        val clean = raw.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(64)
        return clean.ifEmpty { "unknown" }
    }

    private fun readBody(s: IHTTPSession): JsonObject {
        val len = s.headers["content-length"]?.trim()?.toLongOrNull() ?: 0L
        if (len > MAX_BODY_BYTES) throw ApiException("TOO_LARGE", "Dữ liệu gửi lên quá lớn", 413)
        val files = HashMap<String, String>()
        s.parseBody(files)
        val raw = files["postData"] ?: return JsonObject()
        return try {
            JsonParser.parseString(raw).asJsonObject
        } catch (e: Exception) {
            throw ApiException("BAD_REQUEST", "JSON không hợp lệ")
        }
    }

    private fun JsonObject.long(k: String): Long? = get(k)?.takeIf { !it.isJsonNull }?.asLong
    private fun JsonObject.bool(k: String): Boolean? = get(k)?.takeIf { !it.isJsonNull }?.asBoolean
    private fun JsonObject.int(k: String): Int? = get(k)?.takeIf { !it.isJsonNull }?.let {
        try { it.asInt } catch (e: Exception) { throw ApiException("BAD_REQUEST", "$k không hợp lệ") }
    }
    private fun JsonObject.str(k: String): String? = get(k)?.takeIf { !it.isJsonNull }?.let {
        try { it.asString } catch (e: Exception) { throw ApiException("BAD_REQUEST", "$k không hợp lệ") }
    }

    private fun ok(data: Any? = null): Response =
        json(Response.Status.OK, mapOf("ok" to true, "data" to (data ?: emptyMap<String, Any>())))

    private fun error(code: String, msg: String, http: Int): Response {
        val st = when (http) {
            403 -> Response.Status.FORBIDDEN
            404 -> Response.Status.NOT_FOUND
            409 -> Response.Status.CONFLICT
            413 -> Response.Status.PAYLOAD_TOO_LARGE
            500 -> Response.Status.INTERNAL_ERROR
            503 -> Response.Status.SERVICE_UNAVAILABLE
            else -> Response.Status.BAD_REQUEST
        }
        return json(st, mapOf("ok" to false, "error" to mapOf("code" to code, "message" to msg)))
    }

    private fun text(s: String): Response {
        val r = NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", s)
        r.addHeader("Cache-Control", "no-store")
        return r
    }

    private fun json(status: Response.Status, obj: Any): Response {
        val r = NanoHTTPD.newFixedLengthResponse(status, "application/json; charset=utf-8", gson.toJson(obj))
        r.addHeader("Cache-Control", "no-store")
        return r
    }
}
