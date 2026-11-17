package com.karaokebox.game

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Một vòng quay đang hiện trên TV. TV và máy chủ chạy chung trong một tiến trình nên chỉ cần StateFlow:
 * màn hình TV được tạo lại giữa vòng vẫn vẽ lại đúng (dùng [startedAt] / [resultAt]), không quay lại lần nữa.
 * Điện thoại KHÔNG nhận gì về vòng quay.
 */
data class GameRound(
    val id: Long,
    val phase: String,            // SPINNING | RESULT
    val labels: List<String>,     // chữ trên từng ô, theo thứ tự vẽ
    val resultIndex: Int,
    val spinMs: Long,
    val resultMs: Long,
    val title: String,            // vd "BÊN TRÁI NGƯỜI VỪA HÁT"
    val action: String,           // vd "UỐNG CẠN LY!"
    val level: String,            // FULL | HALF | SIP
    val startedAt: Long,          // [nowMs] lúc bắt đầu quay
    val resultAt: Long,           // [nowMs] lúc hiện kết quả (0 khi đang quay)
    val sound: Boolean,
    val reminder: Boolean,
    val test: Boolean             // quay thử: không tính vào đêm
)

/**
 * Trò chơi cuối bài: chọn ô, chọn kết quả, giữ trạng thái vòng quay. Không biết gì về trình phát:
 * PlaybackController hỏi [shouldPlay], gọi [play] (treo tới khi xong) rồi mới sang bài kế tiếp.
 */
class GameEngine(
    private val rules: GameRules,
    private val queueHasItems: () -> Boolean,
    private val clock: () -> Long = ::nowMs,
    private val random: Random = Random.Default,
    private val spinMs: Long = 5_000L
) {
    val session = GameSession(rules, clock, random)

    private val _round = MutableStateFlow<GameRound?>(null)
    val round: StateFlow<GameRound?> = _round
    val isRunning: Boolean get() = _round.value != null

    @Volatile private var skipSignal: CompletableDeferred<Unit>? = null
    private val ids = AtomicLong(0)

    /** Các ô đang được dùng theo Cài đặt (và theo hàng chờ: không có bài kế tiếp thì bỏ ô "Hát kế tiếp"). */
    fun availableSegments(): List<GameSegment> {
        val hasNext = queueHasItems()
        return GameSegment.values().filter { s ->
            when (s.group) {
                GameGroup.POSITION -> rules.groupPosition && (s != GameSegment.NEXT || hasNext)
                GameGroup.GROUP -> rules.groupGroup && when (s) {
                    GameSegment.MEN -> rules.segMen
                    GameSegment.WOMEN -> rules.segWomen
                    else -> true
                }
                GameGroup.TRAIT -> rules.groupTrait
            }
        }
    }

    /**
     * Có nên quay sau bài vừa hát không. [naturalEnd] = bài kết thúc bình thường (không phải bị bỏ qua / lỗi).
     * Có tính bài vào bộ đếm "Tần suất", nên mỗi bài chỉ gọi MỘT lần.
     */
    fun shouldPlay(naturalEnd: Boolean): Boolean {
        if (!rules.enabled || !naturalEnd) return false
        if (isRunning) return false
        if (session.limitReached()) return false
        if (availableSegments().isEmpty()) return false
        return session.dueNow()
    }

    /**
     * Quay + hiện kết quả. Treo tới khi xong, bị [skip] hoặc bị hủy (hủy thì trạng thái vòng tự dọn).
     * Trả false nếu không quay được (đang có vòng khác, hoặc không còn ô nào).
     */
    suspend fun play(test: Boolean = false): Boolean {
        val segs = availableSegments()
        if (segs.isEmpty()) return false
        val picked = pickWeighted(segs)
        val level = session.level()
        val title = if (picked == GameSegment.TRAIT) GamePreset.TRAITS.random(random) else picked.title
        val action = if (picked == GameSegment.ALL) "DÔ! ${level.label}!" else "UỐNG ${level.label}!"
        val resultMs = rules.resultSec * 1000L
        val signal = CompletableDeferred<Unit>()
        val id = ids.incrementAndGet()
        val base = GameRound(
            id = id, phase = "SPINNING", labels = segs.map { it.label }, resultIndex = segs.indexOf(picked),
            spinMs = spinMs, resultMs = resultMs, title = title, action = action, level = level.key,
            startedAt = clock(), resultAt = 0L, sound = rules.sound, reminder = rules.reminder, test = test
        )
        if (!_round.compareAndSet(null, base)) return false   // đang có vòng khác
        skipSignal = signal
        if (!test) session.roundStarted()
        try {
            if (withTimeoutOrNull(spinMs) { signal.await() } != null) return true   // bị bỏ qua giữa lúc quay
            _round.value = base.copy(phase = "RESULT", resultAt = clock())
            withTimeoutOrNull(resultMs) { signal.await() }
            return true
        } finally {
            if (skipSignal === signal) skipSignal = null
            val cur = _round.value
            if (cur != null && cur.id == id) _round.compareAndSet(cur, null)
        }
    }

    /** Kết thúc sớm vòng đang hiện (phím OK / Back trên remote TV, hoặc chủ phòng). [play] trả về bình thường. */
    fun skip() { skipSignal?.complete(Unit) }

    /** Dọn ngay, kể cả khi không còn coroutine nào đang chờ (dùng khi bài bị bỏ qua / dừng hẳn). */
    fun dismiss() {
        skip()
        _round.value = null
    }

    private fun pickWeighted(list: List<GameSegment>): GameSegment {
        val total = list.sumOf { it.weight }
        var r = random.nextDouble() * total
        for (s in list) {
            r -= s.weight
            if (r < 0) return s
        }
        return list.last()
    }
}
