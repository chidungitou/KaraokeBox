package com.karaokebox.player.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class MicProcessorTest {

    private val rate = 44100

    private fun sine(n: Int, amp: Int, hz: Double = 440.0, startAt: Int = 0) =
        ShortArray(n) { (amp * sin(2 * PI * hz * (startAt + it) / rate)).toInt().toShort() }

    private class Block(val l: IntArray, val r: IntArray)

    /** Chạy [input] qua bộ xử lý theo từng khối 10 ms như MicEngine, trả về hai kênh. */
    private fun run(p: MicProcessor, input: ShortArray, frame: Int = rate / 100): Block {
        val l = ArrayList<Int>(); val r = ArrayList<Int>()
        val out = ByteBuffer.allocate(frame * 4).order(ByteOrder.LITTLE_ENDIAN)
        var i = 0
        while (i < input.size) {
            val n = minOf(frame, input.size - i)
            out.clear()
            val bytes = p.process(input.copyOfRange(i, i + n), n, out)
            assertEquals("byte count", (n * 4).toLong(), bytes.toLong())
            out.flip()
            while (out.remaining() >= 4) { l += out.short.toInt(); r += out.short.toInt() }
            i += n
        }
        return Block(l.toIntArray(), r.toIntArray())
    }

    private fun energy(a: IntArray, from: Int = 0, to: Int = a.size): Double {
        var e = 0.0
        for (i in from until to) e += a[i].toDouble() * a[i]
        return e
    }

    @Test fun silenceStaysSilent() {
        val p = MicProcessor(rate).apply { configure(60, 30) }
        val b = run(p, ShortArray(rate / 2))
        assertTrue("không được sinh tiếng từ im lặng", b.l.all { it == 0 } && b.r.all { it == 0 })
    }

    @Test fun volumeZeroIsSilent() {
        val p = MicProcessor(rate).apply { configure(0, 100) }
        val b = run(p, sine(rate / 2, 20000))
        assertTrue("âm lượng 0 phải im", b.l.all { it == 0 } && b.r.all { it == 0 })
    }

    @Test fun louderVolumeGivesLouderOutput() {
        val quiet = run(MicProcessor(rate).apply { configure(20, 0) }, sine(rate, 3000))
        val loud = run(MicProcessor(rate).apply { configure(80, 0) }, sine(rate, 3000))
        assertTrue("80% phải to hơn 20%", energy(loud.l) > energy(quiet.l) * 2)
    }

    @Test fun dryMicIsIdenticalOnBothChannels() {
        val b = run(MicProcessor(rate).apply { configure(60, 0) }, sine(rate / 2, 8000))
        assertTrue("vang 0 thì L = R", b.l.indices.all { b.l[it] == b.r[it] })
        assertTrue("phải có tiếng", energy(b.l) > 0)
    }

    @Test fun echoLeavesTailAfterSoundStops() {
        val burst = sine(rate / 10, 12000)
        val input = ShortArray(rate) .also { burst.copyInto(it) }       // 100 ms tiếng rồi im 900 ms
        val wet = run(MicProcessor(rate).apply { configure(60, 100) }, input)
        val dry = run(MicProcessor(rate).apply { configure(60, 0) }, input)
        val from = rate / 5                                               // sau khi tiếng đã dứt 100 ms
        assertTrue("vang 100% phải còn đuôi", energy(wet.l, from) > energy(dry.l, from) * 10 + 1.0)
    }

    @Test fun neverExceedsLimiterCeiling() {
        val loud = ShortArray(rate) { if ((it / 50) % 2 == 0) 32767 else (-32768).toShort() }
        val b = run(MicProcessor(rate).apply { configure(100, 100) }, loud)
        val peak = maxOf(b.l.maxOf { abs(it) }, b.r.maxOf { abs(it) })
        assertTrue("đỉnh $peak vượt trần limiter", peak <= 30200)
    }

    @Test fun settingsCanChangeWhileRunning() {
        val p = MicProcessor(rate)
        val first = run(p, sine(rate / 4, 6000))
        p.configure(100, 100)
        val second = run(p, sine(rate / 4, 6000, startAt = rate / 4))
        p.configure(0, 0)
        val third = run(p, sine(rate / 4, 6000, startAt = rate / 2))
        assertTrue("phải có tiếng trước khi đổi", energy(first.l) > 0 && energy(second.l) > 0)
        // âm lượng 0 làm hết tín hiệu mới; chỉ còn đuôi vang mờ dần, nhỏ hơn hẳn đoạn đang hát
        assertTrue("sau khi tắt âm lượng chỉ còn đuôi", energy(third.l) < energy(second.l) / 4)
    }

    @Test fun handlesOddBlockSizes() {
        val p = MicProcessor(rate).apply { configure(60, 30) }
        val b = run(p, sine(1234, 5000), frame = 97)
        assertEquals(1234L, b.l.size.toLong())
    }
}
