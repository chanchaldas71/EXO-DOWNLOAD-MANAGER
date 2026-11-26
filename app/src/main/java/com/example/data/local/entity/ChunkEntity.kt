package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.data.model.ChunkState

@Entity(
    tableName = "chunks",
    foreignKeys = [
        ForeignKey(
            entity = DownloadEntity::class,
            parentColumns = ["id"],
            childColumns = ["downloadId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["downloadId"])]
)
data class ChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val downloadId: Long,
    val chunkIndex: Int,
    val startByte: Long,
    val endByte: Long,
    val downloadedBytes: Long = 0L,
    val state: ChunkState = ChunkState.PENDING,
    val assignedNetworkId: String? = null
) {
    val totalLength: Long
        get() = if (endByte >= startByte) (endByte - startByte + 1L) else 0L

    val currentOffset: Long
        get() = startByte + downloadedBytes

    val remainingBytes: Long
        get() = (totalLength - downloadedBytes).coerceAtLeast(0L)
}
