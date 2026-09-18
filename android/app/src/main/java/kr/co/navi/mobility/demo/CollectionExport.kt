package kr.co.navi.mobility.demo

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object CollectionExport {
    fun writeZip(directory: File, output: OutputStream) {
        ZipOutputStream(output.buffered()).use { zip ->
            directory.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(directory).invariantSeparatorsPath }.forEach { file ->
                zip.putNextEntry(ZipEntry(file.relativeTo(directory).invariantSeparatorsPath))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    fun save(context: Context, directory: File, runId: String): String {
        val name = "NaVi-$runId.zip"
        if (Build.VERSION.SDK_INT < 29) {
            val target = File(context.getExternalFilesDir(null), "jeonju/exports/$name")
            target.parentFile?.mkdirs()
            check(!target.exists())
            target.outputStream().use { writeZip(directory, it) }
            return target.absolutePath
        }
        val resolver = context.contentResolver
        val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/NaVi")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        })) { "다운로드 폴더를 열지 못했습니다." }
        try {
            checkNotNull(resolver.openOutputStream(uri)).use { writeZip(directory, it) }
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1)
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
        return "Download/NaVi/$name"
    }
}
