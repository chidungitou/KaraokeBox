package com.karaokebox.ui.settings

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.karaokebox.ServiceLocator
import com.karaokebox.game.GameOptions
import com.karaokebox.ui.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Cài đặt trò chơi cuối bài (vòng xoay may mắn) trên TV, dùng bằng remote hồng ngoại.
 * Tách riêng khỏi Cài đặt thường: dùng GameSettingsStore riêng, nút "Đặt lại" riêng. Điện thoại có màn hình tương ứng (nút 🎲).
 */
class GameSettingsActivity : AppCompatActivity() {
    private val sl = ServiceLocator
    private val g get() = sl.gameSettings
    private val refreshers = ArrayList<() -> Unit>()
    private lateinit var box: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this).apply { setBackgroundColor(0xFF14111B.toInt()) }
        box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(32), dp(48), dp(32))
        }
        scroll.addView(box, LinearLayout.LayoutParams(-1, -2))
        setContentView(scroll)
        build()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() = refreshers.forEach { it() }

    private fun label(t: String, size: Float = 20f) = TextView(this).apply {
        text = t; textSize = size; setTextColor(Color.WHITE)
    }

    private fun note(t: String) = label(t, 16f).apply {
        setTextColor(0xFFA398B4.toInt()); setPadding(0, dp(4), 0, dp(8))
    }

    private fun check(text: String, get: () -> Boolean, set: (Boolean) -> Unit, indent: Int = 0): CheckBox {
        val cb = CheckBox(this).apply {
            this.text = text; textSize = 18f; setTextColor(Color.WHITE)
            setPadding(dp(indent), 0, 0, 0)
        }
        // Gán trạng thái ban đầu TRƯỚC khi gắn listener để không ghi ngược lại
        cb.isChecked = get()
        cb.setOnCheckedChangeListener { _, v -> set(v); refresh() }
        refreshers.add { if (cb.isChecked != get()) cb.isChecked = get() }
        box.addView(cb)
        return cb
    }

    /** Nút bấm mở hộp chọn một trong nhiều giá trị. Nhãn dạng "Tên: giá trị hiện tại". */
    private fun <T> choice(title: String, options: List<Pair<T, String>>, get: () -> T, set: (T) -> Unit) {
        val b = Button(this).apply { isAllCaps = false }
        fun upd() { b.text = "$title: " + (options.firstOrNull { it.first == get() }?.second ?: "?") }
        upd()
        b.setOnClickListener {
            val cur = options.indexOfFirst { it.first == get() }.coerceAtLeast(0)
            AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(options.map { it.second }.toTypedArray(), cur) { d, which ->
                    set(options[which].first)
                    refresh()
                    d.dismiss()
                }
                .setNegativeButton("Đóng", null).show()
        }
        refreshers.add { upd() }
        box.addView(b, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
    }

    private fun section(t: String) {
        box.addView(label(t, 22f).apply { setTextColor(0xFFFFC857.toInt()); setPadding(0, dp(18), 0, dp(2)) })
    }

    private fun build() {
        box.addView(label("Trò chơi cuối bài", 28f))
        box.addView(note("Hết mỗi bài, TV quay vòng xoay may mắn và hiện ai uống, uống bao nhiêu. Người uống chỉ cần nhìn TV, không bấm gì trên điện thoại. " +
            "Mặc định TẮT. Uống có trách nhiệm, đã uống thì không lái xe."))

        check("Bật trò chơi cuối bài", { g.enabled }, { g.enabled = it })
        choice("Tần suất", GameOptions.FREQUENCIES, { g.frequency }, { g.frequency = it })

        section("Mức uống theo thời gian đã hát")
        box.addView(note("Mới hát thì uống theo \"Mức trước mốc\"; hát lâu hơn mốc thì giảm theo \"Mức từ mốc trở đi\"."))
        choice("Mốc \"đã hát lâu\"", GameOptions.THRESHOLD_MIN.map { it to "$it phút" }, { g.thresholdMin }, { g.thresholdMin = it })
        choice("Mức trước mốc", GameOptions.LEVEL_BEFORE, { g.levelBefore }, { g.levelBefore = it })
        choice("Mức từ mốc trở đi", GameOptions.LEVEL_AFTER, { g.levelAfter }, { g.levelAfter = it })
        choice(
            "Tự tính đêm mới nếu nghỉ hát",
            GameOptions.IDLE_RESET_MIN.map { it to (if (it == 0) "Tắt (chỉ tính từ lúc mở app)" else "quá $it phút") },
            { g.idleResetMin }, { g.idleResetMin = it }
        )

        section("Các ô trên vòng xoay")
        check("Vị trí quanh người vừa hát (bên trái, bên phải, hai bên, đối diện, hát kế tiếp...)", { g.groupPosition }, { g.groupPosition = it })
        check("Nhóm (cả phòng \"Dô!\", các anh, các chị)", { g.groupGroup }, { g.groupGroup = it })
        check("Các anh", { g.segMen }, { g.segMen = it }, indent = 32)
        check("Các chị", { g.segWomen }, { g.segWomen = it }, indent = 32)
        check("Đặc điểm ngẫu nhiên (ai đeo kính, người trẻ nhất...)", { g.groupTrait }, { g.groupTrait = it })

        section("Khác")
        choice("Số vòng tối đa mỗi đêm", GameOptions.MAX_ROUNDS.map { it to (if (it == 0) "Không giới hạn" else "$it vòng") }, { g.maxRounds }, { g.maxRounds = it })
        choice("Thời gian hiện kết quả", GameOptions.RESULT_SEC.map { it to "$it giây" }, { g.resultSec }, { g.resultSec = it })
        check("Tiếng vòng xoay", { g.sound }, { g.sound = it })
        check("Dòng nhắc \"Uống có trách nhiệm\"", { g.reminder }, { g.reminder = it })

        status = note("")
        box.addView(status)
        refreshers.add {
            val s = sl.game.session
            status.text = "Đêm hiện tại: ${(s.elapsedMs() / 60_000L)} phút, đã quay ${s.rounds} vòng."
        }

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, dp(8)) }
        val test = Button(this).apply {
            isAllCaps = false
            text = "Quay thử"
            setOnClickListener {
                // Về màn hình hát rồi mới quay để thấy vòng xoay (quay thử không tính vào đêm)
                sl.appScope.launch { delay(700); sl.game.play(test = true) }
                finish()
            }
        }
        val night = Button(this).apply {
            isAllCaps = false
            text = "Bắt đầu đêm mới"
            setOnClickListener {
                sl.game.session.newNight()
                refresh()
            }
        }
        val reset = Button(this).apply {
            isAllCaps = false
            text = "Đặt lại trò chơi"
            setOnClickListener {
                AlertDialog.Builder(this@GameSettingsActivity)
                    .setTitle("Đặt lại cài đặt trò chơi?")
                    .setMessage("Mọi mục trong Trò chơi về mặc định (kể cả tắt trò chơi). Cài đặt khác không bị đụng tới.")
                    .setPositiveButton("Đặt lại") { _, _ -> g.resetDefaults(); refresh() }
                    .setNegativeButton("Hủy", null).show()
            }
        }
        row.addView(test); row.addView(night); row.addView(reset)
        box.addView(row)
        box.addView(note("Trên TV: khi vòng xoay đang hiện, bấm OK hoặc Back để bỏ qua. Điện thoại của chủ phòng cũng chỉnh được ở nút 🎲."))
        refresh()
    }
}
