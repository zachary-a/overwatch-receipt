package dev.owreceipt.core

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import personal.wp9516.protocol.Wp9516Protocol as Codec

@OptIn(ExperimentalCoroutinesApi::class)
class Wp9516SessionTest {
    private val timing = PrinterTiming(0, 1, 1, 2, 100, 100, 2, 100)
    private class Link : PrinterLink {
        val input = Channel<ByteArray>(Channel.UNLIMITED)
        override val frames = input.receiveAsFlow()
        val sent = mutableListOf<ByteArray>()
        var badRow = false
        var zeroBuffers = 0
        var busyAtEnd = 0
        var printed = false
        override suspend fun write(frame: ByteArray) {
            val payload = Codec.Decoder().accept(frame).single()
            sent += payload
            when (payload[0].toInt() and 255) {
                1 -> {
                    val busy = if (printed && busyAtEnd-- > 0) 1 else 0
                    input.send(byteArrayOf(1, 48, 0, 64, 2, busy.toByte(), 0, 0, 0, 0, 1, 50, 1))
                }
                0xac -> input.send(byteArrayOf(0xac.toByte()))
                4 -> input.send(byteArrayOf(4, 0))
                2 -> {
                    var row = Codec.u16(payload, 3)
                    if (badRow && row > 0) row--
                    val buffer = if (zeroBuffers-- > 0) 0 else 149
                    input.send(byteArrayOf(0xa5.toByte(), buffer.toByte(), 0, row.toByte(), (row shr 8).toByte(), 7))
                }
                3 -> printed = true
            }
        }
        override fun close() { input.close() }
    }
    @Test fun capturedFramesSurviveFragmentationAndReencode() {
        val vectors = javaClass.getResourceAsStream("/wp9516-vectors.hex")!!.bufferedReader().readLines().filter { it.isNotBlank() }
        assertEquals(2534, vectors.size)
        val decoder = Codec.Decoder()
        for (line in vectors) {
            val frame = line.split(' ').filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()
            val packets = frame.toList().chunked(3).flatMap { decoder.accept(it.toByteArray()) }
            assertEquals(1, packets.size)
            val payload = packets.single()
            assertArrayEquals(frame, Codec.frame(payload))
            when (payload[0].toInt() and 255) {
                2 -> if (payload.size == 8) assertArrayEquals(frame, Codec.setup(Codec.u16(payload, 1),
                    Codec.u16(payload, 3), payload[5].toInt() and 255, payload[6].toInt() and 255, payload[7].toInt() and 255))
                3 -> assertArrayEquals(frame, Codec.raster(Codec.u16(payload, 1), 576, payload[3].toInt() and 255, payload.copyOfRange(4, payload.size)))
            }
        }
    }
    @Test fun shortStatusPreservesFirmwareAndWidth() {
        val full = Wp9516Session.parseInfo(byteArrayOf(1, 48, 0, 64, 2, 0, 0, 0, 0, 0, 1, 50, 1))
        val short = Wp9516Session.parseInfo(byteArrayOf(1, 48, 0, 64, 2, 0, 0, 0, 0, 1), full)
        assertEquals(576, short.widthDots); assertEquals(50, short.firmware); assertEquals(1, short.transportType)
        assertTrue(short.paperOut)
    }
    @Test fun copiesPaddingTokenAndFinalIdleAreConfirmed() = runTest {
        val link = Link().apply { zeroBuffers = 1; busyAtEnd = 2 }
        val session = Wp9516Session(link, backgroundScope, timing)
        session.initialize()
        val pixels = ByteArray(72 * 32) { (it % 251).toByte() }
        session.print(RenderedReceipt(576, 32, byteArrayOf(1), pixels), "job", PrintSettings(copies = 2, trailingFeedRows = 3))
        val rows = link.sent.filter { it[0].toInt() == 3 }
        assertEquals(70, rows.size)
        rows.forEachIndexed { row, payload ->
            assertEquals(row, Codec.u16(payload, 1))
            val original = row % 35
            assertArrayEquals(if (original < 32) pixels.copyOfRange(original * 72, (original + 1) * 72) else ByteArray(72),
                payload.copyOfRange(4, payload.size))
        }
        val setup = link.sent.filter { it[0].toInt() == 2 }
        assertTrue(setup.all { Codec.u16(it, 1) == 70 })
        assertTrue(setup.drop(1).all { it[5].toInt() == 7 })
        assertEquals(PrintStatus.COMPLETED, session.state.value.status)
        assertTrue(link.sent.count { it[0].toInt() == 1 } >= 5)
        session.close()
    }
    @Test fun unexpectedResumeRowStopsBeforeAnotherBatch() = runTest {
        val link = Link().apply { badRow = true }
        val session = Wp9516Session(link, backgroundScope, timing)
        session.initialize()
        try {
            session.print(RenderedReceipt(576, 40, byteArrayOf(), ByteArray(72 * 40)), "bad", PrintSettings(trailingFeedRows = 0))
            fail("Expected protocol error")
        } catch (_: PrinterProtocolException) { }
        assertEquals(30, link.sent.count { it[0].toInt() == 3 })
        assertEquals(PrintStatus.ERROR, session.state.value.status)
        session.close()
    }
    @Test fun stoppingCancelsProducerBeforeStopFrame() = runTest {
        val link = Link()
        val session = Wp9516Session(link, backgroundScope, timing)
        session.initialize()
        val printing = launch { session.print(RenderedReceipt(576, 100, byteArrayOf(), ByteArray(7200)), "stop", PrintSettings(trailingFeedRows = 0)) }
        advanceTimeBy(10); runCurrent()
        printing.cancelAndJoin()
        session.stop()
        val lastStop = link.sent.indexOfLast { it[0].toInt() == 4 }
        advanceTimeBy(200); runCurrent()
        assertTrue(link.sent.drop(lastStop + 1).none { it[0].toInt() == 3 })
        assertEquals(PrintStatus.STOPPED, session.state.value.status)
        session.close()
    }
    @Test fun paperNotificationPausesUntilExplicitStop() = runTest {
        val link = Link()
        val session = Wp9516Session(link, backgroundScope, timing)
        session.initialize()
        val printing = launch { session.print(RenderedReceipt(576, 100, byteArrayOf(), ByteArray(7200)), "paper", PrintSettings(trailingFeedRows = 0)) }
        advanceTimeBy(5); runCurrent()
        link.input.send(byteArrayOf(1, 48, 0, 64, 2, 0, 0, 0, 0, 1))
        runCurrent()
        val count = link.sent.count { it[0].toInt() == 3 }
        advanceTimeBy(300); runCurrent()
        assertEquals(count, link.sent.count { it[0].toInt() == 3 })
        assertEquals(PrintStatus.PAPER_OUT, session.state.value.status)
        printing.cancelAndJoin(); session.stop(); session.close()
    }
    @Test fun exhaustedBufferTimesOutWithoutSendingRaster() = runTest {
        val link = Link().apply { zeroBuffers = 1000 }
        val session = Wp9516Session(link, backgroundScope, timing)
        session.initialize()
        val error = runCatching { session.print(RenderedReceipt(576, 1, byteArrayOf(), ByteArray(72)),
            "buffer", PrintSettings(trailingFeedRows = 0)) }.exceptionOrNull()
        assertTrue(error is PrinterProtocolException)
        assertEquals(PrintStatus.ERROR, session.state.value.status)
        assertTrue(link.sent.none { it[0].toInt() == 3 })
        session.close()
    }
}
