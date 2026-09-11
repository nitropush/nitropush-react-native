package com.nitropush.sdk

import java.net.URL
import java.net.URLDecoder

internal object NPAssetDeliveryURL {
    fun resolve(raw: String, api: URL?, expectedHash: String): Pair<URL, Boolean> {
        val url = if (raw.startsWith("/api/sdk/asset?") && api != null) URL(api, raw) else URL(raw)
        check(url.protocol.lowercase() in setOf("http", "https") && url.host.isNotEmpty() && url.userInfo == null && url.ref == null) { "invalid asset URL" }
        fun port(value: URL) = if (value.port >= 0) value.port else value.defaultPort
        if (api == null || !url.protocol.equals(api.protocol, true) || !url.host.equals(api.host, true) || port(url) != port(api) || url.path != "/api/sdk/asset") return url to false
        val uri = url.toURI()
        val fields = (uri.rawQuery ?: "").split('&').map { field ->
            val pair = field.split('=', limit = 2)
            URLDecoder.decode(pair[0], "UTF-8") to if (pair.size == 2) URLDecoder.decode(pair[1], "UTF-8") else ""
        }
        check(url.protocol.equals("https", true) && uri.rawPath == "/api/sdk/asset" && fields.size == 2 &&
            fields.count { it.first == "hash" && it.second == expectedHash } == 1 &&
            fields.count { it.first == "ticket" && it.second.isNotEmpty() && it.second.toByteArray(Charsets.UTF_8).size <= 8192 } == 1) { "invalid asset proxy URL" }
        return url to true
    }
}
