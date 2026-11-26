package com.example.data.repository

import com.example.data.local.dao.ChunkDao
import com.example.data.local.dao.DownloadDao
import com.example.data.local.dao.NetworkConfigDao
import com.example.data.local.entity.ChunkEntity
import com.example.data.local.entity.DownloadEntity
import com.example.data.local.entity.NetworkConfigEntity
import com.example.data.model.DownloadStatus
import kotlinx.coroutines.flow.Flow

class DownloadRepository(
    private val downloadDao: DownloadDao,
    private val chunkDao: ChunkDao,
    private val networkConfigDao: NetworkConfigDao
) {
    val allDownloads: Flow<List<DownloadEntity>> = downloadDao.observeAllDownloads()
    val allNetworkConfigs: Flow<List<NetworkConfigEntity>> = networkConfigDao.observeAllConfigs()

    fun observeDownload(id: Long): Flow<DownloadEntity?> = downloadDao.observeDownloadById(id)

    fun observeChunks(downloadId: Long): Flow<List<ChunkEntity>> =
        chunkDao.observeChunksForDownload(downloadId)

    suspend fun getDownload(id: Long): DownloadEntity? = downloadDao.getById(id)

    suspend fun getDownloadsByStatuses(vararg statuses: DownloadStatus): List<DownloadEntity> =
        downloadDao.getByStatuses(statuses.toList())

    suspend fun insertDownload(download: DownloadEntity): Long {
        val nextOrder = downloadDao.getMaxQueueOrder() + 1
        return downloadDao.insert(download.copy(queueOrder = nextOrder))
    }

    suspend fun updateDownload(download: DownloadEntity) = downloadDao.update(download)

    suspend fun updateProgress(id: Long, downloadedBytes: Long, totalBytes: Long) =
        downloadDao.updateProgress(id, downloadedBytes, totalBytes)

    suspend fun updateStatus(id: Long, status: DownloadStatus, errorMessage: String? = null) =
        downloadDao.updateStatus(id, status, errorMessage)

    suspend fun updateRefreshedUrl(id: Long, newUrl: String, newFinalUrl: String, status: DownloadStatus) =
        downloadDao.updateRefreshedUrl(id, newUrl, newFinalUrl, status)

    suspend fun updateStreamOverrides(id: Long, overrides: Map<String, Int>) =
        downloadDao.updateStreamOverrides(id, DownloadEntity.encodeStreamOverrides(overrides))

    suspend fun reorderQueue(orderedIds: List<Long>) {
        orderedIds.forEachIndexed { index, id ->
            downloadDao.updateQueueOrder(id, index)
        }
    }

    suspend fun deleteDownload(id: Long) = downloadDao.deleteById(id)

    suspend fun deleteDownloads(ids: List<Long>) = downloadDao.deleteByIds(ids)

    suspend fun getChunks(downloadId: Long): List<ChunkEntity> =
        chunkDao.getChunksForDownload(downloadId)

    suspend fun saveChunks(downloadId: Long, chunks: List<ChunkEntity>) =
        chunkDao.replaceChunksForDownload(downloadId, chunks)

    suspend fun getAllNetworkConfigs(): List<NetworkConfigEntity> =
        networkConfigDao.getAllConfigs()

    suspend fun getNetworkConfig(networkId: String): NetworkConfigEntity? =
        networkConfigDao.getById(networkId)

    suspend fun upsertNetworkConfig(config: NetworkConfigEntity) =
        networkConfigDao.upsert(config)

    suspend fun renameNetwork(networkId: String, name: String) =
        networkConfigDao.rename(networkId, name)

    suspend fun setNetworkEnabled(networkId: String, enabled: Boolean) =
        networkConfigDao.setEnabled(networkId, enabled)

    suspend fun setNetworkMaxStreams(networkId: String, maxStreams: Int) =
        networkConfigDao.setMaxStreams(networkId, maxStreams)
}
