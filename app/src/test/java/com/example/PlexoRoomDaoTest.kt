package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.PlexoDatabase
import com.example.data.local.entity.ChunkEntity
import com.example.data.local.entity.DownloadEntity
import com.example.data.local.entity.NetworkConfigEntity
import com.example.data.model.ChunkState
import com.example.data.model.DownloadStatus
import com.example.data.model.NetworkTransportType
import com.example.data.repository.DownloadRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlexoRoomDaoTest {

    private lateinit var db: PlexoDatabase
    private lateinit var repository: DownloadRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PlexoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = DownloadRepository(
            downloadDao = db.downloadDao(),
            chunkDao = db.chunkDao(),
            networkConfigDao = db.networkConfigDao()
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `downloads and chunk maps persist across resume and cascade delete cleanly`() = runTest {
        val downloadId = repository.insertDownload(
            DownloadEntity(
                url = "https://cdn.example.com/ubuntu.iso",
                fileName = "ubuntu.iso",
                totalBytes = 8L * 1024L * 1024L,
                etag = "\"abc123\"",
                status = DownloadStatus.DOWNLOADING
            )
        )

        val chunks = listOf(
            ChunkEntity(
                downloadId = downloadId,
                chunkIndex = 0,
                startByte = 0L,
                endByte = 4L * 1024L * 1024L - 1L,
                downloadedBytes = 4L * 1024L * 1024L,
                state = ChunkState.COMPLETED
            ),
            ChunkEntity(
                downloadId = downloadId,
                chunkIndex = 1,
                startByte = 4L * 1024L * 1024L,
                endByte = 8L * 1024L * 1024L - 1L,
                downloadedBytes = 1024L * 1024L,
                state = ChunkState.IN_PROGRESS,
                assignedNetworkId = "wifi_wlan0"
            )
        )
        repository.saveChunks(downloadId, chunks)

        val restoredChunks = repository.getChunks(downloadId)
        assertEquals(2, restoredChunks.size)
        assertEquals(ChunkState.COMPLETED, restoredChunks[0].state)
        assertEquals(1024L * 1024L, restoredChunks[1].downloadedBytes)

        // Cascade delete removes both download and its chunk map
        repository.deleteDownload(downloadId)
        assertTrue(repository.getChunks(downloadId).isEmpty())
    }

    @Test
    fun `network config persists custom adapter names enabled state and stream caps`() = runTest {
        repository.upsertNetworkConfig(
            NetworkConfigEntity(
                networkId = "cellular_rmnet0",
                customName = "5G Cellular",
                transportType = NetworkTransportType.CELLULAR,
                isEnabled = false,
                maxStreams = 4,
                isMetered = true
            )
        )

        repository.renameNetwork("cellular_rmnet0", "T-Mobile 5G UC")
        repository.setNetworkEnabled("cellular_rmnet0", true)
        repository.setNetworkMaxStreams("cellular_rmnet0", 6)

        val saved = repository.getNetworkConfig("cellular_rmnet0")
        assertNotNull(saved)
        assertEquals("T-Mobile 5G UC", saved?.customName)
        assertEquals(true, saved?.isEnabled)
        assertEquals(6, saved?.maxStreams)

        val all = repository.allNetworkConfigs.first()
        assertEquals(1, all.size)
    }
}
