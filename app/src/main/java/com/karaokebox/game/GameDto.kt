package com.karaokebox.game

data class GameOptionDto(val key: String, val label: String)

/** Cài đặt trò chơi gửi cho web remote (màn hình 🎲 Trò chơi) kèm các lựa chọn hợp lệ và trạng thái đêm hiện tại. */
data class GameSettingsDto(
    val enabled: Boolean,
    val frequency: String,
    val thresholdMin: Int,
    val levelBefore: String,
    val levelAfter: String,
    val idleResetMin: Int,
    val groupPosition: Boolean,
    val groupGroup: Boolean,
    val groupTrait: Boolean,
    val segMen: Boolean,
    val segWomen: Boolean,
    val maxRounds: Int,
    val resultSec: Int,
    val sound: Boolean,
    val reminder: Boolean,
    val frequencyOptions: List<GameOptionDto>,
    val levelBeforeOptions: List<GameOptionDto>,
    val levelAfterOptions: List<GameOptionDto>,
    val thresholdOptions: List<Int>,
    val idleResetOptions: List<Int>,
    val maxRoundsOptions: List<Int>,
    val resultSecOptions: List<Int>,
    val nightMinutes: Int,        // đã hát được bao lâu trong đêm hiện tại
    val roundsThisNight: Int,
    val running: Boolean          // đang có vòng quay trên TV
)

private fun List<Pair<String, String>>.dto() = map { GameOptionDto(it.first, it.second) }

fun GameRules.toDto(engine: GameEngine) = GameSettingsDto(
    enabled = enabled, frequency = frequency, thresholdMin = thresholdMin,
    levelBefore = levelBefore, levelAfter = levelAfter, idleResetMin = idleResetMin,
    groupPosition = groupPosition, groupGroup = groupGroup, groupTrait = groupTrait,
    segMen = segMen, segWomen = segWomen, maxRounds = maxRounds, resultSec = resultSec,
    sound = sound, reminder = reminder,
    frequencyOptions = GameOptions.FREQUENCIES.dto(),
    levelBeforeOptions = GameOptions.LEVEL_BEFORE.dto(),
    levelAfterOptions = GameOptions.LEVEL_AFTER.dto(),
    thresholdOptions = GameOptions.THRESHOLD_MIN,
    idleResetOptions = GameOptions.IDLE_RESET_MIN,
    maxRoundsOptions = GameOptions.MAX_ROUNDS,
    resultSecOptions = GameOptions.RESULT_SEC,
    nightMinutes = (engine.session.elapsedMs() / 60_000L).toInt(),
    roundsThisNight = engine.session.rounds,
    running = engine.isRunning
)
