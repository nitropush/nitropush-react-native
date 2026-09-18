package com.nitropush.sdk

import java.io.File
import java.security.MessageDigest

/** Only verified app-private content is reusable; filenames are not proof. */
internal object NPContentHashCache {
    fun file(directory: File, sha256: String): File {
        check(Regex("^[a-f0-9]{64}$").matches(sha256)) { "invalid cache hash" }
        // Resolve OS aliases in the parent, but never follow a substituted cache directory.
        val directoryPath = File(directory.parentFile!!.canonicalFile, directory.name).absoluteFile
        check(directoryPath.canonicalFile == directoryPath) { "invalid cache directory" }
        return File(directoryPath, sha256)
    }

    /** Returns exact installed bytes, or null for missing/corrupt content. */
    fun copyVerified(directory: File, sha256: String, destination: File, announcedSize: Long, maximumBytes: Long): Long? {
        check(maximumBytes > 0 && (announcedSize == -1L || announcedSize > 0)) { "invalid cache bounds" }
        val cached = file(directory, sha256)
        val size = cached.length()
        if (cached.canonicalFile != cached.absoluteFile || !cached.isFile || size !in 1..maximumBytes ||
            (announcedSize > 0 && size != announcedSize)) {
            // File.delete removes only this leaf, never an unexpected directory tree.
            if (cached.canonicalFile != cached.absoluteFile || cached.isFile) cached.delete()
            return null
        }
        // Resolve a trusted application-root alias, not symlinks within a release.
        val logicalRoot = directory.parentFile!!.absoluteFile
        val physicalRoot = logicalRoot.canonicalFile
        val requested = destination.absolutePath
        val prefix = if (requested.startsWith(logicalRoot.path + File.separator)) logicalRoot.path else physicalRoot.path
        check(requested.startsWith(prefix + File.separator)) { "cache destination escapes update root" }
        val dest = File(physicalRoot, requested.substring(prefix.length + 1))
        check(dest.canonicalFile == dest && dest != cached) { "invalid cache destination" }
        val temporary = File.createTempFile(".np-cache-", ".tmp", dest.parentFile)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            cached.inputStream().use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        if (count.toLong() > size - copied) {
                            cached.delete()
                            return null
                        }
                        copied += count
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (copied != size || hash != sha256) {
                cached.delete()
                return null
            }
            check(dest.canonicalFile == dest) { "invalid cache destination" }
            if (dest.exists()) check(dest.isFile && dest.delete()) { "cannot replace cache destination" }
            check(temporary.renameTo(dest)) { "cannot install verified cache content" }
            cached.setLastModified(System.currentTimeMillis())
            return copied
        } finally { temporary.delete() }
    }
}
