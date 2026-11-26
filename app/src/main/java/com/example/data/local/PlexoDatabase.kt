package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.example.data.local.dao.ChunkDao
import com.example.data.local.dao.DownloadDao
import com.example.data.local.dao.NetworkConfigDao
import com.example.data.local.entity.ChunkEntity
import com.example.data.local.entity.DownloadEntity
import com.example.data.local.entity.NetworkConfigEntity
import com.example.data.model.ChunkState
import com.example.data.model.DownloadStatus
import com.example.data.model.HashAlgorithm
import com.example.data.model.NetworkTransportType

class PlexoTypeConverters {
    @TypeConverter
    fun fromDownloadStatus(value: DownloadStatus): String = value.name

    @TypeConverter
    fun toDownloadStatus(value: String): DownloadStatus =
        runCatching { DownloadStatus.valueOf(value) }.getOrDefault(DownloadStatus.PAUSED)

    @TypeConverter
    fun fromChunkState(value: ChunkState): String = value.name

    @TypeConverter
    fun toChunkState(value: String): ChunkState =
        runCatching { ChunkState.valueOf(value) }.getOrDefault(ChunkState.PENDING)

    @TypeConverter
    fun fromNetworkTransportType(value: NetworkTransportType): String = value.name

    @TypeConverter
    fun toNetworkTransportType(value: String): NetworkTransportType =
        runCatching { NetworkTransportType.valueOf(value) }.getOrDefault(NetworkTransportType.OTHER)

    @TypeConverter
    fun fromHashAlgorithm(value: HashAlgorithm): String = value.name

    @TypeConverter
    fun toHashAlgorithm(value: String): HashAlgorithm =
        runCatching { HashAlgorithm.valueOf(value) }.getOrDefault(HashAlgorithm.NONE)
}

@Database(
    entities = [
        DownloadEntity::class,
        ChunkEntity::class,
        NetworkConfigEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(PlexoTypeConverters::class)
abstract class PlexoDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao
    abstract fun chunkDao(): ChunkDao
    abstract fun networkConfigDao(): NetworkConfigDao

    companion object {
        @Volatile
        private var INSTANCE: PlexoDatabase? = null

        fun getInstance(context: Context): PlexoDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    PlexoDatabase::class.java,
                    "plexo_database.db"
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
