package com.karaokebox.server

import com.karaokebox.ServiceLocator
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD

/** HTTP (REST + static) và WebSocket chung một cổng. */
class KaraokeServer(port: Int) : NanoWSD(port) {
    override fun openWebSocket(handshake: IHTTPSession): WebSocket =
        ServiceLocator.hub.createSocket(handshake)

    override fun serveHttp(session: IHTTPSession): Response =
        ServiceLocator.router.handle(session)
}
