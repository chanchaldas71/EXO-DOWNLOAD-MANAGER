package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.data.model.DownloadStatus
import com.example.data.model.HashAlgorithm

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val url: String,
    val finalUrl: String = url,
    val fileName: String,
    val saveUri: String = "",
    val filePath: String = "",
    val totalBytes: Long = -1L,
    val downloadedBytes: Long = 0L,
    val etag: String? = null,
    val lastModified: String? = null,
    val supportsRanges: Boolean = true,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val errorMessage: String? = null,
    val expectedHash: String? = null,
    val hashAlgorithm: HashAlgorithm = HashAlgorithm.NONE,
    val hashVerified: Boolean? = null,
    val queueOrder: Int = 0,
    /**
     * Serialized map of networkId -> maxStreams override for this download ("net1=8;net2=4").
     * Empty means use global per-network settings.
     */
    val networkStreamOverrides: String = "",
    /**
     * Comma-separated enabled networkIds for this download. Empty means use all globally enabled networks.
     */
    val allowedNetworkIds: String = "",
    val chunkSizeBytes: Long = 4L * 1024L * 1024L,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null
) {
    fun parsedStreamOverrides(): Map<String, Int> {
        if (networkStreamOverrides.isBlank()) return emptyMap()
        return networkStreamOverrides.split(";")
            .mapNotNull { entry ->
                val parts = entry.split("=")
                if (parts.size == 2) {
                    val count = parts[1].toIntOrNull()
                    if (count != null && count > 0) parts[0] to count else null
                } else null
            }
            .toMap()
    }

    fun parsedAllowedNetworks(): Set<String> {
        if (allowedNetworkIds.isBlank()) return emptySet()
        return allowedNetworkIds.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    companion object {
        fun encodeStreamOverrides(map: Map<String, Int>): String =
            map.entries.joinToString(";") { "${it.key}=${it.value}" }

        fun encodeAllowedNetworks(ids: Collection<String>): String =
            ids.filter { it.isNotBlank() }.distinct().joinToString(",")
    }
}
