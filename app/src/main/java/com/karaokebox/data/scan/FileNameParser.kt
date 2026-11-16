package com.karaokebox.data.scan

import java.util.Locale

data class ParsedName(val code: String?, val title: String, val artist: String)

/**
 * Hỗ trợ:
 *  "12345 - Tên Bài - Ca Sĩ.mp4"
 *  "Tên Bài - Ca Sĩ.mkv"
 *  "12345 - Tên Bài.mkv"
 *  "Tên Bài.mkv"
 */
object FileNameParser {
    const val UNKNOWN_ARTIST = "Không rõ"

    private val opt = setOf(RegexOption.IGNORE_CASE)
    private val junkTags = Regex("[\\[(]\\s*(karaoke|beat|hd|full\\s*hd|full|1080p|720p|vocal)\\s*[\\])]", opt)
    private val junkSuffix = Regex("\\s+(karaoke|hd|full\\s*hd)$", opt)
    private val spaces = Regex("\\s+")
    private val patA = Regex("^(\\d{3,7})\\s*[-–—.]\\s*(.+?)\\s+[-–—]\\s+(.+)$")
    private val patCodeOnly = Regex("^(\\d{3,7})\\s*[-–—.]\\s*(.+)$")
    private val patB = Regex("^(.+?)\\s+[-–—]\\s+(.+)$")

    fun parse(fileName: String, swap: Boolean = false): ParsedName {
        var base = fileName.substringBeforeLast('.', fileName).replace('_', ' ')
        base = junkTags.replace(base, "")
        base = junkSuffix.replace(base, "")
        base = spaces.replace(base, " ").trim()

        patA.matchEntire(base)?.let { m ->
            return make(m.groupValues[1], m.groupValues[2], m.groupValues[3], swap)
        }
        patCodeOnly.matchEntire(base)?.let { m ->
            val rest = m.groupValues[2]
            if (!Regex("\\s[-–—]\\s").containsMatchIn(rest)) {
                return ParsedName(m.groupValues[1], rest.trim(), UNKNOWN_ARTIST)
            }
        }
        patB.matchEntire(base)?.let { m ->
            return make(null, m.groupValues[1], m.groupValues[2], swap)
        }
        return ParsedName(null, base.ifEmpty { fileName }, UNKNOWN_ARTIST)
    }

    private fun make(code: String?, a: String, b: String, swap: Boolean): ParsedName {
        val t = (if (swap) b else a).trim()
        val ar = (if (swap) a else b).trim()
        return ParsedName(code, t, ar.ifEmpty { UNKNOWN_ARTIST })
    }

    fun ext(file: java.io.File): String = file.extension.lowercase(Locale.ROOT)
}
