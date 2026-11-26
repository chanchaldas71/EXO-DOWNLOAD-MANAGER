package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entity.NetworkConfigEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NetworkConfigDao {
    @Query("SELECT * FROM network_configs")
    fun observeAllConfigs(): Flow<List<NetworkConfigEntity>>

    @Query("SELECT * FROM network_configs")
    suspend fun getAllConfigs(): List<NetworkConfigEntity>

    @Query("SELECT * FROM network_configs WHERE networkId = :networkId")
    suspend fun getById(networkId: String): NetworkConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(config: NetworkConfigEntity)

    @Query("UPDATE network_configs SET customName = :customName WHERE networkId = :networkId")
    suspend fun rename(networkId: String, customName: String)

    @Query("UPDATE network_configs SET isEnabled = :enabled WHERE networkId = :networkId")
    suspend fun setEnabled(networkId: String, enabled: Boolean)

    @Query("UPDATE network_configs SET maxStreams = :maxStreams WHERE networkId = :networkId")
    suspend fun setMaxStreams(networkId: String, maxStreams: Int)
}
