package com.karaokebox.data.scan

import android.os.Environment
import java.io.File

/** Gợi ý các điểm mount ổ cứng/USB phổ biến trên box HiSilicon. */
object StorageLocator {
    fun candidates(): List<File> {
        val set = LinkedHashSet<File>()
        val fixed = listOf(
            "/mnt/sda/sda1", "/mnt/sda1", "/mnt/sda", "/mnt/sata", "/mnt/internal_sd",
            "/mnt/sdcard", "/storage", "/mnt"
        )
        fixed.forEach { set.add(File(it)) }
        listOf("/mnt", "/mnt/sda", "/storage").forEach { parent ->
            File(parent).listFiles()?.filter { it.isDirectory }?.forEach { set.add(it) }
        }
        try { set.add(Environment.getExternalStorageDirectory()) } catch (_: Exception) {}
        return set.filter { it.isDirectory && it.canRead() }
    }
}
