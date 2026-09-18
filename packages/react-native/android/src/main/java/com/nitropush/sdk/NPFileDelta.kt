package com.nitropush.sdk

import java.io.ByteArrayOutputStream

/** Bounded NPDIFF01 decoder. The caller authenticates base and output bytes. */
internal object NPFileDelta {
    fun apply(base: ByteArray, patch: ByteArray, maximumBytes: Int): ByteArray {
        check(base.isNotEmpty() && base.size <= maximumBytes && patch.size in 12..maximumBytes &&
            patch.copyOfRange(0, 8).contentEquals("NPDIFF01".toByteArray(Charsets.US_ASCII))) { "Invalid file delta header" }
        var cursor = 8
        fun u32(): Int {
            check(cursor + 4 <= patch.size) { "Truncated file delta" }
            var value = 0L
            repeat(4) { value = (value shl 8) or (patch[cursor++].toLong() and 255) }
            check(value <= Int.MAX_VALUE) { "File delta integer exceeds limit" }
            return value.toInt()
        }
        val size = u32()
        check(size in 1..maximumBytes) { "Invalid file delta output size" }
        val output = ByteArrayOutputStream(size)
        while (cursor < patch.size) {
            val op = patch[cursor++].toInt()
            check(op == 0 || op == 1) { "Invalid file delta operation" }
            val offset = if (op == 0) u32() else 0
            val length = u32()
            check(length > 0 && length <= size - output.size()) { "File delta exceeds output size" }
            if (op == 0) {
                check(offset <= base.size && length <= base.size - offset) { "File delta exceeds base size" }
                output.write(base, offset, length)
            } else {
                check(length <= patch.size - cursor) { "Truncated file delta literal" }
                output.write(patch, cursor, length); cursor += length
            }
        }
        check(output.size() == size) { "Incomplete file delta" }
        return output.toByteArray()
    }
}
