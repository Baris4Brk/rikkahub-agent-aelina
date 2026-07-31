package me.rerere.rikkahub.owner

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** API-26-compatible bounded read used for untrusted Owner install manifests and archives. */
internal fun InputStream.readOwnerBytesAtMost(maxBytes: Int): ByteArray {
    require(maxBytes >= 0)
    val output = ByteArrayOutputStream(minOf(maxBytes, DEFAULT_BUFFER_SIZE))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var remaining = maxBytes
    while (remaining > 0) {
        val count = read(buffer, 0, minOf(buffer.size, remaining))
        if (count < 0) break
        if (count == 0) {
            val single = read()
            if (single < 0) break
            output.write(single)
            remaining--
            continue
        }
        output.write(buffer, 0, count)
        remaining -= count
    }
    return output.toByteArray()
}
