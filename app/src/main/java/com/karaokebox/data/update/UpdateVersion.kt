package com.karaokebox.data.update

/** Logic thuần (không phụ thuộc Android) để nhận biết bản phát hành mới trên GitHub Releases. */
object UpdateVersion {
    /** Tag do workflow đặt: v<phiên bản app>-np-<phiên bản NewPipe>, ví dụ v1.6.0-np-v0.26.5. */
    data class Tag(val app: String, val np: String)

    private val TAG_RE = Regex("^v(\\d+(?:\\.\\d+)*)-np-(v?\\d+(?:\\.\\d+)*)$")

    fun parseTag(tag: String): Tag? {
        val m = TAG_RE.matchEntire(tag.trim()) ?: return null
        return Tag(m.groupValues[1], m.groupValues[2])
    }

    private fun parts(v: String): List<Int>? {
        val out = ArrayList<Int>()
        for (seg in v.trim().removePrefix("v").removePrefix("V").split('.')) {
            out.add(seg.takeWhile { it.isDigit() }.toIntOrNull() ?: return null)
        }
        return out
    }

    /** So sánh hai số phiên bản; null nếu không đọc được số. */
    fun compare(a: String, b: String): Int? {
        val x = parts(a) ?: return null
        val y = parts(b) ?: return null
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrElse(i) { 0 }
            val q = y.getOrElse(i) { 0 }
            if (p != q) return if (p > q) 1 else -1
        }
        return 0
    }

    /** true nếu bản trên GitHub mới hơn bản đang chạy (so phiên bản app trước, bằng nhau thì so NewPipe). */
    fun isNewer(latestTag: String, curApp: String, curNp: String): Boolean {
        val t = parseTag(latestTag) ?: return false
        val c = compare(t.app, curApp) ?: return false
        if (c != 0) return c > 0
        return (compare(t.np, curNp) ?: 0) > 0
    }
}
