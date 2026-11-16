package com.karaokebox.core.text

/** Làm gọn tiêu đề video YouTube: bỏ hashtag (#LIVETAISINH...) và dấu phân cách thừa ở cuối/đầu. */
object TitleCleaner {
    private val hashtag = Regex("(^|\\s)#\\p{L}[\\p{L}\\p{N}_]*")
    private val spaces = Regex("\\s+")
    private val edgeSep = Regex("^[\\s|\\-–—·:]+|[\\s|\\-–—·:]+$")

    fun clean(raw: String): String {
        val s = raw.replace(hashtag, " ").replace(spaces, " ").replace(edgeSep, "")
        return s.ifBlank { raw.trim() }
    }
}
