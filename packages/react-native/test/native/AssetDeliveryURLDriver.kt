package com.nitropush.sdk
import java.net.URL
fun main() {
    val api = URL("https://api.example.test/base")
    val hash = "a".repeat(64)
    val relative = "/api/sdk/asset?ticket=proof&hash=$hash"
    val resolved = NPAssetDeliveryURL.resolve(relative, api, hash)
    check(resolved.first.toString() == "https://api.example.test$relative" && resolved.second)
    check(NPAssetDeliveryURL.resolve("https://api.example.test:443$relative", api, hash).second)
    for (candidate in listOf("https://cdn.example.test$relative", "https://api.example.test:444$relative", "https://api.example.test/assets/file.png", "http://api.example.test$relative"))
        check(!NPAssetDeliveryURL.resolve(candidate, api, hash).second)
    for (candidate in listOf(relative + "&hash=$hash", relative + "&extra=1", "/api/sdk/asset?ticket=&hash=$hash",
        "/api/sdk/asset?ticket=proof&hash=wrong", relative + "#fragment", "//api.example.test$relative", "https://user:pass@api.example.test$relative", "/different/path"))
        check(runCatching { NPAssetDeliveryURL.resolve(candidate, api, hash) }.isFailure)
    check(runCatching { NPAssetDeliveryURL.resolve(relative, URL("http://api.example.test"), hash) }.isFailure)
    val encoded = runCatching { NPAssetDeliveryURL.resolve("https://api.example.test/api/sdk/%61sset?ticket=proof&hash=$hash", api, hash) }.getOrNull()
    check(encoded == null || !encoded.second)
    println("asset proxy URL origin, exact hash, query cardinality and credential isolation passed")
}
