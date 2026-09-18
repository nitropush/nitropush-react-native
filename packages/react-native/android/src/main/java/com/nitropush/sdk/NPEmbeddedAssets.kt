package com.nitropush.sdk

import android.content.Context
import android.util.TypedValue
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.json.JSONObject

/** Uses a build-generated binary inventory; never scans the APK on an update check. */
internal class NPEmbeddedAssets(private val context: Context) {
    private data class Entry(val hash: String, val size: Long, val kind: String, val path: String?,
        val resourceType: String?, val resourceName: String?, val density: Int)
    private var index: Map<String, List<Entry>>? = null

    @Synchronized private fun entries(hash: String): List<Entry> {
        if (index == null) {
            val parsed = mutableMapOf<String, MutableList<Entry>>()
            for (path in listOf("nitropush-embedded-assets.json", "app/nitropush-embedded-assets.json")) {
                val inventory = runCatching {
                    val data = context.assets.open(path).use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(16384)
                        while (true) {
                            val count = input.read(buffer)
                            if (count == -1) break
                            check(count <= 2 * 1024 * 1024 - output.size())
                            output.write(buffer, 0, count)
                        }
                        output.toString("UTF-8")
                    }
                    JSONObject(data).also { check(it.getInt("schemaVersion") == 1 && it.getString("platform") == "android") }
                }.getOrNull() ?: continue
                val values = inventory.optJSONArray("entries") ?: continue
                if (values.length() > 10000) continue
                for (i in 0 until values.length()) {
                    val entry = runCatching {
                        val value = values.getJSONObject(i)
                        val entry = Entry(value.getString("sha256"), value.getLong("size"), value.getString("kind"),
                            value.optString("path").takeIf { it.isNotEmpty() }, value.optString("resourceType").takeIf { it.isNotEmpty() },
                            value.optString("resourceName").takeIf { it.isNotEmpty() }, value.optInt("density", 0))
                        check(Regex("^[a-f0-9]{64}$").matches(entry.hash) && entry.size in 1..(64L * 1024 * 1024))
                        when (entry.kind) {
                            "file" -> check(entry.path != null && safePath(entry.path))
                            "resource" -> check(entry.resourceType in setOf("drawable", "raw") && entry.resourceName != null &&
                                Regex("^[a-z_][a-z0-9_]*$").matches(entry.resourceName) && entry.density in setOf(0, 120, 160, 240, 320, 480, 640))
                            else -> error("invalid embedded asset kind")
                        }
                        entry
                    }.getOrNull() ?: continue
                    val matches = parsed.getOrPut(entry.hash) { mutableListOf() }
                    if (matches.size < 8) matches += entry
                }
                break
            }
            index = parsed
        }
        return index!![hash] ?: emptyList()
    }

    private fun sources(entry: Entry): List<() -> InputStream?> {
        if (entry.kind == "file") return listOf({ context.assets.open(entry.path!!) })
        val id = context.resources.getIdentifier(entry.resourceName, entry.resourceType, context.packageName)
        if (id == 0) return emptyList()
        val value = TypedValue()
        context.resources.getValueForDensity(id, entry.density, value, true)
        val resourcePath = value.string?.toString() ?: return emptyList()
        if (!resourcePath.startsWith("res/") || !safePath(resourcePath)) return emptyList()
        // Resource paths may be rewritten by AAPT. Resolve the packaged path, then
        // read that exact ZIP entry at the requested density, including split APKs.
        val apkPaths = listOf(context.applicationInfo.sourceDir) + (context.applicationInfo.splitSourceDirs?.toList() ?: emptyList())
        if (apkPaths.size > 32) return emptyList()
        return apkPaths.map { apkPath ->
            {
                val zip = ZipFile(apkPath)
                try {
                    val item = zip.getEntry(resourcePath)
                    if (item == null || item.isDirectory || item.size != entry.size) { zip.close(); null }
                    else object : FilterInputStream(zip.getInputStream(item)) {
                        override fun close() { try { super.close() } finally { zip.close() } }
                    }
                } catch (error: Throwable) { zip.close(); throw error }
            }
        }
    }

    fun populate(directory: File, sha256: String, announcedSize: Long, maximumBytes: Long): Boolean {
        for (entry in entries(sha256)) {
            if (entry.size > maximumBytes || (announcedSize > 0 && entry.size != announcedSize)) continue
            val candidates = runCatching { sources(entry) }.getOrDefault(emptyList())
            for (open in candidates) {
                val reused = runCatching {
                    val input = open() ?: return@runCatching false
                    storeVerified(input, directory, sha256, entry.size)
                }.getOrDefault(false)
                if (reused) return true
            }
        }
        return false
    }

    companion object {
        internal fun safePath(path: String) = path.isNotEmpty() && path.toByteArray(Charsets.UTF_8).size <= 512 &&
            !path.contains('\\') && path.none { it.code < 0x20 || it.code == 0x7f } && path.split('/').none { it.isEmpty() || it == "." || it == ".." }

        internal fun storeVerified(input: InputStream, directory: File, sha256: String, size: Long): Boolean = input.use { stream ->
            if (size !in 1..(64L * 1024 * 1024)) return false
            val cached = NPContentHashCache.file(directory, sha256)
            check(cached.parentFile!!.isDirectory || cached.parentFile!!.mkdirs())
            val temporary = File.createTempFile(".np-embedded-", ".tmp", cached.parentFile)
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                var written = 0L
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count == -1) break
                        if (count.toLong() > size - written) return false
                        written += count
                        digest.update(buffer, 0, count); output.write(buffer, 0, count)
                    }
                }
                if (written != size || digest.digest().joinToString("") { "%02x".format(it) } != sha256) return false
                if (cached.exists()) check(cached.canonicalFile == cached.absoluteFile && cached.isFile && cached.delete())
                check(temporary.renameTo(cached))
                true
            } finally { temporary.delete() }
        }
    }
}
