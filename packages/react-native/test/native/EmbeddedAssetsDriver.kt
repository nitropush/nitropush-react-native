package com.nitropush.sdk

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

fun main() {
    val root = Files.createTempDirectory("np-embedded-test-").toFile().canonicalFile
    try {
        val cache = File(root, "cache")
        val bytes = "exact binary asset bytes".toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(NPEmbeddedAssets.storeVerified(ByteArrayInputStream(bytes), cache, hash, bytes.size.toLong()))
        val dest = File(root, "asset.png")
        check(NPContentHashCache.copyVerified(cache, hash, dest, bytes.size.toLong(), 1024) == bytes.size.toLong())
        check(dest.readBytes().contentEquals(bytes))
        val cached = NPContentHashCache.file(cache, hash)
        cached.delete()
        check(!NPEmbeddedAssets.storeVerified(ByteArrayInputStream(ByteArray(bytes.size) { 42 }), cache, hash, bytes.size.toLong()))
        check(!cached.exists())
        check(!NPEmbeddedAssets.storeVerified(ByteArrayInputStream(bytes), cache, hash, bytes.size.toLong() - 1))
        check(!NPEmbeddedAssets.storeVerified(ByteArrayInputStream(bytes), cache, hash, bytes.size.toLong() + 1))
        check(!cached.exists())
        check(!NPEmbeddedAssets.safePath("../secret"))
        check(!NPEmbeddedAssets.safePath("/app/file"))
        check(!NPEmbeddedAssets.safePath("app\\file"))
        check(!NPEmbeddedAssets.safePath("app//file"))
        check(NPEmbeddedAssets.safePath("app/assets/logo.png"))
        println("embedded verified stream reuse, corruption, bounds and path rejection passed")
    } finally { root.deleteRecursively() }
}
