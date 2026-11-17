package com.karaokebox.game

/**
 * Cài đặt trò chơi mà [GameSession] / [GameEngine] cần đọc. Tách thành interface để logic chạy thử được
 * mà không cần Android ([GameSettingsStore] là bản thật lưu trong SharedPreferences, riêng với SettingsStore).
 */
interface GameRules {
    /** Bật trò chơi cuối bài (mặc định TẮT). */
    val enabled: Boolean
    /** EVERY | EVERY2 | EVERY3 | RANDOM */
    val frequency: String
    /** Mốc "đã hát lâu" (phút). */
    val thresholdMin: Int
    /** Mức trước mốc: FULL | HALF */
    val levelBefore: String
    /** Mức từ mốc trở đi: HALF | SIP | RANDOM */
    val levelAfter: String
    /** Nghỉ hát quá chừng này phút thì coi như đêm mới (0 = tắt). */
    val idleResetMin: Int
    val groupPosition: Boolean
    val groupGroup: Boolean
    val groupTrait: Boolean
    val segMen: Boolean
    val segWomen: Boolean
    /** Số vòng tối đa mỗi đêm (0 = không giới hạn). */
    val maxRounds: Int
    /** Số giây hiện kết quả. */
    val resultSec: Int
    val sound: Boolean
    val reminder: Boolean
}
