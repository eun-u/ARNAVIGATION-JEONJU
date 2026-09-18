package kr.co.navi.mobility.demo

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream

class CollectionExportTest {
    @Test fun `export preserves nested sensor bytes and interrupted run metadata`() {
        val directory=Files.createTempDirectory("collection-export-test").toFile()
        try {
            val original=byteArrayOf(0,0,44,1,-1,-1)
            File(directory,"depth").mkdir()
            File(directory,"depth/42.u16").writeBytes(original)
            File(directory,"capture_state.json").writeText("{\"status\":\"interrupted\"}")
            val output=ByteArrayOutputStream()
            CollectionExport.writeZip(directory,output)
            val entries=mutableMapOf<String,ByteArray>()
            ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
                while(true) {val entry=zip.nextEntry ?: break;entries[entry.name]=zip.readBytes()}
            }
            assertEquals(setOf("depth/42.u16","capture_state.json"),entries.keys)
            assertArrayEquals(original,entries["depth/42.u16"])
            assertEquals("{\"status\":\"interrupted\"}",entries["capture_state.json"]!!.toString(Charsets.UTF_8))
        } finally {directory.deleteRecursively()}
    }
}
