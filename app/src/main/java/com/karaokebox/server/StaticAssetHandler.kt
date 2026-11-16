package com.karaokebox.server

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response
import java.io.ByteArrayInputStream

object StaticAssetHandler {
    private val mime = mapOf(
        "html" to "text/html; charset=utf-8",
        "css" to "text/css; charset=utf-8",
        "js" to "application/javascript; charset=utf-8",
        "json" to "application/json; charset=utf-8",
        "svg" to "image/svg+xml",
        "png" to "image/png",
        "ico" to "image/x-icon"
    )

    fun serve(ctx: Context, uri: String): Response {
        var path = uri.trimStart('/')
        if (path.isEmpty()) path = "index.html"
        if (path.contains("..")) return notFound()
        return try {
            val bytes = ctx.assets.open("web/$path").use { it.readBytes() }
            val ext = path.substringAfterLast('.', "")
            val r = NanoHTTPD.newFixedLengthResponse(
                Response.Status.OK, mime[ext] ?: "application/octet-stream",
                ByteArrayInputStream(bytes), bytes.size.toLong()
            )
            r.addHeader("Cache-Control", "no-cache")
            r
        } catch (e: Exception) {
            notFound()
        }
    }

    private fun notFound(): Response =
        NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain; charset=utf-8", "404")
}
