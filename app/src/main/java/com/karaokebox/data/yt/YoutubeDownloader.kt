package com.karaokebox.data.yt

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.util.concurrent.TimeUnit

/** Cầu nối HTTP (OkHttp) cho NewPipeExtractor. Toàn bộ request tìm kiếm/lấy link đi qua đây. */
class YoutubeDownloader : Downloader() {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override fun execute(request: Request): Response {
        val method = request.httpMethod()
        val url = request.url()
        val data = request.dataToSend()

        val body = when {
            data != null -> data.toRequestBody(null)
            method.equals("POST", true) || method.equals("PUT", true) -> ByteArray(0).toRequestBody(null)
            else -> null
        }

        val b = okhttp3.Request.Builder().url(url).method(method, body)
            .header("User-Agent", USER_AGENT)

        for ((name, values) in request.headers()) {
            if (values.isEmpty()) continue
            b.removeHeader(name)
            values.forEach { b.addHeader(name, it) }
        }

        client.newCall(b.build()).execute().use { resp ->
            if (resp.code == 429) throw ReCaptchaException("YouTube yêu cầu xác minh (429)", url)
            // 403 từ chính các máy chủ YouTube (không phải link phát) thường là IP bị chặn tạm thời: xử lý như bị giới hạn truy cập
            if (resp.code == 403 && isYoutubeHost(resp.request.url.host)) {
                throw ReCaptchaException("YouTube từ chối truy cập (403)", url)
            }
            val text = resp.body?.string()
            return Response(resp.code, resp.message, resp.headers.toMultimap(), text, resp.request.url.toString())
        }
    }

    private fun isYoutubeHost(host: String) = host == "youtube.com" || host.endsWith(".youtube.com")

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0"
    }
}
