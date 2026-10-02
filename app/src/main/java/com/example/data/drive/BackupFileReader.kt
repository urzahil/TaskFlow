package com.example.data.drive

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream

/**
 * Reads a TaskFlow JSON backup from a document-provider stream.
 *
 * Some Android document providers expose files through a ZIP container even
 * when the selected file is presented as a JSON document. Plain JSON remains
 * the normal format; ZIP support is limited to a small JSON entry so unrelated
 * archives are rejected instead of being treated as backups.
 */
object BackupFileReader {
    private const val MAX_JSON_BYTES = 10 * 1024 * 1024
    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B)

    fun readJson(inputStream: InputStream): String {
        val bytes = inputStream.readBounded(MAX_JSON_BYTES)
        val normalized = stripUtf8Bom(bytes)

        if (normalized.size >= 2 &&
            normalized[0] == ZIP_MAGIC[0] &&
            normalized[1] == ZIP_MAGIC[1]
        ) {
            return readJsonFromZip(normalized)
        }

        return normalized.toString(StandardCharsets.UTF_8)
    }

    private fun readJsonFromZip(bytes: ByteArray): String {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.lowercase().endsWith(".json")) {
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0

                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_JSON_BYTES) { "Backup JSON is too large" }
                        output.write(buffer, 0, count)
                    }

                    return stripUtf8Bom(output.toByteArray()).toString(StandardCharsets.UTF_8)
                }
                entry = zip.nextEntry
            }
        }

        throw IllegalArgumentException("ZIP backup does not contain a JSON file")
    }

    private fun InputStream.readBounded(maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0

        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            require(total <= maxBytes) { "Backup file is too large" }
            output.write(buffer, 0, count)
        }

        return output.toByteArray()
    }

    private fun stripUtf8Bom(bytes: ByteArray): ByteArray {
        return if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            bytes.copyOfRange(3, bytes.size)
        } else {
            bytes
        }
    }
}
