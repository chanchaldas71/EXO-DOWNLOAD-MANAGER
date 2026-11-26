package com.example.engine.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.example.data.model.HashAlgorithm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest

class StorageFullException(message: String) : IOException(message)

/**
 * Thread-safe positional disk writer backed by [RandomAccessFile] and [FileChannel.write] at
 * explicit file offsets so dozens of concurrent HTTP streams can write directly into
 * `<filename>.plexo.part` without coarse locks stalling network reads.
 */
class PositionalPartWriter(
    val partFile: File,
    private val expectedSize: Long
) : AutoCloseable {

    private val raf: RandomAccessFile
    private val channel: FileChannel

    init {
        partFile.parentFile?.mkdirs()
        raf = RandomAccessFile(partFile, "rw")
        channel = raf.channel
        if (expectedSize > 0L && raf.length() < expectedSize) {
            try {
                raf.setLength(expectedSize)
            } catch (e: IOException) {
                raf.close()
                if (e.message?.contains("ENOSPC", ignoreCase = true) == true ||
                    e.message?.contains("No space", ignoreCase = true) == true
                ) {
                    throw StorageFullException("Storage full — not enough space to preallocate file")
                }
                throw e
            }
        }
    }

    /**
     * Writes [length] bytes from [buffer] starting at absolute file [position].
     * [FileChannel.write] with an explicit position is thread-safe per the JVM NIO specification.
     */
    fun writeAt(position: Long, buffer: ByteArray, offset: Int = 0, length: Int = buffer.size) {
        if (length <= 0) return
        val bb = ByteBuffer.wrap(buffer, offset, length)
        var currentPos = position
        try {
            while (bb.hasRemaining()) {
                val written = channel.write(bb, currentPos)
                if (written <= 0) {
                    throw IOException("Zero bytes written at offset $currentPos")
                }
                currentPos += written
            }
        } catch (e: IOException) {
            if (e.message?.contains("ENOSPC", ignoreCase = true) == true ||
                e.message?.contains("No space", ignoreCase = true) == true
            ) {
                throw StorageFullException("Storage full — free up space and resume")
            }
            throw e
        }
    }

    fun truncateTo(size: Long) {
        if (size >= 0L) {
            channel.truncate(size)
        }
    }

    fun flush() {
        runCatching { channel.force(false) }
    }

    override fun close() {
        runCatching { channel.force(false) }
        runCatching { channel.close() }
        runCatching { raf.close() }
    }
}

data class FinalizedFileResult(
    val finalFilePath: String,
    val finalUri: String
)

/**
 * Handles storage space checks, `.plexo.part` file lifecycle, optional SHA-256/MD5 checksum
 * verification, auto-sorting by file extension, and Android Scoped Storage / SAF finalization.
 */
class DiskWriter(private val context: Context?) {

    fun getDefaultDownloadsDir(): File {
        val base = context?.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context?.filesDir?.let { File(it, "downloads") }
            ?: File(System.getProperty("java.io.tmpdir"), "plexo_downloads")
        if (!base.exists()) base.mkdirs()
        return base
    }

    fun getAvailableStorageBytes(dir: File = getDefaultDownloadsDir()): Long {
        if (!dir.exists()) dir.mkdirs()
        return try {
            val stat = StatFs(dir.absolutePath)
            stat.availableBytes
        } catch (_: Throwable) {
            dir.usableSpace
        }
    }

    fun hasEnoughSpace(requiredBytes: Long, dir: File = getDefaultDownloadsDir()): Boolean {
        if (requiredBytes <= 0L) return true
        // Leave a 32 MB safety cushion on Android devices
        val safetyBuffer = 32L * 1024L * 1024L
        return getAvailableStorageBytes(dir) > (requiredBytes + safetyBuffer)
    }

    fun resolvePartFile(
        fileName: String,
        downloadId: Long,
        autoSortByType: Boolean,
        customBaseDir: File? = null
    ): File {
        val baseDir = customBaseDir ?: getDefaultDownloadsDir()
        val targetDir = if (autoSortByType) {
            File(baseDir, categorySubfolderFor(fileName))
        } else {
            baseDir
        }
        if (!targetDir.exists()) targetDir.mkdirs()
        return File(targetDir, "${downloadId}_${fileName}.plexo.part")
    }

    fun openPartWriter(partFile: File, expectedSize: Long, checkSpace: Boolean = true): PositionalPartWriter {
        val parent = partFile.parentFile ?: getDefaultDownloadsDir()
        if (checkSpace && expectedSize > 0L) {
            val existingSize = if (partFile.exists()) partFile.length() else 0L
            val additionalNeeded = (expectedSize - existingSize).coerceAtLeast(0L)
            if (additionalNeeded > 0L && !hasEnoughSpace(additionalNeeded, parent)) {
                throw StorageFullException("Storage full — free up space before starting")
            }
        }
        return PositionalPartWriter(partFile, expectedSize)
    }

    suspend fun verifyChecksum(
        file: File,
        expectedHash: String,
        algorithm: HashAlgorithm
    ): Boolean = withContext(Dispatchers.IO) {
        if (algorithm == HashAlgorithm.NONE || expectedHash.isBlank()) return@withContext true
        val actual = computeHash(file, algorithm)
        actual.equals(expectedHash.trim(), ignoreCase = true)
    }

    suspend fun computeHash(file: File, algorithm: HashAlgorithm): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance(algorithm.jcaName)
        val buffer = ByteArray(64 * 1024)
        FileInputStream(file).use { fis ->
            var read = fis.read(buffer)
            while (read >= 0) {
                if (read > 0) digest.update(buffer, 0, read)
                read = fis.read(buffer)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    suspend fun finalizePartFile(
        partFile: File,
        desiredFileName: String,
        autoSortByType: Boolean,
        safTreeUri: String = ""
    ): FinalizedFileResult = withContext(Dispatchers.IO) {
        val targetDir = partFile.parentFile ?: getDefaultDownloadsDir()
        if (!targetDir.exists()) targetDir.mkdirs()

        val finalLocalFile = uniqueTargetFile(targetDir, desiredFileName)
        if (!partFile.renameTo(finalLocalFile)) {
            partFile.copyTo(finalLocalFile, overwrite = true)
            partFile.delete()
        }

        // Export to user-selected SAF directory if configured
        if (safTreeUri.isNotBlank() && context != null) {
            val exportedSafUri = exportToSafTree(context, Uri.parse(safTreeUri), finalLocalFile, desiredFileName)
            if (exportedSafUri != null) {
                return@withContext FinalizedFileResult(
                    finalFilePath = finalLocalFile.absolutePath,
                    finalUri = exportedSafUri.toString()
                )
            }
        }

        // Publish into public Downloads collection via MediaStore on Android 10+ for easy user access
        if (context != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val mediaUri = exportToMediaStoreDownloads(context, finalLocalFile, desiredFileName, autoSortByType)
            if (mediaUri != null) {
                return@withContext FinalizedFileResult(
                    finalFilePath = finalLocalFile.absolutePath,
                    finalUri = mediaUri.toString()
                )
            }
        }

        FinalizedFileResult(
            finalFilePath = finalLocalFile.absolutePath,
            finalUri = Uri.fromFile(finalLocalFile).toString()
        )
    }

    private fun uniqueTargetFile(dir: File, desiredFileName: String): File {
        var candidate = File(dir, desiredFileName)
        if (!candidate.exists()) return candidate
        val dotIdx = desiredFileName.lastIndexOf('.')
        val base = if (dotIdx > 0) desiredFileName.substring(0, dotIdx) else desiredFileName
        val ext = if (dotIdx > 0) desiredFileName.substring(dotIdx) else ""
        var counter = 1
        while (candidate.exists()) {
            candidate = File(dir, "$base ($counter)$ext")
            counter++
        }
        return candidate
    }

    private fun exportToSafTree(
        context: Context,
        treeUri: Uri,
        sourceFile: File,
        displayName: String
    ): Uri? {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            val createdUri = DocumentsContract.createDocument(
                context.contentResolver,
                parentDocUri,
                mimeTypeFor(displayName),
                displayName
            ) ?: return null

            context.contentResolver.openOutputStream(createdUri)?.use { out ->
                FileInputStream(sourceFile).use { input ->
                    input.copyTo(out, bufferSize = 64 * 1024)
                }
            }
            createdUri
        } catch (_: Exception) {
            null
        }
    }

    private fun exportToMediaStoreDownloads(
        context: Context,
        sourceFile: File,
        displayName: String,
        autoSortByType: Boolean
    ): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val sub = if (autoSortByType) {
                "${Environment.DIRECTORY_DOWNLOADS}/Plexo/${categorySubfolderFor(displayName)}"
            } else {
                "${Environment.DIRECTORY_DOWNLOADS}/Plexo"
            }
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, mimeTypeFor(displayName))
                put(MediaStore.Downloads.RELATIVE_PATH, sub)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val itemUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(itemUri)?.use { out ->
                FileInputStream(sourceFile).use { input ->
                    input.copyTo(out, bufferSize = 64 * 1024)
                }
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(itemUri, values, null, null)
            itemUri
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        fun categorySubfolderFor(fileName: String): String {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "mp4", "mkv", "mov", "webm", "avi", "m4v" -> "Video"
                "mp3", "flac", "wav", "aac", "ogg", "m4a", "opus" -> "Music"
                "apk", "apks", "xapk", "aab" -> "APKs"
                "zip", "tar", "gz", "tgz", "7z", "rar", "bz2", "xz", "iso" -> "Archives"
                "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "epub" -> "Documents"
                else -> "Other"
            }
        }

        fun mimeTypeFor(fileName: String): String {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "mp4" -> "video/mp4"
                "mkv" -> "video/x-matroska"
                "mp3" -> "audio/mpeg"
                "flac" -> "audio/flac"
                "apk" -> "application/vnd.android.package-archive"
                "zip" -> "application/zip"
                "pdf" -> "application/pdf"
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                else -> "application/octet-stream"
            }
        }
    }
}
