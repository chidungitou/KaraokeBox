package com.karaokebox.core.net

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {
    /** IP LAN của box: ưu tiên Ethernet, sau đó Wi-Fi. */
    fun localIp(): String? = try {
        val list = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nif ->
                nif.inetAddresses.toList()
                    .filter { it is Inet4Address && !it.isLoopbackAddress }
                    .map { Triple(nif.name, it.hostAddress ?: "", it.isSiteLocalAddress) }
            }
            .filter { it.second.isNotEmpty() && !it.first.startsWith("rmnet") && !it.first.startsWith("dummy") }
        list.firstOrNull { it.first.startsWith("eth") && it.third }?.second
            ?: list.firstOrNull { it.first.startsWith("wlan") && it.third }?.second
            ?: list.firstOrNull { it.third }?.second
            ?: list.firstOrNull()?.second
    } catch (e: Exception) {
        null
    }
}
