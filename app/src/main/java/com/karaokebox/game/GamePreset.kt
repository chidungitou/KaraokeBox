package com.karaokebox.game

/** Mốc thời gian đơn điệu (ms), không phụ thuộc Android để logic trò chơi chạy thử được trên JVM. */
fun nowMs(): Long = System.nanoTime() / 1_000_000L

/** Nhóm ô trên vòng xoay (mỗi nhóm bật/tắt riêng trong Cài đặt > Trò chơi). */
enum class GameGroup { POSITION, GROUP, TRAIT }

/** Mức uống. [label] là chữ hiện trên TV. */
enum class DrinkLevel(val key: String, val label: String) {
    FULL("FULL", "CẠN LY"),
    HALF("HALF", "MỘT NỬA"),
    SIP("SIP", "NHẤP MÔI")
}

/**
 * Các ô của vòng xoay. Không dùng tên người (điện thoại thường chỉ là "Khách") nên mọi ô đều mô tả theo VỊ TRÍ / NHÓM / ĐẶC ĐIỂM.
 * [label] ngắn để vẽ lên ô; [title] là câu hiện ở kết quả (viết hoa); [weight] là trọng số khi random.
 */
enum class GameSegment(val group: GameGroup, val label: String, val title: String, val weight: Double) {
    SELF(GameGroup.POSITION, "Người vừa hát", "NGƯỜI VỪA HÁT", 1.5),
    LEFT(GameGroup.POSITION, "Bên trái", "BÊN TRÁI NGƯỜI VỪA HÁT", 1.2),
    RIGHT(GameGroup.POSITION, "Bên phải", "BÊN PHẢI NGƯỜI VỪA HÁT", 1.2),
    BOTH(GameGroup.POSITION, "Hai bên", "HAI BÊN NGƯỜI VỪA HÁT", 0.7),
    OPPOSITE(GameGroup.POSITION, "Đối diện", "NGƯỜI NGỒI ĐỐI DIỆN NGƯỜI VỪA HÁT", 1.0),
    NEXT(GameGroup.POSITION, "Hát kế tiếp", "NGƯỜI HÁT KẾ TIẾP", 1.0),
    ALL(GameGroup.GROUP, "Cả phòng", "CẢ PHÒNG", 1.0),
    MEN(GameGroup.GROUP, "Các anh", "CÁC ANH", 0.8),
    WOMEN(GameGroup.GROUP, "Các chị", "CÁC CHỊ", 0.8),
    TRAIT(GameGroup.TRAIT, "Đặc điểm", "", 1.2)
}

/** Dữ liệu chỉnh "gu" trò chơi: sửa ở đây (giống StudioPreset của phần âm thanh). */
object GamePreset {
    /** Đặc điểm ngẫu nhiên cho ô "Đặc điểm" (viết hoa vì hiện thẳng trên TV). */
    val TRAITS = listOf(
        "AI ĐEO KÍNH",
        "AI MẶC ÁO TỐI MÀU",
        "AI ĐANG CẦM ĐIỆN THOẠI",
        "NGƯỜI TRẺ NHẤT PHÒNG",
        "NGƯỜI LỚN TUỔI NHẤT PHÒNG",
        "NGƯỜI NGỒI GẦN CỬA NHẤT",
        "AI ĐANG CẦM LY",
        "NGƯỜI CAO NHẤT PHÒNG"
    )
}

/** Các lựa chọn cố định trong Cài đặt trò chơi (dùng chung cho TV, web và kiểm tra hợp lệ ở API). */
object GameOptions {
    val FREQUENCIES = listOf("EVERY" to "Mỗi bài", "EVERY2" to "Cách 2 bài", "EVERY3" to "Cách 3 bài", "RANDOM" to "Ngẫu nhiên 50%")
    val LEVEL_BEFORE = listOf("FULL" to "Cạn ly", "HALF" to "Một nửa")
    val LEVEL_AFTER = listOf("HALF" to "Một nửa", "SIP" to "Nhấp môi", "RANDOM" to "Ngẫu nhiên (một nửa / nhấp môi)")
    val THRESHOLD_MIN = listOf(10, 15, 20, 30, 45, 60, 90, 120, 180)
    /** 0 = tắt (không tự tính lại phiên). */
    val IDLE_RESET_MIN = listOf(0, 15, 30, 45, 60, 90, 120, 180)
    /** 0 = không giới hạn. */
    val MAX_ROUNDS = listOf(0, 3, 5, 8, 10, 15, 20)
    val RESULT_SEC = (5..15).toList()

    fun keys(list: List<Pair<String, String>>): List<String> = list.map { it.first }
    fun label(list: List<Pair<String, String>>, key: String): String = list.firstOrNull { it.first == key }?.second ?: key
}
