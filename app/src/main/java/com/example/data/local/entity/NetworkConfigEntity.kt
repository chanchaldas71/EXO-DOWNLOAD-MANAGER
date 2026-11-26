package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.data.model.NetworkTransportType

@Entity(tableName = "network_configs")
data class NetworkConfigEntity(
    @PrimaryKey val networkId: String,
    val customName: String,
    val transportType: NetworkTransportType,
    val isEnabled: Boolean,
    val maxStreams: Int,
    val isMetered: Boolean
)
