package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.data.local.entity.ChunkEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChunkDao {
    @Query("SELECT * FROM chunks WHERE downloadId = :downloadId ORDER BY startByte ASC")
    fun observeChunksForDownload(downloadId: Long): Flow<List<ChunkEntity>>

    @Query("SELECT * FROM chunks WHERE downloadId = :downloadId ORDER BY startByte ASC")
    suspend fun getChunksForDownload(downloadId: Long): List<ChunkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chunks: List<ChunkEntity>): List<Long>

    @Query("DELETE FROM chunks WHERE downloadId = :downloadId")
    suspend fun deleteForDownload(downloadId: Long)

    @Transaction
    suspend fun replaceChunksForDownload(downloadId: Long, chunks: List<ChunkEntity>) {
        deleteForDownload(downloadId)
        if (chunks.isNotEmpty()) {
            insertAll(chunks)
        }
    }
}
