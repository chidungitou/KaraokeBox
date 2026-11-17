package com.karaokebox.core.net

import android.content.Context
import okhttp3.OkHttpClient

/**
 * Cho client OkHttp dùng thêm kho chứng chỉ gốc đi kèm app (assets/cacerts.bin) khi kho của hệ thống thiếu,
 * để box Android cũ vẫn tải được bản cập nhật từ GitHub. Không đọc được kho thì giữ nguyên kết nối mặc định.
 */
fun OkHttpClient.Builder.withBundledRoots(ctx: Context): OkHttpClient.Builder {
    val m = TrustedRoots.build { ctx.applicationContext.assets.open("cacerts.bin") } ?: return this
    return sslSocketFactory(m.socketFactory, m.trustManager)
}
