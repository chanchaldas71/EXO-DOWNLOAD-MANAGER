package com.example

import android.app.Application
import com.example.data.local.PlexoDatabase
import com.example.data.preferences.SettingsRepository
import com.example.data.repository.DownloadRepository
import com.example.engine.download.DiskWriter
import com.example.engine.download.DownloadEngine
import com.example.engine.network.NetworkBondingManager
import com.example.engine.network.OkHttpBoundNetworkClientFactory
import com.example.engine.network.ProbeService
import com.example.service.DownloadForegroundService

/**
 * Application container wiring Room persistence, DataStore settings, multi-network bonding,
 * probe service, disk writer, and the download engine via clean constructor injection.
 */
class PlexoApplication : Application() {

    lateinit var database: PlexoDatabase
        private set

    lateinit var repository: DownloadRepository
        private set

    lateinit var settingsRepository: SettingsRepository
        private set

    lateinit var clientFactory: OkHttpBoundNetworkClientFactory
        private set

    lateinit var networkBondingManager: NetworkBondingManager
        private set

    lateinit var probeService: ProbeService
        private set

    lateinit var diskWriter: DiskWriter
        private set

    lateinit var downloadEngine: DownloadEngine
        private set

    override fun onCreate() {
        super.onCreate()

        database = PlexoDatabase.getInstance(this)
        repository = DownloadRepository(
            downloadDao = database.downloadDao(),
            chunkDao = database.chunkDao(),
            networkConfigDao = database.networkConfigDao()
        )
        settingsRepository = SettingsRepository(this)
        clientFactory = OkHttpBoundNetworkClientFactory()
        networkBondingManager = NetworkBondingManager(
            context = this,
            repository = repository,
            settingsRepository = settingsRepository,
            clientFactory = clientFactory
        )
        probeService = ProbeService(clientFactory.getDefaultClient())
        diskWriter = DiskWriter(this)

        downloadEngine = DownloadEngine(
            repository = repository,
            settingsRepository = settingsRepository,
            networkManager = networkBondingManager,
            probeService = probeService,
            diskWriter = diskWriter,
            clientFactory = clientFactory,
            onActiveDownloadsChanged = { hasActive ->
                if (hasActive) {
                    DownloadForegroundService.ensureStarted(this)
                } else {
                    DownloadForegroundService.stopIfIdle(this)
                }
            }
        )

        networkBondingManager.startMonitoring()
    }
}
