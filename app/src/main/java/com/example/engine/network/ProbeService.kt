package com.example.engine.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

sealed interface ProbeResult {
    data class Success(
        val originalUrl: String,
        val finalUrl: String,
        val fileName: String,
        val contentLength: Long,
        val supportsRanges: Boolean,
        val etag: String?,
        val lastModified: String?,
        val contentType: String?
    ) : ProbeResult

    data class Failure(
        val httpCode: Int?,
        val userMessage: String,
        val isExpiredLink: Boolean = false
    ) : ProbeResult
}

/**
 * Probes a remote URL via HTTP HEAD (falling back to GET with Range: bytes=0-0 if HEAD is
 * rejected or incomplete), follows redirects, and extracts filename, length, ETag, Last-Modified,
 * and Accept-Ranges support.
 */
class ProbeService(
    private val defaultClient: OkHttpClient
) {
    suspend fun probe(
        url: String,
        client: OkHttpClient = defaultClient
    ): ProbeResult = withContext(Dispatchers.IO) {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return@withContext ProbeResult.Failure(
                httpCode = null,
                userMessage = "Enter a valid http:// or https:// download URL"
            )
        }

        val headResult = runCatching { executeHead(trimmed, client) }.getOrNull()

        if (headResult != null && headResult.code in 200..299) {
            val length = headResult.contentLength
            val acceptRanges = headResult.acceptRanges.equals("bytes", ignoreCase = true)
            if (length > 0L && acceptRanges) {
                return@withContext ProbeResult.Success(
                    originalUrl = trimmed,
                    finalUrl = headResult.finalUrl,
                    fileName = headResult.fileName,
                    contentLength = length,
                    supportsRanges = true,
                    etag = headResult.etag,
                    lastModified = headResult.lastModified,
                    contentType = headResult.contentType
                )
            }
            val rangeProbe = runCatching { executeRangeGet(headResult.finalUrl, client) }.getOrNull()
            if (rangeProbe != null && rangeProbe.code in listOf(200, 206)) {
                val resolvedLength = if (rangeProbe.contentLength > 0L) rangeProbe.contentLength else length
                val resolvedRanges = rangeProbe.supportsRanges || acceptRanges
                return@withContext ProbeResult.Success(
                    originalUrl = trimmed,
                    finalUrl = rangeProbe.finalUrl,
                    fileName = rangeProbe.fileName.ifBlank { headResult.fileName },
                    contentLength = resolvedLength,
                    supportsRanges = resolvedRanges,
                    etag = rangeProbe.etag ?: headResult.etag,
                    lastModified = rangeProbe.lastModified ?: headResult.lastModified,
                    contentType = rangeProbe.contentType ?: headResult.contentType
                )
            }
            return@withContext ProbeResult.Success(
                originalUrl = trimmed,
                finalUrl = headResult.finalUrl,
                fileName = headResult.fileName,
                contentLength = length,
                supportsRanges = acceptRanges,
                etag = headResult.etag,
                lastModified = headResult.lastModified,
                contentType = headResult.contentType
            )
        }

        val rangeResult = try {
            executeRangeGet(trimmed, client)
        } catch (e: Exception) {
            return@withContext ProbeResult.Failure(
                httpCode = headResult?.code,
                userMessage = humanizeNetworkError(e)
            )
        }

        return@withContext when (rangeResult.code) {
            200, 206 -> ProbeResult.Success(
                originalUrl = trimmed,
                finalUrl = rangeResult.finalUrl,
                fileName = rangeResult.fileName,
                contentLength = rangeResult.contentLength,
                supportsRanges = rangeResult.supportsRanges,
                etag = rangeResult.etag,
                lastModified = rangeResult.lastModified,
                contentType = rangeResult.contentType
            )
            403, 410 -> ProbeResult.Failure(
                httpCode = rangeResult.code,
                userMessage = "Link expired — paste a refreshed URL to resume",
                isExpiredLink = true
            )
            404 -> ProbeResult.Failure(
                httpCode = 404,
                userMessage = "File not found on server (HTTP 404)"
            )
            401 -> ProbeResult.Failure(
                httpCode = 401,
                userMessage = "Authentication required by server (HTTP 401)"
            )
            429 -> ProbeResult.Failure(
                httpCode = 429,
                userMessage = "Server rate-limited connections (HTTP 429) — try fewer streams"
            )
            in 500..599 -> ProbeResult.Failure(
                httpCode = rangeResult.code,
                userMessage = "Server error (HTTP ${rangeResult.code}) — try again shortly"
            )
            else -> ProbeResult.Failure(
                httpCode = rangeResult.code,
                userMessage = "Server rejected request (HTTP ${rangeResult.code})"
            )
        }
    }

    private data class RawProbeData(
        val code: Int,
        val finalUrl: String,
        val fileName: String,
        val contentLength: Long,
        val acceptRanges: String?,
        val supportsRanges: Boolean,
        val etag: String?,
        val lastModified: String?,
        val contentType: String?
    )

    private fun executeHead(url: String, client: OkHttpClient): RawProbeData {
        val req = Request.Builder()
            .url(url)
            .head()
            .header("User-Agent", NetworkBondingManager.USER_AGENT)
            .header("Accept-Encoding", "identity")
            .build()
        client.newCall(req).execute().use { res ->
            val finalUrl = res.request.url.toString()
            val length = res.header("Content-Length")?.toLongOrNull() ?: -1L
            val acceptRanges = res.header("Accept-Ranges")
            val fileName = extractFileName(res.header("Content-Disposition"), finalUrl)
            return RawProbeData(
                code = res.code,
                finalUrl = finalUrl,
                fileName = fileName,
                contentLength = length,
                acceptRanges = acceptRanges,
                supportsRanges = acceptRanges.equals("bytes", ignoreCase = true),
                etag = res.header("ETag"),
                lastModified = res.header("Last-Modified"),
                contentType = res.header("Content-Type")
            )
        }
    }

    private fun executeRangeGet(url: String, client: OkHttpClient): RawProbeData {
        val req = Request.Builder()
            .url(url)
            .get()
            .header("Range", "bytes=0-0")
            .header("User-Agent", NetworkBondingManager.USER_AGENT)
            .header("Accept-Encoding", "identity")
            .build()
        client.newCall(req).execute().use { res ->
            val finalUrl = res.request.url.toString()
            val contentRange = res.header("Content-Range")
            val totalFromRange = parseTotalFromContentRange(contentRange)
            val contentLength = when {
                totalFromRange > 0L -> totalFromRange
                res.code == 200 -> res.header("Content-Length")?.toLongOrNull() ?: -1L
                else -> -1L
            }
            val acceptRanges = res.header("Accept-Ranges")
            val supportsRanges = res.code == 206 || acceptRanges.equals("bytes", ignoreCase = true)
            val fileName = extractFileName(res.header("Content-Disposition"), finalUrl)
            return RawProbeData(
                code = res.code,
                finalUrl = finalUrl,
                fileName = fileName,
                contentLength = contentLength,
                acceptRanges = acceptRanges,
                supportsRanges = supportsRanges,
                etag = res.header("ETag"),
                lastModified = res.header("Last-Modified"),
                contentType = res.header("Content-Type")
            )
        }
    }

    companion object {
        fun parseTotalFromContentRange(header: String?): Long {
            if (header.isNullOrBlank()) return -1L
            val slashIdx = header.lastIndexOf('/')
            if (slashIdx < 0 || slashIdx >= header.length - 1) return -1L
            return header.substring(slashIdx + 1).trim().toLongOrNull() ?: -1L
        }

        fun extractFileName(contentDisposition: String?, finalUrl: String): String {
            if (!contentDisposition.isNullOrBlank()) {
                val utf8Regex = Regex("""filename\*\s*=\s*(?:UTF-8|utf-8)''([^;\s]+)""")
                utf8Regex.find(contentDisposition)?.groupValues?.getOrNull(1)?.let { encoded ->
                    val decoded = runCatching {
                        URLDecoder.decode(encoded, StandardCharsets.UTF_8.name())
                    }.getOrDefault(encoded)
                    val clean = sanitizeFileName(decoded)
                    if (clean.isNotBlank()) return clean
                }
                val stdRegex = Regex("""filename\s*=\s*"?([^";]+)"?""", RegexOption.IGNORE_CASE)
                stdRegex.find(contentDisposition)?.groupValues?.getOrNull(1)?.let { raw ->
                    val clean = sanitizeFileName(raw)
                    if (clean.isNotBlank()) return clean
                }
            }

            val pathPart = finalUrl.substringBefore('?').substringBefore('#')
            val rawSegment = pathPart.substringAfterLast('/', "")
            val decoded = runCatching {
                URLDecoder.decode(rawSegment, StandardCharsets.UTF_8.name())
            }.getOrDefault(rawSegment)
            val sanitized = sanitizeFileName(decoded)
            return sanitized.ifBlank { "plexo_download_${System.currentTimeMillis()}.bin" }
        }

        fun sanitizeFileName(raw: String): String {
            return raw.trim()
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .replace(Regex("""[<>:"/\\|?*\x00-\x1F]"""), "_")
                .trim('.')
                .take(180)
        }

        fun humanizeNetworkError(throwable: Throwable): String {
            val msg = throwable.message.orEmpty()
            return when {
                throwable is java.net.UnknownHostException -> "Can't reach server (DNS lookup failed)"
                throwable is java.net.SocketTimeoutException -> "Connection timed out — server not responding"
                throwable is java.net.ConnectException -> "Can't reach server (connection refused)"
                throwable is javax.net.ssl.SSLException -> "Secure TLS handshake failed with server"
                msg.contains("ENOSPC", ignoreCase = true) -> "Storage full"
                else -> "Network error: ${msg.ifBlank { throwable.javaClass.simpleName }}"
            }
        }
    }
}
