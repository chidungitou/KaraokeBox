package com.karaokebox.game

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Cài đặt trò chơi cuối bài. CỐ Ý lưu riêng (file "karaoke_game") và API riêng, tách khỏi [com.karaokebox.data.repo.SettingsStore]:
 * "Đặt lại mặc định" và sao lưu của phần cài đặt thường không đụng tới trò chơi, và ngược lại.
 */
class GameSettingsStore(ctx: Context) : GameRules {
    private val sp = ctx.getSharedPreferences("karaoke_game", Context.MODE_PRIVATE)

    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    /** Phát mỗi khi một cài đặt trò chơi đổi (TV cập nhật nhãn nút...). */
    val changes: SharedFlow<Unit> = _changes

    // Phải giữ tham chiếu mạnh: SharedPreferences chỉ giữ listener bằng WeakHashMap
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> _changes.tryEmit(Unit) }
    init { sp.registerOnSharedPreferenceChangeListener(listener) }

    private fun bool(k: String, def: Boolean) = sp.getBoolean(k, def)
    private fun putBool(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()
    private fun str(k: String, def: String, allowed: List<String>): String {
        val v = sp.getString(k, def) ?: def
        return if (v in allowed) v else def
    }
    private fun int(k: String, def: Int, allowed: List<Int>): Int {
        val v = sp.getInt(k, def)
        return if (v in allowed) v else def
    }

    /** Mặc định TẮT: phòng có trẻ em hoặc người không uống không bị bất ngờ. */
    override var enabled: Boolean
        get() = bool("enabled", false)
        set(v) = putBool("enabled", v)

    override var frequency: String
        get() = str("frequency", "EVERY", GameOptions.keys(GameOptions.FREQUENCIES))
        set(v) = sp.edit().putString("frequency", v).apply()

    override var thresholdMin: Int
        get() = int("thresholdMin", 30, GameOptions.THRESHOLD_MIN)
        set(v) = sp.edit().putInt("thresholdMin", v).apply()

    override var levelBefore: String
        get() = str("levelBefore", "FULL", GameOptions.keys(GameOptions.LEVEL_BEFORE))
        set(v) = sp.edit().putString("levelBefore", v).apply()

    override var levelAfter: String
        get() = str("levelAfter", "HALF", GameOptions.keys(GameOptions.LEVEL_AFTER))
        set(v) = sp.edit().putString("levelAfter", v).apply()

    override var idleResetMin: Int
        get() = int("idleResetMin", 60, GameOptions.IDLE_RESET_MIN)
        set(v) = sp.edit().putInt("idleResetMin", v).apply()

    override var groupPosition: Boolean
        get() = bool("groupPosition", true)
        set(v) = putBool("groupPosition", v)

    override var groupGroup: Boolean
        get() = bool("groupGroup", true)
        set(v) = putBool("groupGroup", v)

    override var groupTrait: Boolean
        get() = bool("groupTrait", true)
        set(v) = putBool("groupTrait", v)

    override var segMen: Boolean
        get() = bool("segMen", true)
        set(v) = putBool("segMen", v)

    override var segWomen: Boolean
        get() = bool("segWomen", true)
        set(v) = putBool("segWomen", v)

    override var maxRounds: Int
        get() = int("maxRounds", 0, GameOptions.MAX_ROUNDS)
        set(v) = sp.edit().putInt("maxRounds", v).apply()

    override var resultSec: Int
        get() = int("resultSec", 8, GameOptions.RESULT_SEC)
        set(v) = sp.edit().putInt("resultSec", v).apply()

    override var sound: Boolean
        get() = bool("sound", true)
        set(v) = putBool("sound", v)

    override var reminder: Boolean
        get() = bool("reminder", true)
        set(v) = putBool("reminder", v)

    /** Đặt lại TOÀN BỘ cài đặt trò chơi về mặc định (kể cả tắt trò chơi). Không đụng tới cài đặt thường. */
    fun resetDefaults() {
        sp.edit().clear().apply()
    }
}
