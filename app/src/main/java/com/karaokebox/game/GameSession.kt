package com.karaokebox.game

import kotlin.random.Random

/**
 * "Đêm hát" hiện tại: tính xem đã hát được bao lâu (để chọn mức uống), đã quay mấy vòng, và tới lượt quay chưa.
 *
 * - Đêm bắt đầu từ lúc mở app. Box hay tự mở app khi bật máy nên: nếu nghỉ hát quá [GameRules.idleResetMin] phút
 *   (mặc định 60) thì bài kế tiếp bắt đầu một đêm MỚI. Chủ phòng cũng có thể bấm "Bắt đầu đêm mới".
 * - "Nghỉ hát" tính bằng lần gần nhất có nhạc đang phát ([touch], gọi mỗi giây khi đang hát).
 */
class GameSession(
    private val rules: GameRules,
    private val clock: () -> Long = ::nowMs,
    private val random: Random = Random.Default
) {
    private var nightStart = clock()
    private var lastActive = nightStart
    private var songsSinceRound = 0

    /** Số vòng đã quay trong đêm này (không tính quay thử). */
    @Volatile var rounds = 0; private set

    /** Có nhạc đang phát lúc này: cập nhật mốc "lần gần nhất đang hát". */
    fun touch() { lastActive = clock() }

    /** Sắp hát bài mới (không tính lần nối lại sau lỗi): nghỉ quá lâu thì sang đêm mới. */
    fun onSongStart() {
        val now = clock()
        val idle = rules.idleResetMin
        if (idle > 0 && now - lastActive > idle * 60_000L) newNight() else lastActive = now
    }

    fun newNight() {
        val now = clock()
        nightStart = now
        lastActive = now
        rounds = 0
        songsSinceRound = 0
    }

    fun elapsedMs(): Long = (clock() - nightStart).coerceAtLeast(0L)

    /** Mức uống của vòng quay lúc này, theo thời gian đã hát và cài đặt. */
    fun level(): DrinkLevel {
        val before = if (rules.levelBefore == "HALF") DrinkLevel.HALF else DrinkLevel.FULL
        if (elapsedMs() < rules.thresholdMin * 60_000L) return before
        return when (rules.levelAfter) {
            "SIP" -> DrinkLevel.SIP
            "RANDOM" -> if (random.nextBoolean()) DrinkLevel.HALF else DrinkLevel.SIP
            else -> DrinkLevel.HALF
        }
    }

    fun limitReached(): Boolean = rules.maxRounds > 0 && rounds >= rules.maxRounds

    /** Gọi một lần mỗi khi một bài kết thúc bình thường: đếm bài và cho biết tới lượt quay chưa theo "Tần suất". */
    fun dueNow(): Boolean {
        songsSinceRound++
        return when (rules.frequency) {
            "EVERY2" -> songsSinceRound >= 2
            "EVERY3" -> songsSinceRound >= 3
            "RANDOM" -> random.nextBoolean()
            else -> true
        }
    }

    fun roundStarted() {
        rounds++
        songsSinceRound = 0
    }
}
