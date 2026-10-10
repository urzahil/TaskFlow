package com.example

import com.example.data.drive.BackupFileReader
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupFileReaderTest {

    @Test
    fun readsPlainJson() {
        val json = """{"app":"TaskFlow","version":2}"""

        assertEquals(
            json,
            BackupFileReader.readJson(ByteArrayInputStream(json.toByteArray(Charsets.UTF_8)))
        )
    }

    @Test
    fun stripsUtf8Bom() {
        val json = """{"app":"TaskFlow","version":2}"""
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
                json.toByteArray(Charsets.UTF_8)

        assertEquals(
            json,
            BackupFileReader.readJson(ByteArrayInputStream(bytes))
        )
    }

    @Test
    fun readsJsonInsideZip() {
        val json = """{"app":"TaskFlow","version":2}"""
        val output = ByteArrayOutputStream()

        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("taskflow_backup.json"))
            zip.write(json.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }

        assertEquals(
            json,
            BackupFileReader.readJson(ByteArrayInputStream(output.toByteArray()))
        )
    }
}
