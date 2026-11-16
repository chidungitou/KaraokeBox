package com.karaokebox.ui.overlay

import java.util.Calendar

/** Loại hạt rơi / bay trên màn hình chờ. */
enum class Particles { TWINKLE, BLOSSOM, SNOW, CONFETTI }

private fun c(v: Long): Int = v.toInt()

/**
 * Giao diện màn hình chờ theo dịp: màu nền, màu chữ 3D, rèm, đèn, thanh nhạc và loại hạt bay.
 * Chọn trong Cài đặt (mặc định "Tự động theo ngày": Tết / Giáng sinh tự bật đúng dịp).
 */
enum class StageTheme(
    val key: String,
    val label: String,
    /** 4 màu nền sân khấu từ trên xuống dưới. */
    val bg: IntArray,
    /** 4 màu bokeh trôi nổi. */
    val orbs: IntArray,
    val extrudeNear: Int, val extrudeFar: Int, val outline: Int, val glow: Int,
    /** 4 màu mặt chữ 3D từ trên xuống dưới. */
    val face: IntArray,
    val eqTop: Int, val eqBottom: Int,
    /** Màu đèn spotlight. */
    val beam: Int,
    val curtainLight: Int, val curtainDark: Int, val trim: Int,
    /** Màu nhấn: vòng sáng quanh QR, huy hiệu chào khách, dòng chúc. */
    val accent: Int,
    val greeting: String?,
    val particles: Particles,
    val particleColors: IntArray
) {
    DEFAULT(
        "DEFAULT", "Mặc định (tím neon)",
        intArrayOf(c(0xFF0B0714), c(0xFF1D1038), c(0xFF2B1250), c(0xFF0B0714)),
        intArrayOf(c(0xFFFF4FB8), c(0xFF7C5CFF), c(0xFF4FD8FF), c(0xFFFFC24F)),
        c(0xFFB0207E), c(0xFF2A0A4A), c(0xFF3A0B5E), c(0xFFFF3FA8),
        intArrayOf(c(0xFFFFF4C2), c(0xFFFFD56B), c(0xFFFF6FC8), c(0xFFB04BFF)),
        c(0xFFFF6FC8), c(0xFF7C5CFF),
        c(0xFFFFFFFF),
        c(0xFF8E2BC4), c(0xFF2E0B52), c(0xFFFFD56B),
        c(0xFFFFD56B),
        null,
        Particles.TWINKLE,
        intArrayOf(c(0xFFFFFFFF))
    ),
    TET(
        "TET", "Tết",
        intArrayOf(c(0xFF1A0505), c(0xFF3A0A0A), c(0xFF5A1010), c(0xFF1A0505)),
        intArrayOf(c(0xFFFF3B30), c(0xFFFFC107), c(0xFFFF8A3D), c(0xFFFF5C8A)),
        c(0xFFC77700), c(0xFF5A0A0A), c(0xFF4A0808), c(0xFFFFB300),
        intArrayOf(c(0xFFFFF3B0), c(0xFFFFD34D), c(0xFFFFA000), c(0xFFE53935)),
        c(0xFFFFC107), c(0xFFD32F2F),
        c(0xFFFFE9A8),
        c(0xFFE53935), c(0xFF5C0A0A), c(0xFFFFC107),
        c(0xFFFFC107),
        "Chúc Mừng Năm Mới",
        Particles.BLOSSOM,
        // [0] hoa mai vàng, [1] hoa đào hồng
        intArrayOf(c(0xFFFFD21F), c(0xFFFF8FB3))
    ),
    NOEL(
        "NOEL", "Giáng sinh",
        intArrayOf(c(0xFF04120C), c(0xFF0A2A1C), c(0xFF123B28), c(0xFF04120C)),
        intArrayOf(c(0xFFFF3B3B), c(0xFF2ECC71), c(0xFFFFE08A), c(0xFFBFE8FF)),
        c(0xFF9B1B1B), c(0xFF06281A), c(0xFF06281A), c(0xFFFF4D4D),
        intArrayOf(c(0xFFFFFFFF), c(0xFFFFE0E0), c(0xFFFF6B6B), c(0xFFD32F2F)),
        c(0xFF5BE38C), c(0xFFD32F2F),
        c(0xFFE8FFF0),
        c(0xFFC62828), c(0xFF5A0F0F), c(0xFFFFE08A),
        c(0xFF7CFFB2),
        "Giáng Sinh An Lành",
        Particles.SNOW,
        intArrayOf(c(0xFFFFFFFF))
    ),
    BIRTHDAY(
        "BIRTHDAY", "Sinh nhật",
        intArrayOf(c(0xFF0F0A24), c(0xFF24104A), c(0xFF3A1566), c(0xFF0F0A24)),
        intArrayOf(c(0xFFFF4FB8), c(0xFF4FD8FF), c(0xFFFFE04F), c(0xFF7CFF6B)),
        c(0xFFD81B8A), c(0xFF2A0A4A), c(0xFF3A0B5E), c(0xFFFF4FB8),
        intArrayOf(c(0xFFFFF4C2), c(0xFFFFD54F), c(0xFFFF6FC8), c(0xFF40C4FF)),
        c(0xFF4FD8FF), c(0xFFFF4FB8),
        c(0xFFFFFFFF),
        c(0xFFE91E8C), c(0xFF5A0A47), c(0xFFFFE04F),
        c(0xFFFFE04F),
        "Chúc Mừng Sinh Nhật",
        Particles.CONFETTI,
        intArrayOf(c(0xFFFF4FB8), c(0xFF4FD8FF), c(0xFFFFE04F), c(0xFF7CFF6B), c(0xFFB04BFF))
    );

    companion object {
        const val AUTO = "AUTO"

        /** Các lựa chọn trong Cài đặt: (khóa lưu, tên hiển thị). */
        val MODES: List<Pair<String, String>> = listOf(
            AUTO to "Tự động theo ngày (Tết, Giáng sinh)",
            DEFAULT.key to DEFAULT.label,
            TET.key to TET.label,
            NOEL.key to NOEL.label,
            BIRTHDAY.key to BIRTHDAY.label
        )

        /** Mùng 1 Tết âm lịch (tháng, ngày dương lịch) 2026-2035. Ngoài bảng thì tự động quay về Mặc định. */
        private val TET_DATES = mapOf(
            2026 to (Calendar.FEBRUARY to 17), 2027 to (Calendar.FEBRUARY to 6),
            2028 to (Calendar.JANUARY to 26), 2029 to (Calendar.FEBRUARY to 13),
            2030 to (Calendar.FEBRUARY to 3), 2031 to (Calendar.JANUARY to 23),
            2032 to (Calendar.FEBRUARY to 11), 2033 to (Calendar.JANUARY to 31),
            2034 to (Calendar.FEBRUARY to 19), 2035 to (Calendar.FEBRUARY to 8)
        )

        /** Giao diện tự động theo ngày hôm nay: 10 ngày trước đến 14 ngày sau mùng 1 Tết; 12-26/12 là Giáng sinh. */
        fun auto(now: Calendar = Calendar.getInstance()): StageTheme {
            val year = now.get(Calendar.YEAR)
            // Tết luôn rơi vào cuối tháng 1 - tháng 2, cửa sổ -10..+14 ngày không bao giờ sang năm khác
            TET_DATES[year]?.let { (month, day) ->
                val tet = Calendar.getInstance().apply {
                    clear(); set(year, month, day, 12, 0, 0)
                }
                val today = Calendar.getInstance().apply {
                    clear(); set(year, now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH), 12, 0, 0)
                }
                val diffDays = Math.round((today.timeInMillis - tet.timeInMillis) / 86_400_000.0).toInt()
                if (diffDays in -10..14) return TET
            }
            if (now.get(Calendar.MONTH) == Calendar.DECEMBER && now.get(Calendar.DAY_OF_MONTH) in 12..26) return NOEL
            return DEFAULT
        }

        /** Đổi giá trị đã lưu trong Cài đặt thành giao diện thật sự dùng hôm nay. */
        fun resolve(mode: String?): StageTheme =
            if (mode == null || mode == AUTO) auto()
            else values().firstOrNull { it.key == mode } ?: DEFAULT

        /** Chữ hiển thị trên nút Cài đặt (kèm giao diện đang dùng nếu là Tự động). */
        fun describe(mode: String?): String {
            val m = mode ?: AUTO
            return if (m == AUTO) "Tự động theo ngày (đang dùng: ${auto().label})"
            else MODES.firstOrNull { it.first == m }?.second ?: DEFAULT.label
        }
    }
}
