package com.karaokebox.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Kiểm tra box có thật sự ra được YouTube hay không (không chỉ "đang nối Wi-Fi").
 * Router còn sáng đèn nhưng đứt cáp quang vẫn cho kết quả false. Một lần kiểm tra tối đa ~5 giây, rất nhẹ.
 */
object InternetProbe {
    private const val HOST = "www.youtube.com"
    private const val PORT = 443

    suspend fun canReachYoutube(timeoutMs: Long = 5_000): Boolean =
        withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.IO) {
                try {
                    val addr = InetAddress.getByName(HOST)
                    Socket().use { it.connect(InetSocketAddress(addr, PORT), 3_000) }
                    true
                } catch (_: Exception) {
                    false
                }
            }
        } ?: false
}
