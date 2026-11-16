package com.karaokebox.data.repo

import android.content.Context
import android.content.SharedPreferences
import com.karaokebox.player.audio.StudioPreset
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

class SettingsStore(ctx: Context) {
    private val sp = ctx.getSharedPreferences("karaoke", Context.MODE_PRIVATE)

    companion object {
        // Khóa của các cài đặt chỉnh được ngay lúc đang hát (điện thoại + bảng nhanh trên TV)
        const val K_LEFT_IS_VOCAL = "leftIsVocal"
        const val K_FAIR_QUEUE = "fairQueue"
        const val K_YT_MAX_HEIGHT = "ytMaxHeight"
        const val K_STAGE_THEME = "stageTheme"
        const val K_AUDIO_FX = "audioFx"
        const val K_AUDIO_FX_LEVEL = "audioFxLevel"
        const val K_GUEST_SKIP = "guestCanSkip"
        const val K_MIC_ENABLED = "micEnabled"
        const val K_MIC_VOLUME = "micVolume"
        const val K_MIC_ECHO = "micEcho"
        /** Không lưu vào SharedPreferences: chỉ là tín hiệu "trạng thái micro vừa đổi" (xem [notifyChanged]). */
        const val K_MIC_STATUS = "micStatus"
        private val LIVE_KEYS = setOf(
            K_LEFT_IS_VOCAL, K_FAIR_QUEUE, K_YT_MAX_HEIGHT, K_STAGE_THEME, K_AUDIO_FX, K_AUDIO_FX_LEVEL, K_GUEST_SKIP,
            K_MIC_ENABLED, K_MIC_VOLUME, K_MIC_ECHO
        )

        /** Các mức chất lượng YouTube cho phép (chiều cao tối đa, px). */
        val YT_HEIGHTS = listOf(360, 480, 720, 1080)
    }

    private val _changes = MutableSharedFlow<String>(extraBufferCapacity = 16)
    /** Phát khóa của cài đặt "chỉnh lúc hát" vừa đổi giá trị (từ điện thoại, TV hay màn hình Cài đặt). */
    val changes: SharedFlow<String> = _changes

    /** Phát tín hiệu đổi cho một khóa không nằm trong SharedPreferences (vd trạng thái micro) để điện thoại / TV cập nhật. */
    fun notifyChanged(key: String) { _changes.tryEmit(key) }

    // Phải giữ tham chiếu mạnh: SharedPreferences chỉ giữ listener bằng WeakHashMap
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null && key in LIVE_KEYS) _changes.tryEmit(key)
    }
    init { sp.registerOnSharedPreferenceChangeListener(listener) }

    /** true: tên file dạng "Ca sĩ - Tên bài". */
    var swapTitleArtist: Boolean
        get() = sp.getBoolean("swapTitleArtist", false)
        set(v) = sp.edit().putBoolean("swapTitleArtist", v).apply()

    /** true: với file 1 track stereo, kênh TRÁI là tiếng ca sĩ (kênh phải là beat). */
    var leftIsVocal: Boolean
        get() = sp.getBoolean(K_LEFT_IS_VOCAL, true)
        set(v) = sp.edit().putBoolean(K_LEFT_IS_VOCAL, v).apply()

    var autoStart: Boolean
        get() = sp.getBoolean("autoStart", false)
        set(v) = sp.edit().putBoolean("autoStart", v).apply()

    /** true: bài mới thêm được xếp xen kẽ theo người thêm để ai cũng có lượt hát (mặc định bật). */
    var fairQueue: Boolean
        get() = sp.getBoolean(K_FAIR_QUEUE, true)
        set(v) = sp.edit().putBoolean(K_FAIR_QUEUE, v).apply()

    /** true: khách (không phải chủ phòng) được qua cả bài của người khác. Tắt thì khách chỉ qua được bài của chính mình. */
    var guestCanSkip: Boolean
        get() = sp.getBoolean(K_GUEST_SKIP, true)
        set(v) = sp.edit().putBoolean(K_GUEST_SKIP, v).apply()

    /** Micro cắm vào box (USB): bật thì tiếng hát đi qua box, có vang. Mặc định TẮT; mỗi buổi hát chủ phòng tự bật. */
    var micEnabled: Boolean
        get() = sp.getBoolean(K_MIC_ENABLED, false)
        set(v) = sp.edit().putBoolean(K_MIC_ENABLED, v).apply()

    /** Âm lượng micro 0..100 (%), mặc định 60. */
    var micVolume: Int
        get() = sp.getInt(K_MIC_VOLUME, 60).coerceIn(0, 100)
        set(v) = sp.edit().putInt(K_MIC_VOLUME, v.coerceIn(0, 100)).apply()

    /** Độ vang của micro 0..100 (%), mặc định 30. */
    var micEcho: Int
        get() = sp.getInt(K_MIC_ECHO, 30).coerceIn(0, 100)
        set(v) = sp.edit().putInt(K_MIC_ECHO, v.coerceIn(0, 100)).apply()

    var preferredPort: Int
        get() = sp.getInt("port", 8080)
        set(v) = sp.edit().putInt("port", v).apply()

    /** Chiều cao tối đa khi phát YouTube (360/480/720/1080). 720p là cân bằng cho Wi-Fi + Q10. */
    var youtubeMaxHeight: Int
        get() = sp.getInt(K_YT_MAX_HEIGHT, 720)
        set(v) = sp.edit().putInt(K_YT_MAX_HEIGHT, v).apply()

    /** Đã đặt âm lượng mặc định (80%) lần đầu chưa. Chỉ làm một lần để không ghi đè mức người dùng đã chỉnh sau đó. */
    var defaultVolumeApplied: Boolean
        get() = sp.getBoolean("defVolApplied", false)
        set(v) = sp.edit().putBoolean("defVolApplied", v).apply()

    /** Dung lượng cache bài YouTube trên ổ box (MB). 0 = tắt cache. Đổi xong cần mở lại app. Thực tế còn bị giới hạn theo chỗ trống của ổ. */
    var youtubeCacheMb: Int
        get() = sp.getInt("ytCacheMb", 2048)
        set(v) = sp.edit().putInt("ytCacheMb", v).apply()

    /** Bản NewPipeExtractor mới nhất đã biết (tag GitHub, vd "v0.27.0"); rỗng = chưa kiểm tra lần nào. */
    var npLatest: String
        get() = sp.getString("npLatest", "") ?: ""
        set(v) = sp.edit().putString("npLatest", v).apply()

    /** Thời điểm (ms) kiểm tra bản mới thành công gần nhất. */
    var npCheckedAt: Long
        get() = sp.getLong("npCheckedAt", 0L)
        set(v) = sp.edit().putLong("npCheckedAt", v).apply()

    /** Bản app mới nhất đã biết trên GitHub Releases (tag, link APK, dung lượng); rỗng = chưa kiểm tra. */
    var appLatestTag: String
        get() = sp.getString("appLatestTag", "") ?: ""
        set(v) = sp.edit().putString("appLatestTag", v).apply()
    var appLatestUrl: String
        get() = sp.getString("appLatestUrl", "") ?: ""
        set(v) = sp.edit().putString("appLatestUrl", v).apply()
    var appLatestSize: Long
        get() = sp.getLong("appLatestSize", 0L)
        set(v) = sp.edit().putLong("appLatestSize", v).apply()
    var appCheckedAt: Long
        get() = sp.getLong("appCheckedAt", 0L)
        set(v) = sp.edit().putLong("appCheckedAt", v).apply()

    /** Tự kiểm tra bản app mới (lúc mở app và mỗi 6 giờ) và nhắc trên TV. */
    var autoUpdateCheck: Boolean
        get() = sp.getBoolean("autoUpdateCheck", true)
        set(v) = sp.edit().putBoolean("autoUpdateCheck", v).apply()

    /** Đã bấm cài bản mới: mở lại app ngay khi cài xong (Android không tự mở lại sau khi cài đè). */
    var relaunchAfterUpdate: Boolean
        get() = sp.getBoolean("relaunchAfterUpdate", false)
        set(v) = sp.edit().putBoolean("relaunchAfterUpdate", v).apply()

    /** Giao diện màn hình chờ: AUTO (theo ngày) | DEFAULT | TET | NOEL | BIRTHDAY. */
    var stageThemeMode: String
        get() = sp.getString(K_STAGE_THEME, "AUTO") ?: "AUTO"
        set(v) = sp.edit().putString(K_STAGE_THEME, v).apply()

    /** Hiệu ứng âm thanh phòng thu: khóa của [StudioPreset] (OFF | STUDIO | HALL | LIVE | BASS | VOCAL). Mặc định STUDIO. */
    var audioFx: String
        get() = StudioPreset.fromKey(sp.getString(K_AUDIO_FX, StudioPreset.DEFAULT.key)).key
        set(v) = sp.edit().putString(K_AUDIO_FX, StudioPreset.fromKey(v).key).apply()

    /** Cường độ hiệu ứng 0..100 (%), mặc định 70. */
    var audioFxLevel: Int
        get() = sp.getInt(K_AUDIO_FX_LEVEL, 70).coerceIn(0, 100)
        set(v) = sp.edit().putInt(K_AUDIO_FX_LEVEL, v.coerceIn(0, 100)).apply()

    /** Tone (số nửa cung, -6..+6) nhớ riêng cho từng bài; 0 = tone gốc nên không lưu. */
    fun songKey(songId: Long): Int = sp.getInt("key_$songId", 0)
    fun setSongKey(songId: Long, v: Int) {
        val e = sp.edit()
        if (v == 0) e.remove("key_$songId") else e.putInt("key_$songId", v)
        e.apply()
    }
}
