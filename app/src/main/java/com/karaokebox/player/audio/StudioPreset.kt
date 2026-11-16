package com.karaokebox.player.audio

/**
 * Thông số của một hiệu ứng ở cường độ 100%. Khi người dùng kéo cường độ xuống, các mức dB / mix / độ rộng
 * được nội suy từ "trung tính" (0 dB, không nén, không vang) tới giá trị dưới đây.
 */
data class StudioParams(
    val lowDb: Float, val lowHz: Float,          // kệ bass (low shelf)
    val mudDb: Float, val mudHz: Float,          // cắt/ nhấn vùng "đục" (peak, thường là số âm)
    val presDb: Float, val presHz: Float,        // độ rõ lời (peak)
    val airDb: Float, val airHz: Float,          // độ sáng / "không khí" (high shelf)
    val compThrDb: Float, val compRatio: Float, val makeupDb: Float,   // nén động
    val reverbMix: Float, val reverbRoom: Float, val reverbDamp: Float, // vang (0..1)
    val width: Float                             // độ rộng stereo (1 = giữ nguyên)
)

/** Các hiệu ứng có sẵn. [params] = null nghĩa là tắt hẳn (âm gốc, không tốn CPU). */
enum class StudioPreset(val key: String, val label: String, val note: String, val params: StudioParams?) {
    OFF("OFF", "Tắt (âm gốc)", "Giữ nguyên âm thanh của file, không xử lý gì thêm.", null),

    STUDIO(
        "STUDIO", "Phòng thu",
        "Sạch, đầy và sáng: nén nhẹ cho đều tiếng, vang ngắn như phòng thu, bass ấm, giọng hát rõ.",
        StudioParams(
            lowDb = 2.0f, lowHz = 120f, mudDb = -1.5f, mudHz = 350f, presDb = 2.0f, presHz = 3200f,
            airDb = 2.5f, airHz = 10000f, compThrDb = -18f, compRatio = 2.5f, makeupDb = 2.0f,
            reverbMix = 0.10f, reverbRoom = 0.55f, reverbDamp = 0.50f, width = 1.15f
        )
    ),
    HALL(
        "HALL", "Hội trường / Nhà hát",
        "Vang dài, không gian rộng như hát trong hội trường; hợp nhạc trữ tình, bolero.",
        StudioParams(
            lowDb = 1.5f, lowHz = 120f, mudDb = -1.0f, mudHz = 350f, presDb = 1.0f, presHz = 3200f,
            airDb = 2.0f, airHz = 10000f, compThrDb = -20f, compRatio = 2.0f, makeupDb = 1.5f,
            reverbMix = 0.20f, reverbRoom = 0.86f, reverbDamp = 0.35f, width = 1.25f
        )
    ),
    LIVE(
        "LIVE", "Sân khấu",
        "Mạnh và rộng như đại nhạc hội: bass dày, sáng, nén chắc, vang vừa.",
        StudioParams(
            lowDb = 3.0f, lowHz = 100f, mudDb = -1.0f, mudHz = 350f, presDb = 2.0f, presHz = 3200f,
            airDb = 3.0f, airHz = 10000f, compThrDb = -18f, compRatio = 3.0f, makeupDb = 2.5f,
            reverbMix = 0.16f, reverbRoom = 0.70f, reverbDamp = 0.45f, width = 1.30f
        )
    ),
    BASS(
        "BASS", "Bass ấm",
        "Nhấn bass dày và ấm, vang nhẹ; hợp nhạc sôi động, remix, loa nhỏ.",
        StudioParams(
            lowDb = 5.0f, lowHz = 90f, mudDb = 0.0f, mudHz = 350f, presDb = 1.0f, presHz = 3200f,
            airDb = 1.5f, airHz = 10000f, compThrDb = -16f, compRatio = 3.0f, makeupDb = 2.0f,
            reverbMix = 0.06f, reverbRoom = 0.40f, reverbDamp = 0.60f, width = 1.10f
        )
    ),
    VOCAL(
        "VOCAL", "Rõ lời",
        "Bớt đục vùng giữa, nhấn dải giọng hát để lời bài rõ ràng hơn; ít vang.",
        StudioParams(
            lowDb = -1.0f, lowHz = 120f, mudDb = -2.5f, mudHz = 400f, presDb = 3.5f, presHz = 3200f,
            airDb = 2.0f, airHz = 10000f, compThrDb = -20f, compRatio = 2.5f, makeupDb = 2.0f,
            reverbMix = 0.08f, reverbRoom = 0.50f, reverbDamp = 0.55f, width = 1.0f
        )
    );

    companion object {
        val ALL: List<StudioPreset> = values().toList()
        val DEFAULT = STUDIO
        fun fromKey(key: String?): StudioPreset = ALL.firstOrNull { it.key == key } ?: DEFAULT
        fun isValid(key: String?): Boolean = ALL.any { it.key == key }
    }
}
