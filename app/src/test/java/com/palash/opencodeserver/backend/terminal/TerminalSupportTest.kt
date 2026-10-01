package com.palash.opencodeserver.backend.terminal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec 65/67 - terminal key encoding and bounded scrollback.
 *
 * These are pure functions with no PTY dependency, which is what makes them testable off-device.
 */
class TerminalSupportTest {

    // ---- key encoding (spec 65/66) -----------------------------------------------------------

    @Test
    fun controlLetterMapsToControlCode() {
        assertArrayEquals(byteArrayOf(0x03), TerminalKeyEncoder.encode(SpecialKey.CTRL_C))
        assertArrayEquals(byteArrayOf(0x04), TerminalKeyEncoder.encode(SpecialKey.CTRL_D))
        assertArrayEquals(byteArrayOf(0x0C), TerminalKeyEncoder.encode(SpecialKey.CTRL_L))
    }

    @Test
    fun ctrlModifierOnLiteralProducesControlCode() {
        // With CTRL latched, typing "c" must send 0x03, not the character.
        val bytes = TerminalKeyEncoder.encode(SpecialKey.TAB, ModifierState(ctrl = true), literal = 'c')
        assertArrayEquals(byteArrayOf(0x03), bytes)
    }

    @Test
    fun ctrlModifierIsCaseInsensitive() {
        val upper = TerminalKeyEncoder.encode(SpecialKey.TAB, ModifierState(ctrl = true), literal = 'C')
        assertArrayEquals(byteArrayOf(0x03), upper)
    }

    @Test
    fun altModifierEscapesLiteral() {
        val bytes = TerminalKeyEncoder.encode(SpecialKey.TAB, ModifierState(alt = true), literal = 'b')
        assertArrayEquals(byteArrayOf(0x1B, 'b'.code.toByte()), bytes)
    }

    @Test
    fun plainLiteralIsSentVerbatim() {
        assertArrayEquals(
            "x".toByteArray(),
            TerminalKeyEncoder.encode(SpecialKey.TAB, ModifierState(), literal = 'x')
        )
    }

    @Test
    fun ctrlWithUnmappedCharacterStillSendsTheLiteral() {
        // Ctrl+[ has no control-code mapping; sending the literal is better than dropping the key.
        val bytes = TerminalKeyEncoder.encode(SpecialKey.TAB, ModifierState(ctrl = true), literal = '[')
        assertArrayEquals("[".toByteArray(), bytes)
    }

    @Test
    fun keyWithoutModifiersUsesItsOwnSequence() {
        assertArrayEquals(byteArrayOf(0x1B, 0x5B, 0x41), TerminalKeyEncoder.encode(SpecialKey.ARROW_UP))
    }

    @Test
    fun arrowSequencesAreStandardXterm() {
        assertArrayEquals(byteArrayOf(0x1B, 0x5B, 0x41), SpecialKey.ARROW_UP.bytes)
        assertArrayEquals(byteArrayOf(0x1B, 0x5B, 0x42), SpecialKey.ARROW_DOWN.bytes)
        assertArrayEquals(byteArrayOf(0x1B, 0x5B, 0x43), SpecialKey.ARROW_RIGHT.bytes)
        assertArrayEquals(byteArrayOf(0x1B, 0x5B, 0x44), SpecialKey.ARROW_LEFT.bytes)
    }

    @Test
    fun eofIsCtrlD() {
        assertArrayEquals(byteArrayOf(0x04), SpecialKey.CTRL_D.bytes)
    }

    @Test
    fun developerSymbolPaletteCoversTheSpecList() {
        val expected = listOf("/", "\\", "|", "~", "_", "-", "=", ":", ";", "&", "$", "@", "#", "%", "^", "`",
            "<", ">", "(", ")", "[", "]", "{", "}")
        expected.forEach { symbol ->
            assertTrue("$symbol should be on the palette", TerminalKeyEncoder.developerSymbols.contains(symbol))
        }
    }

    @Test
    fun stickyModifierIsConsumedAfterOneKey() {
        val consumed = ModifierState(ctrl = true, sticky = true).consumedAfterKey()
        assertEquals(false, consumed.ctrl)

        val sticky = ModifierState(ctrl = true, sticky = false).consumedAfterKey()
        assertEquals(true, sticky.ctrl)
    }

    // ---- scrollback (spec 67/105) -------------------------------------------------------------

    @Test
    fun bufferKeepsOnlyTheConfiguredLineCount() {
        val buffer = ScrollbackBuffer(3)
        repeat(10) { buffer.append("line $it") }

        assertEquals(3, buffer.size())
        assertEquals(listOf("line 7", "line 8", "line 9"), buffer.snapshot())
    }

    @Test
    fun droppedLinesAreReportedRatherThanHidden() {
        val buffer = ScrollbackBuffer(2)
        repeat(5) { buffer.append("line $it") }

        assertEquals(3L, buffer.dropped())
    }

    @Test
    fun increasingTheLimitCannotRecoverDroppedLines() {
        val buffer = ScrollbackBuffer(2)
        repeat(5) { buffer.append("line $it") }

        buffer.changeLimit(100)

        assertEquals(2, buffer.size())
        // The UI must be able to tell the user history was lost rather than showing a partial buffer
        // as if it were complete.
        assertEquals(3L, buffer.dropped())
    }

    @Test
    fun decreasingTheLimitTrimsImmediately() {
        val buffer = ScrollbackBuffer(10)
        repeat(10) { buffer.append("line $it") }

        buffer.changeLimit(3)

        assertEquals(3, buffer.size())
        assertEquals(listOf("line 7", "line 8", "line 9"), buffer.snapshot())
    }

    @Test
    fun unlimitedBufferDoesNotDrop() {
        val buffer = ScrollbackBuffer(Int.MAX_VALUE)
        repeat(50_000) { buffer.append("line $it") }

        assertEquals(50_000, buffer.size())
        assertEquals(0L, buffer.dropped())
        assertTrue(buffer.isUnbounded)
    }

    @Test
    fun tailReturnsTheRequestedNumberOfLines() {
        val buffer = ScrollbackBuffer(100)
        repeat(10) { buffer.append("line $it") }

        assertEquals(listOf("line 8", "line 9"), buffer.tail(2))
    }

    @Test
    fun clearResetsBufferAndDropCount() {
        val buffer = ScrollbackBuffer(2)
        repeat(5) { buffer.append("line $it") }

        buffer.clear()

        assertEquals(0, buffer.size())
        assertEquals(0L, buffer.dropped())
    }

    @Test
    fun appendAllAppliesTheSameBound() {
        val buffer = ScrollbackBuffer(2)
        buffer.appendAll(listOf("a", "b", "c", "d"))

        assertEquals(listOf("c", "d"), buffer.snapshot())
    }

    @Test
    fun zeroLimitKeepsNothing() {
        val buffer = ScrollbackBuffer(0)
        buffer.append("only line")

        assertEquals(0, buffer.size())
        assertEquals(1L, buffer.dropped())
    }

    // ---- geometry (spec 64) -------------------------------------------------------------------

    @Test
    fun geometryFallsBackToEightyByTwentyFour() {
        assertEquals(80 to 24, defaultGeometry(null, null))
        assertEquals(120 to 40, defaultGeometry(120, 40))
    }

    @Test
    fun geometryIgnoresNonPositiveValues() {
        assertEquals(80 to 24, defaultGeometry(0, -5))
    }
}