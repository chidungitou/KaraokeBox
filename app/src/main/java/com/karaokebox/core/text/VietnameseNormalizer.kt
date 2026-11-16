package com.karaokebox.core.text

import java.text.Normalizer
import java.util.Locale

/** Bỏ dấu tiếng Việt, hạ chữ thường, tạo chữ cái đầu để tìm kiếm. */
object VietnameseNormalizer {
    private val marks = Regex("\\p{Mn}+")
    private val nonWord = Regex("[^a-z0-9\\s]")
    private val spaces = Regex("\\s+")

    fun normalize(s: String): String {
        val d = s.replace('đ', 'd').replace('Đ', 'D')
        val nfd = Normalizer.normalize(d, Normalizer.Form.NFD)
        val noMarks = marks.replace(nfd, "").lowercase(Locale.ROOT)
        return spaces.replace(nonWord.replace(noMarks, " "), " ").trim()
    }

    /** Nhận chuỗi ĐÃ chuẩn hóa: "lac troi" -> "lt". */
    fun initials(normalized: String): String =
        normalized.split(' ').filter { it.isNotEmpty() }.map { it[0] }.joinToString("")
}
