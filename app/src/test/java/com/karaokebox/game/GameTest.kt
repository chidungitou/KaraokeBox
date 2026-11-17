package com.karaokebox.game

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Cài đặt giả cho test, giá trị mặc định giống bản thật. */
class FakeRules : GameRules {
    override var enabled = true
    override var frequency = "EVERY"
    override var thresholdMin = 30
    override var levelBefore = "FULL"
    override var levelAfter = "HALF"
    override var idleResetMin = 60
    override var groupPosition = true
    override var groupGroup = true
    override var groupTrait = true
    override var segMen = true
    override var segWomen = true
    override var maxRounds = 0
    override var resultSec = 5
    override var sound = true
    override var reminder = true
}

class GameTest {
    private val min = 60_000L

    private class Clock { var t = 1_000_000L; fun advance(ms: Long) { t += ms }; fun now() = t }

    @Test fun levelFullBeforeThresholdThenHalf() {
        val c = Clock(); val r = FakeRules()
        val s = GameSession(r, c::now)
        assertEquals(DrinkLevel.FULL, s.level())
        c.advance(29 * min)
        assertEquals(DrinkLevel.FULL, s.level())
        c.advance(1 * min)
        assertEquals(DrinkLevel.HALF, s.level())
    }

    @Test fun levelAfterSipAndThresholdIsConfigurable() {
        val c = Clock(); val r = FakeRules().apply { thresholdMin = 10; levelAfter = "SIP"; levelBefore = "HALF" }
        val s = GameSession(r, c::now)
        assertEquals(DrinkLevel.HALF, s.level())
        c.advance(10 * min)
        assertEquals(DrinkLevel.SIP, s.level())
    }

    @Test fun levelAfterRandomOnlyHalfOrSip() {
        val c = Clock(); val r = FakeRules().apply { levelAfter = "RANDOM" }
        val s = GameSession(r, c::now, Random(7))
        c.advance(31 * min)
        val seen = (1..60).map { s.level() }.toSet()
        assertTrue(seen.contains(DrinkLevel.HALF) && seen.contains(DrinkLevel.SIP))
        assertFalse(seen.contains(DrinkLevel.FULL))
    }

    @Test fun idleGapStartsNewNight() {
        val c = Clock(); val r = FakeRules()
        val s = GameSession(r, c::now)
        c.advance(40 * min); s.touch()          // đang hát, đã hơn mốc 30 phút
        assertEquals(DrinkLevel.HALF, s.level())
        c.advance(30 * min); s.onSongStart()      // nghỉ 30 phút < 60: vẫn cùng đêm
        assertEquals(DrinkLevel.HALF, s.level())
        s.touch()
        c.advance(61 * min); s.onSongStart()      // nghỉ 61 phút: đêm mới → cạn ly lại
        assertEquals(DrinkLevel.FULL, s.level())
        assertEquals(0, s.rounds)
    }

    @Test fun idleResetCanBeDisabled() {
        val c = Clock(); val r = FakeRules().apply { idleResetMin = 0 }
        val s = GameSession(r, c::now)
        c.advance(40 * min); s.touch()
        c.advance(500 * min); s.onSongStart()
        assertEquals(DrinkLevel.HALF, s.level())
    }

    @Test fun newNightResetsEverything() {
        val c = Clock(); val r = FakeRules()
        val s = GameSession(r, c::now)
        c.advance(45 * min); s.roundStarted()
        assertEquals(1, s.rounds)
        s.newNight()
        assertEquals(0, s.rounds)
        assertEquals(DrinkLevel.FULL, s.level())
    }

    @Test fun frequencyEveryNSongs() {
        val c = Clock(); val r = FakeRules().apply { frequency = "EVERY2" }
        val s = GameSession(r, c::now)
        assertFalse(s.dueNow()); assertTrue(s.dueNow())
        s.roundStarted()
        assertFalse(s.dueNow()); assertTrue(s.dueNow())
        r.frequency = "EVERY3"; s.roundStarted()
        assertFalse(s.dueNow()); assertFalse(s.dueNow()); assertTrue(s.dueNow())
    }

    @Test fun maxRoundsLimit() {
        val c = Clock(); val r = FakeRules().apply { maxRounds = 2 }
        val s = GameSession(r, c::now)
        assertFalse(s.limitReached()); s.roundStarted(); s.roundStarted()
        assertTrue(s.limitReached())
        r.maxRounds = 0
        assertFalse(s.limitReached())
    }

    @Test fun segmentsFollowSettingsAndQueue() {
        val r = FakeRules(); var hasQueue = true
        val e = GameEngine(r, { hasQueue })
        assertEquals(10, e.availableSegments().size)
        hasQueue = false
        assertFalse(e.availableSegments().contains(GameSegment.NEXT))
        r.segMen = false; r.groupTrait = false
        val ids = e.availableSegments()
        assertFalse(ids.contains(GameSegment.MEN)); assertFalse(ids.contains(GameSegment.TRAIT))
        assertTrue(ids.contains(GameSegment.WOMEN))
        r.groupPosition = false; r.groupGroup = false
        assertTrue(e.availableSegments().isEmpty())
        assertFalse(e.shouldPlay(true))
    }

    @Test fun shouldPlayGuards() {
        val r = FakeRules(); val e = GameEngine(r, { true })
        assertTrue(e.shouldPlay(true))
        assertFalse(e.shouldPlay(false))           // bài bị bỏ qua / lỗi
        r.enabled = false
        assertFalse(e.shouldPlay(true))            // mặc định tắt thì không bao giờ quay
    }

    @Test fun roundRunsSpinThenResultThenClears() = runBlocking {
        val r = FakeRules().apply { resultSec = 1 }
        val e = GameEngine(r, { true }, spinMs = 150L, random = Random(3))
        val job = async { e.play() }
        delay(40)
        val spinning = e.round.value!!
        assertEquals("SPINNING", spinning.phase)
        assertEquals(10, spinning.labels.size)
        assertTrue(spinning.resultIndex in 0..9)
        assertTrue(spinning.action.endsWith("CẠN LY!"))
        delay(250)
        val res = e.round.value!!
        assertEquals("RESULT", res.phase)
        assertEquals(spinning.title, res.title)             // kết quả không đổi giữa chừng
        assertEquals(spinning.resultIndex, res.resultIndex)
        assertTrue(job.await())
        assertNull(e.round.value)
        assertEquals(1, e.session.rounds)
    }

    @Test fun skipEndsRoundEarlyAndTestRoundIsNotCounted() = runBlocking {
        val r = FakeRules().apply { resultSec = 10 }
        val e = GameEngine(r, { true }, spinMs = 5_000L)
        val job = async { e.play(test = true) }
        delay(30)
        assertTrue(e.round.value!!.test)
        e.skip()
        assertTrue(job.await())
        assertNull(e.round.value)
        assertEquals(0, e.session.rounds)
    }

    @Test fun secondRoundRefusedWhileRunning() = runBlocking {
        val r = FakeRules()
        val e = GameEngine(r, { true }, spinMs = 5_000L)
        val a = async { e.play() }
        delay(30)
        assertFalse(e.play())            // đang có vòng
        assertFalse(e.shouldPlay(true))
        e.dismiss()
        assertTrue(a.await())
        assertNull(e.round.value)
    }

    @Test fun cancelClearsRound() = runBlocking {
        val r = FakeRules()
        val e = GameEngine(r, { true }, spinMs = 5_000L)
        val a = async { e.play() }
        delay(30)
        a.cancel()
        a.join()
        assertNull(e.round.value)
    }

    @Test fun allSegmentUsesDoWording() = runBlocking {
        val r = FakeRules().apply { groupPosition = false; segMen = false; segWomen = false; groupTrait = false; resultSec = 5 }
        val e = GameEngine(r, { true }, spinMs = 5_000L)
        val a = async { e.play() }
        delay(30)
        val round = e.round.value!!
        assertEquals("CẢ PHÒNG", round.title)
        assertEquals("DÔ! CẠN LY!", round.action)
        e.dismiss(); a.await()
        Unit
    }

    @Test fun traitUsesTraitText() = runBlocking {
        val r = FakeRules().apply { groupPosition = false; groupGroup = false }
        val e = GameEngine(r, { true }, spinMs = 5_000L)
        val a = async { e.play() }
        delay(30)
        val round = e.round.value!!
        val trait = GamePreset.TRAITS.first { it.title == round.title }
        // Ô trên vòng xoay phải ghi đúng đặc điểm sẽ trúng, không phải chữ "Đặc điểm" chung chung
        assertEquals(trait.label, round.labels[round.resultIndex])
        e.dismiss(); a.await()
        Unit
    }
}
