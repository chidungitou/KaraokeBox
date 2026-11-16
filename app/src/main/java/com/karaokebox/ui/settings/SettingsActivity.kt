package com.karaokebox.ui.settings

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.karaokebox.BuildConfig
import com.karaokebox.ServiceLocator
import com.karaokebox.data.db.MusicFolderEntity
import com.karaokebox.data.update.AppUpdater
import com.karaokebox.data.yt.NewPipeUpdateChecker
import com.karaokebox.ui.dp
import com.karaokebox.ui.overlay.StageTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {
    private val sl = ServiceLocator
    private lateinit var list: ListView
    private lateinit var status: TextView
    private lateinit var ytBtn: Button
    private lateinit var appBtn: Button
    private var folders: List<MusicFolderEntity> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF14111B.toInt())
            setPadding(dp(48), dp(32), dp(48), dp(32))
        }
        fun label(t: String, size: Float = 20f) = TextView(this).apply {
            text = t; textSize = size; setTextColor(Color.WHITE)
        }
        root.addView(label("Cài đặt thư viện nhạc", 28f))
        root.addView(label("Chọn thư mục chứa video karaoke (ví dụ /mnt/sda1/Karaoke). Bấm vào thư mục để xóa.").apply {
            setTextColor(0xFFA398B4.toInt()); setPadding(0, dp(8), 0, dp(12))
        })

        list = ListView(this)
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        list.setOnItemClickListener { _, _, pos, _ ->
            val f = folders.getOrNull(pos) ?: return@setOnItemClickListener
            AlertDialog.Builder(this)
                .setTitle("Xóa thư mục này khỏi thư viện?")
                .setMessage(f.path)
                .setPositiveButton("Xóa") { _, _ ->
                    lifecycleScope.launch {
                        sl.db.songDao().deleteByFolder(f.id)
                        sl.db.folderDao().delete(f.id)
                        reload()
                    }
                }
                .setNegativeButton("Hủy", null).show()
        }

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, dp(8)) }
        val add = Button(this).apply {
            text = "Thêm thư mục"
            setOnClickListener { startActivity(Intent(this@SettingsActivity, FolderPickerActivity::class.java)) }
        }
        val scan = Button(this).apply {
            text = "Quét lại"
            setOnClickListener { lifecycleScope.launch(Dispatchers.IO) { sl.scanner.scanAll() } }
        }
        ytBtn = Button(this).apply {
            setOnClickListener { startActivity(Intent(this@SettingsActivity, YoutubeUpdateActivity::class.java)) }
        }
        refreshYtBtn()
        btnRow.addView(add); btnRow.addView(scan); btnRow.addView(ytBtn)
        root.addView(btnRow)

        appBtn = Button(this).apply {
            setOnClickListener { startActivity(Intent(this@SettingsActivity, AppUpdateActivity::class.java)) }
        }
        refreshAppBtn()
        root.addView(appBtn, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(8) })

        fun check(text: String, initial: Boolean, onChange: (Boolean) -> Unit) = CheckBox(this).apply {
            this.text = text; textSize = 18f; setTextColor(Color.WHITE); isChecked = initial
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
        root.addView(check("Tên file dạng \"Ca sĩ - Tên bài\" (đảo tiêu đề/ca sĩ, cần Quét lại)", sl.settings.swapTitleArtist) {
            sl.settings.swapTitleArtist = it
        })
        root.addView(check("File 1 track stereo: kênh TRÁI là tiếng ca sĩ", sl.settings.leftIsVocal) {
            sl.settings.leftIsVocal = it
        })
        root.addView(check("Tự mở Karaoke khi box khởi động", sl.settings.autoStart) { sl.settings.autoStart = it })
        root.addView(check("Xếp hàng công bằng: bài của nhiều người được xen kẽ, không ai chiếm hết hàng chờ", sl.settings.fairQueue) {
            sl.settings.fairQueue = it
        })

        // Giao diện màn hình chờ theo dịp (Tết, Giáng sinh, Sinh nhật) hoặc tự động theo ngày
        val themeBtn = Button(this)
        fun refreshThemeBtn() { themeBtn.text = "Giao diện màn hình chờ: " + StageTheme.describe(sl.settings.stageThemeMode) }
        refreshThemeBtn()
        themeBtn.setOnClickListener {
            val modes = StageTheme.MODES
            val cur = modes.indexOfFirst { it.first == sl.settings.stageThemeMode }.coerceAtLeast(0)
            AlertDialog.Builder(this)
                .setTitle("Giao diện màn hình chờ")
                .setSingleChoiceItems(modes.map { it.second }.toTypedArray(), cur) { d, which ->
                    sl.settings.stageThemeMode = modes[which].first
                    refreshThemeBtn()
                    d.dismiss()
                }
                .setNegativeButton("Đóng", null).show()
        }
        root.addView(themeBtn, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })

        status = label("").apply { setTextColor(0xFFFFB02E.toInt()); setPadding(0, dp(8), 0, 0) }
        root.addView(status)
        setContentView(root)

        // Nhắc bản NewPipeExtractor mới: kiểm tra nền tối đa 12 giờ/lần, có bản mới thì nút YouTube báo ngay
        lifecycleScope.launch {
            NewPipeUpdateChecker.check(sl.settings, 12 * 3_600_000L)
            refreshYtBtn()
            AppUpdater.check(sl.settings, 6 * 3_600_000L)
            refreshAppBtn()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    sl.scanner.progress.collect { p ->
                        status.text = if (p.scanning) "Đang quét... ${p.scanned} file, mới/đổi: ${p.found}"
                        else if (p.scanned > 0) "Quét xong: ${p.scanned} file" else ""
                        if (!p.scanning) reload()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
        refreshYtBtn()
        refreshAppBtn()
    }

    /** Nút mở màn hình cập nhật app; thêm dấu ⚠ khi đã biết có bản mới. */
    private fun refreshAppBtn() {
        val rel = AppUpdater.known(sl.settings)
        appBtn.text = if (rel != null && AppUpdater.isNewer(rel)) "⚠ Cập nhật app: có bản mới ${rel.label}"
        else "Cập nhật app: ${BuildConfig.VERSION_NAME}"
    }

    /** Nút mở màn hình cập nhật YouTube; thêm dấu ⚠ khi có bản mới hoặc YouTube đang lỗi. */
    private fun refreshYtBtn() {
        val latest = sl.settings.npLatest
        val newer = latest.isNotEmpty() && NewPipeUpdateChecker.isNewer(latest)
        val broken = sl.youtube.status.value.health != "OK"
        ytBtn.text = when {
            newer -> "⚠ YouTube: có bản mới $latest"
            broken -> "⚠ YouTube đang lỗi - xem cách sửa"
            else -> "YouTube: ${BuildConfig.NEWPIPE_VERSION}"
        }
    }

    private fun reload() {
        lifecycleScope.launch {
            folders = sl.db.folderDao().getAll()
            val rows = if (folders.isEmpty()) listOf("(chưa có thư mục nào)")
            else folders.map { "${it.path}   -   ${it.songCount} bài" }
            list.adapter = object : ArrayAdapter<String>(this@SettingsActivity, android.R.layout.simple_list_item_1, rows) {
                override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                    val v = super.getView(position, convertView, parent) as TextView
                    v.setTextColor(Color.WHITE); v.textSize = 20f
                    return v
                }
            }
        }
    }
}
