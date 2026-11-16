package com.karaokebox.ui.settings

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.karaokebox.ServiceLocator
import com.karaokebox.data.db.MusicFolderEntity
import com.karaokebox.data.scan.StorageLocator
import com.karaokebox.ui.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/** Duyệt thư mục bằng D-pad: OK để đi vào, chọn dòng "Chọn thư mục này". */
class FolderPickerActivity : AppCompatActivity() {
    private lateinit var list: ListView
    private lateinit var pathView: TextView
    private var current: File? = null
    private var entries: List<Pair<String, () -> Unit>> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF14111B.toInt())
            setPadding(dp(48), dp(32), dp(48), dp(32))
        }
        pathView = TextView(this).apply { textSize = 22f; setTextColor(0xFFFFB02E.toInt()); setPadding(0, 0, 0, dp(12)) }
        list = ListView(this)
        root.addView(pathView)
        root.addView(list, LinearLayout.LayoutParams(-1, -1))
        setContentView(root)
        list.setOnItemClickListener { _, _, pos, _ -> entries.getOrNull(pos)?.second?.invoke() }
        show(null)
    }

    private fun show(dir: File?) {
        current = dir
        val rows = ArrayList<Pair<String, () -> Unit>>()
        if (dir == null) {
            pathView.text = "Chọn ổ đĩa / thư mục gốc"
            StorageLocator.candidates().forEach { f -> rows.add("📁 ${f.path}" to { show(f) }) }
        } else {
            pathView.text = dir.path
            rows.add("✔ Chọn thư mục này" to { choose(dir) })
            rows.add("⬆ Lên thư mục cha" to { show(dir.parentFile?.takeIf { it.canRead() }) })
            dir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }
                ?.sortedBy { it.name.lowercase() }
                ?.forEach { f -> rows.add("📁 ${f.name}" to { show(f) }) }
        }
        if (rows.isEmpty()) rows.add("(không có thư mục nào đọc được)" to {})
        entries = rows
        list.adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, rows.map { it.first }) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                v.setTextColor(Color.WHITE); v.textSize = 20f
                return v
            }
        }
        list.requestFocus()
    }

    private fun choose(dir: File) {
        lifecycleScope.launch {
            ServiceLocator.db.folderDao().insert(MusicFolderEntity(path = dir.path))
            launch(Dispatchers.IO) { ServiceLocator.scanner.scanAll() }
            finish()
        }
    }
}
