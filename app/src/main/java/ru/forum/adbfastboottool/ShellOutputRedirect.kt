package ru.forum.adbfastboottool

import java.util.Locale

/**
 * Pure parser for a desktop-style output redirect in the ADB terminal:
 * `adb logcat -d > logcat.txt` or `adb shell dumpsys battery >> battery.txt`.
 *
 * NekoFlash has no host shell, so the `>` is interpreted here, exactly like a PC
 * shell would: an UNQUOTED `>` / `>>` means "save the command's output on THIS
 * phone". The redirect is cut out of the command line and the rest is sent to the
 * target device untouched.
 *
 * Deliberately NOT treated as a host redirect (left for the target device as before):
 *  - a `>` inside single or double quotes, for example `sh -c "echo x > /sdcard/f"`;
 *  - an escaped `\>`;
 *  - file-descriptor forms such as `2>`, `2>&1`, `&>` and `>&2`.
 *
 * Quote and escape handling mirrors MainActivity.tokenizeCommandLine so both agree
 * on where a word starts and ends. No Android dependencies: safe for plain unit tests.
 */
object ShellOutputRedirect {

    data class Redirect(
        /** Command line with the redirect removed, ready to be parsed as a normal command. */
        val command: String,
        /** Unquoted target path exactly as typed (relative, absolute or directory). */
        val target: String,
        /** `>>` (append) instead of `>` (overwrite). */
        val append: Boolean
    )

    sealed class Result {
        /** No host-side redirect: the line is handled exactly as before. */
        object None : Result()
        data class Found(val redirect: Redirect) : Result()
        data class Invalid(val message: String) : Result()
    }

    private class Target(val value: String, val end: Int)

    fun parse(line: String): Result {
        var quote: Char? = null
        var escaping = false
        var found: Redirect? = null
        val remaining = StringBuilder()
        var i = 0

        while (i < line.length) {
            val ch = line[i]
            when {
                escaping -> {
                    escaping = false
                    remaining.append(ch)
                    i++
                }
                ch == '\\' -> {
                    escaping = true
                    remaining.append(ch)
                    i++
                }
                quote != null -> {
                    if (ch == quote) quote = null
                    remaining.append(ch)
                    i++
                }
                ch == '\'' || ch == '"' -> {
                    quote = ch
                    remaining.append(ch)
                    i++
                }
                ch == '>' && isHostRedirect(line, i) -> {
                    if (found != null) {
                        return Result.Invalid("Only one output redirect (>) is supported per command")
                    }
                    val append = line.getOrNull(i + 1) == '>'
                    val target = readTarget(line, i + if (append) 2 else 1)
                        ?: return Result.Invalid("File name is missing after ${if (append) ">>" else ">"}")
                    found = Redirect(command = "", target = target.value, append = append)
                    i = target.end
                }
                else -> {
                    remaining.append(ch)
                    i++
                }
            }
        }

        val redirect = found ?: return Result.None
        return Result.Found(redirect.copy(command = remaining.toString().trim()))
    }

    /** `>` at [index] starts a host redirect unless it is a file-descriptor form. */
    private fun isHostRedirect(line: String, index: Int): Boolean {
        val prev = line.getOrNull(index - 1)
        if (prev == '&' || prev == '>') return false
        // "2>" / "2>>" / "2>&1": a lone digit that starts a word is a descriptor number.
        if (prev != null && prev.isDigit() && (index < 2 || line[index - 2].isWhitespace())) return false
        val operatorEnd = if (line.getOrNull(index + 1) == '>') index + 2 else index + 1
        val next = line.getOrNull(operatorEnd)
        return next != '&' && next != '|'
    }

    /** Reads one (optionally quoted) word after the operator. Null when there is none. */
    private fun readTarget(line: String, start: Int): Target? {
        var i = start
        while (i < line.length && line[i].isWhitespace()) i++
        if (i >= line.length || line[i] == '>' || line[i] == '<' || line[i] == '|' || line[i] == '&') return null

        val value = StringBuilder()
        var quote: Char? = null
        var escaping = false
        while (i < line.length) {
            val ch = line[i]
            if (escaping) {
                value.append(ch)
                escaping = false
            } else if (ch == '\\') {
                escaping = true
            } else if (quote != null) {
                if (ch == quote) quote = null else value.append(ch)
            } else if (ch == '\'' || ch == '"') {
                quote = ch
            } else if (ch.isWhitespace()) {
                break
            } else {
                value.append(ch)
            }
            i++
        }
        if (value.isBlank()) return null
        return Target(value.toString(), i)
    }

    /** Same units and precision as the other transfer logs: "512 B", "84.20 KB", "2.40 MB". */
    fun formatSize(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.coerceAtLeast(0L).toDouble()
        var unit = 0
        while (value >= 1024.0 && unit < units.lastIndex) {
            value /= 1024.0
            unit++
        }
        return if (unit == 0) "${bytes.coerceAtLeast(0L)} B" else String.format(Locale.US, "%.2f %s", value, units[unit])
    }

    /** "0.4s", "12s", "3m 05s". */
    fun formatElapsed(ms: Long): String {
        if (ms < 0L) return "unknown"
        if (ms < 1000L) return String.format(Locale.US, "%.1fs", ms / 1000.0)
        val totalSeconds = ms / 1000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return if (minutes > 0L) String.format(Locale.US, "%dm %02ds", minutes, seconds) else "${totalSeconds}s"
    }
}
