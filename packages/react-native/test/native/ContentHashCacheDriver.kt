package com.nitropush.sdk

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

fun main() {
    val root = Files.createTempDirectory("np-cache-test-").toFile().canonicalFile
    try {
        val cache = File(root, "cache").also { it.mkdirs() }
        val payload = "reusable asset bytes".toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
        val cached = File(cache, hash)
        val dest = File(root, "asset.png")
        fun copy(announced: Long = payload.size.toLong(), maximum: Long = 1024) =
            NPContentHashCache.copyVerified(cache, hash, dest, announced, maximum)
        check(copy() == null)
        cached.writeBytes(payload)
        check(copy() == payload.size.toLong())
        check(dest.readBytes().contentEquals(payload))
        val secondDest = File(root, "another-release.png")
        var deltaAttempts = 0
        var fullDownloads = 0
        if (NPContentHashCache.copyVerified(cache, hash, secondDest, -1, 1024) == null) {
            deltaAttempts += 1; fullDownloads += 1
        }
        check(deltaAttempts == 0 && fullDownloads == 0)
        check(secondDest.readBytes().contentEquals(payload))
        val aliasRoot = File(root.parentFile, "np-cache-alias-${java.util.UUID.randomUUID()}")
        Files.createSymbolicLink(aliasRoot.toPath(), root.toPath())
        try {
            check(NPContentHashCache.copyVerified(File(aliasRoot, "cache"), hash, File(aliasRoot, "alias-asset.png"), payload.size.toLong(), 1024) == payload.size.toLong())
        } finally { aliasRoot.delete() }
        val outsideDestination = File(root.parentFile, "np-outside-${java.util.UUID.randomUUID()}")
        check(runCatching { NPContentHashCache.copyVerified(cache, hash, outsideDestination, payload.size.toLong(), 1024) }.isFailure)
        cached.writeBytes(ByteArray(payload.size) { 42 })
        check(copy() == null)
        check(!cached.exists())
        check(dest.readBytes().contentEquals(payload))
        cached.writeBytes(payload)
        check(copy(payload.size.toLong() + 1) == null)
        cached.writeBytes(payload)
        check(copy(payload.size.toLong(), payload.size.toLong() - 1) == null)
        cached.writeBytes(ByteArray(0))
        check(copy() == null)
        val outside = File(root, "outside").also { it.writeBytes(payload) }
        Files.createSymbolicLink(cached.toPath(), outside.toPath())
        check(copy() == null)
        check(outside.readBytes().contentEquals(payload))
        cached.mkdir()
        val child = File(cached, "preserve-me").also { it.writeBytes(payload) }
        check(copy() == null)
        check(child.exists())
        child.delete(); cached.delete()
        cached.writeBytes(payload)
        dest.delete()
        Files.createSymbolicLink(dest.toPath(), outside.toPath())
        check(runCatching { copy() }.isFailure)
        check(outside.readBytes().contentEquals(payload))
        val redirectedCache = File(root, "redirected-cache")
        Files.createSymbolicLink(redirectedCache.toPath(), cache.toPath())
        check(runCatching { NPContentHashCache.file(redirectedCache, hash) }.isFailure)
        check(runCatching { NPContentHashCache.file(cache, "../outside") }.isFailure)
        check(root.listFiles()!!.none { it.name.startsWith(".np-cache-") })
        println("verified cache reuse, corruption, byte bounds and path safety passed")
    } finally { root.deleteRecursively() }
}
