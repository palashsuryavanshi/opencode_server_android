package com.palash.opencodeserver.backend.terminal

import com.palash.opencodeserver.backend.core.TerminalStatus
import java.util.UUID

/** Spec 31 - the per-session record the frontend renders. */
data class TerminalSession(
    val sessionId: String,
    val workingDirectory: String,
    val displayWorkingDirectory: String,
    val shell: String,
    val processId: Int,
    val status: TerminalStatus,
    val createdAt: Long,
    val columns: Int,
    val rows: Int,
    val backendId: String,
    val supportsFullScreen: Boolean,
    val title: String = "",
    val lastExitCode: Int? = null
) {
    val isAlive: Boolean get() = status == TerminalStatus.RUNNING || status == TerminalStatus.STARTING

    companion object {
        fun newId(): String = "term-${UUID.randomUUID()}"
    }
}

/** Spec 66 - developer characters that are hard to reach on a soft keyboard. */
enum class SpecialKey(val label: String, val sequence: ByteArray?) {
    CTRL_TAB("CTRL+TAB", byteArrayOf(0x09)),
    CTRL_A("CTRL+A", byteArrayOf(0x01)),
    CTRL_C("CTRL+C", byteArrayOf(0x03)),
    CTRL_D("CTRL+D", byteArrayOf(0x04)),
    CTRL_E("CTRL+E", byteArrayOf(0x05)),
    CTRL_K("CTRL+K", byteArrayOf(0x0B)),
    CTRL_L("CTRL+L", byteArrayOf(0x0C)),
    CTRL_R("CTRL+R", byteArrayOf(0x12)),
    CTRL_U("CTRL+U", byteArrayOf(0x15)),
    CTRL_W("CTRL+W", byteArrayOf(0x17)),
    CTRL_Z("CTRL+Z", byteArrayOf(0x1A)),
    ESC("ESC", byteArrayOf(0x1B)),
    TAB("TAB", byteArrayOf(0x09)),
    ENTER("ENTER", byteArrayOf(0x0D)),
    BACKSPACE("BACKSPACE", byteArrayOf(0x7F)),
    DELETE("DELETE", byteArrayOf(0x1B, 0x5B.toByte(), 0x33, 0x7E)),
    ARROW_UP("↑", byteArrayOf(0x1B, 0x5B.toByte(), 0x41)),
    ARROW_DOWN("↓", byteArrayOf(0x1B, 0x5B.toByte(), 0x42)),
    ARROW_RIGHT("→", byteArrayOf(0x1B, 0x5B.toByte(), 0x43)),
    ARROW_LEFT("←", byteArrayOf(0x1B, 0x5B.toByte(), 0x44)),
    HOME("HOME", byteArrayOf(0x1B, 0x5B.toByte(), 0x48)),
    END("END", byteArrayOf(0x1B, 0x5B.toByte(), 0x46)),
    PAGE_UP("PAGEUP", byteArrayOf(0x1B, 0x5B.toByte(), 0x35, 0x7E)),
    PAGE_DOWN("PAGEDOWN", byteArrayOf(0x1B, 0x5B.toByte(), 0x36, 0x7E)),
    // Bracketed paste markers; the terminal strips them but some TUI apps need them.
    PASTE_START("PASTE", byteArrayOf(0x1B, 0x5B, 0x32, 0x30, 0x30, 0x7E)),
    PASTE_END("PASTE-END", byteArrayOf(0x1B, 0x5B, 0x32, 0x30, 0x31, 0x7E));

    val bytes: ByteArray get() = sequence ?: ByteArray(0)
}

/** Spec 65 - the modifier state a special-key toolbar toggles. */
data class ModifierState(
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
    val sticky: Boolean = true
) {
    /** Consume a one-shot modifier after a single key press. */
    fun consumedAfterKey(): ModifierState =
        if (sticky) copy(ctrl = false, alt = false) else this
}

/**
 * Spec 65/66 - turn a toolbar intent into the exact bytes a PTY expects.
 *
 * Centralising this keeps the Compose toolbar dumb and prevents a subtle class of bug where the
 * UI sends a printable character while CTRL is latched.
 */
object TerminalKeyEncoder {

    /** Control characters for Ctrl+A..Ctrl+Z. */
    private fun controlCode(letter: Char): ByteArray? {
        val upper = letter.uppercaseChar()
        if (upper !in 'A'..'Z') return null
        val code = upper.code - 'A'.code + 1
        // Ctrl+@ / Ctrl+Space and Ctrl+? have no letter mapping.
        return byteArrayOf(code.toByte())
    }

    /**
     * Encode a single key press.
     *
     * Returns null when the key should be ignored (an unbound modifier combination).
     */
    fun encode(
        key: SpecialKey,
        modifiers: ModifierState = ModifierState(),
        literal: Char? = null
    ): ByteArray? {
        // With CTRL latched, a letter becomes its control code regardless of the nominal key.
        if (modifiers.ctrl && literal != null) {
            controlCode(literal)?.let { code ->
                return if (modifiers.alt) byteArrayOf(0x1B) + code else code
            }
            // Ctrl with an unmapped character still sends the literal, minus CTRL semantics.
        }
        if (modifiers.alt && literal != null) {
            return byteArrayOf(0x1B) + literal.toString().toByteArray(Charsets.UTF_8)
        }
        if (literal != null) return literal.toString().toByteArray(Charsets.UTF_8)
        return key.bytes.takeIf { it.isNotEmpty() }
    }

    /** Developer punctuation that soft keyboards hide (spec 66). */
    val developerSymbols: List<String> = listOf(
        "/", "\\", "|", "~", "_", "-", "=", ":", ";", "&", "$", "@", "#", "%", "^", "`",
        "<", ">", "(", ")", "[", "]", "{", "}", "\"", "'", "*", "+", "?", "!", "."
    )
}

/** Spec 67/105 - a bounded ring buffer of decoded output lines. */
/**
 * Spec 64 - the initial PTY geometry.
 *
 * The UI reports dimensions that may be absent or zero while it is still measuring (during a
 * rotation, or before the keyboard settles), so a sane default is applied rather than asking a PTY
 * for a zero-sized window.
 */
fun defaultGeometry(columns: Int?, rows: Int?): Pair<Int, Int> {
    val cols = columns?.takeIf { it in MIN_COLUMNS..MAX_COLUMNS } ?: DEFAULT_COLUMNS
    val rws = rows?.takeIf { it in MIN_ROWS..MAX_ROWS } ?: DEFAULT_ROWS
    return cols to rws
}

private const val DEFAULT_COLUMNS = 80
private const val DEFAULT_ROWS = 24
private const val MIN_COLUMNS = 20
private const val MAX_COLUMNS = 500
private const val MIN_ROWS = 5
private const val MAX_ROWS = 200

/** Spec 67/105 - a bounded ring buffer of decoded output lines. */
class ScrollbackBuffer(initialMaxLines: Int) {

    @Volatile
    var maxLines: Int = initialMaxLines.coerceAtLeast(0)
        private set

    private val lines = ArrayDeque<String>()
    private var droppedLines: Long = 0

    val isUnbounded: Boolean get() = maxLines == Int.MAX_VALUE

    @Synchronized
    fun append(line: String) {
        lines.addLast(line)
        trim()
    }

    @Synchronized
    fun appendAll(newLines: List<String>) {
        newLines.forEach { lines.addLast(it) }
        trim()
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    @Synchronized
    fun tail(count: Int): List<String> = lines.toList().takeLast(count.coerceAtLeast(0))

    @Synchronized
    fun size(): Int = lines.size

    /** How many lines have been discarded. Surfaced in Advanced details (spec 67). */
    @Synchronized
    fun dropped(): Long = droppedLines

    @Synchronized
    fun clear() {
        lines.clear()
        droppedLines = 0
    }

    /**
     * Spec 67 - change the scrollback preference.
     *
     * Increasing the limit cannot recover already-dropped lines, which stays visible through
     * [dropped] rather than pretending the history is complete.
     */
    @Synchronized
    fun changeLimit(newMaxLines: Int) {
        val next = newMaxLines.coerceAtLeast(0)
        if (next == maxLines) return
        maxLines = next
        if (next == Int.MAX_VALUE) return
        // Reclaiming requires rewriting the deque, so drop from the front.
        while (lines.size > next) {
            lines.removeFirst()
            droppedLines++
        }
    }

    private fun trim() {
        if (maxLines == Int.MAX_VALUE) return
        while (lines.size > maxLines) {
            lines.removeFirst()
            droppedLines++
        }
    }
}