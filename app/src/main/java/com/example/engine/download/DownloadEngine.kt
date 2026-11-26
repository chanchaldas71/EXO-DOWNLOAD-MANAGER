package com.example.engine.download

import com.example.data.local.entity.ChunkEntity
import com.example.data.local.entity.DownloadEntity
import com.example.data.model.ChunkState
import com.example.data.model.DownloadStatus
import com.example.data.model.HashAlgorithm
import com.example.data.model.NetworkTransportType
import com.example.data.model.ReachabilityState
import com.example.data.preferences.PlexoSettings
import com.example.data.preferences.SettingsRepository
import com.example.data.repository.DownloadRepository
import com.example.engine.network.BoundNetworkClientFactory
import com.example.engine.network.NetworkAdapterInfo
import com.example.engine.network.NetworkBondingManager
import com.example.engine.network.NetworkHotPlugEvent
import com.example.engine.network.ProbeResult
import com.example.engine.network.ProbeService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class ResumeMismatchPrompt(
    val downloadId: Long,
    val fileName: String,
    val reason: String
)

sealed interface EngineAlertEvent {
    data class Completed(val downloadId: Long, val fileName: String, val filePath: String) : EngineAlertEvent
    data class Failed(val downloadId: Long, val fileName: String, val reason: String) : EngineAlertEvent
    data class ExpiredLink(val downloadId: Long, val fileName: String) : EngineAlertEvent
    data class AdapterDropped(val networkName: String) : EngineAlertEvent
    data class ResumeMismatch(val prompt: ResumeMismatchPrompt) : EngineAlertEvent
}

data class NewDownloadRequest(
    val url: String,
    val fileName: String,
    val saveUri: String = "",
    val probe: ProbeResult.Success? = null,
    val allowedNetworkIds: Set<String> = emptySet(),
    val streamOverrides: Map<String, Int> = emptyMap(),
    val expectedHash: String = "",
    val hashAlgorithm: HashAlgorithm = HashAlgorithm.NONE,
    val startImmediately: Boolean = true
)

/**
 * Core segmented multi-network bonding download orchestrator.
 */
class DownloadEngine(
    private val repository: DownloadRepository,
    private val settingsRepository: SettingsRepository,
    private val networkManager: NetworkBondingManager,
    val probeService: ProbeService,
    val diskWriter: DiskWriter,
    private val clientFactory: BoundNetworkClientFactory = networkManager.clientFactory,
    private val stallTimeoutMs: Long = DEFAULT_STALL_TIMEOUT_MS,
    private val persistIntervalMs: Long = DEFAULT_PERSIST_INTERVAL_MS,
    private val onActiveDownloadsChanged: ((hasActive: Boolean) -> Unit)? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionMutex = Mutex()
    private val speedLimiter = SpeedLimiter()

    private val activeSessions = ConcurrentHashMap<Long, ActiveDownloadSession>()
    private val manualBypassIds = ConcurrentHashMap.newKeySet<Long>()

    private val _liveMetrics = MutableStateFlow<Map<Long, DownloadLiveMetrics>>(emptyMap())
    val liveMetrics: StateFlow<Map<Long, DownloadLiveMetrics>> = _liveMetrics.asStateFlow()

    private val _alerts = MutableSharedFlow<EngineAlertEvent>(extraBufferCapacity = 32)
    val alerts: SharedFlow<EngineAlertEvent> = _alerts.asSharedFlow()

    private val _freeStorageBytes = MutableStateFlow(diskWriter.getAvailableStorageBytes())
    val freeStorageBytes: StateFlow<Long> = _freeStorageBytes.asStateFlow()

    @Volatile
    private var settings: PlexoSettings = PlexoSettings()

    private class ActiveDownloadSession(
        val downloadId: Long,
        val entity: DownloadEntity,
        val scheduler: ChunkScheduler,
        val telemetry: TelemetryTracker,
        val partFile: File,
        val writer: PositionalPartWriter,
        val supervisorJob: Job
    ) {
        val networkWorkerJobs = ConcurrentHashMap<String, MutableList<Job>>()
        val expiredTriggered = AtomicBoolean(false)
        val fatalError = MutableStateFlow<String?>(null)
        val pauseRequested = AtomicBoolean(false)
    }

    init {
        scope.launch {
            settingsRepository.settingsFlow.collect { updated ->
                settings = updated
                speedLimiter.setLimitBytesPerSec(updated.effectiveSpeedLimitBytesPerSec())
                evaluateQueue()
            }
        }

        // Listen for hot-plug network events (Wi-Fi/Ethernet join or unplug mid-download)
        scope.launch {
            networkManager.hotPlugEvents.collect { event ->
                when (event) {
                    is NetworkHotPlugEvent.NetworkAdded -> onNetworkHotPlugged(event.adapter)
                    is NetworkHotPlugEvent.NetworkUpdated -> onNetworkUpdated(event.adapter)
                    is NetworkHotPlugEvent.NetworkLost -> onNetworkHotUnplugged(event.networkId)
                }
            }
        }

        // Throttled ~4 Hz UI telemetry publisher + free storage refresh
        scope.launch {
            var tick = 0
            while (isActive) {
                delay(250L)
                publishTelemetryTick()
                tick++
                if (tick % 16 == 0) {
                    _freeStorageBytes.value = diskWriter.getAvailableStorageBytes()
                }
                if (tick % 40 == 0 && settings.schedulerEnabled) {
                    evaluateQueue()
                }
            }
        }

        // Recover any downloads that were left in DOWNLOADING state after an unexpected process kill
        scope.launch {
            recoverInterruptedDownloads()
        }
    }

    private suspend fun recoverInterruptedDownloads() {
        val interrupted = repository.getDownloadsByStatuses(
            DownloadStatus.DOWNLOADING,
            DownloadStatus.VERIFYING
        )
        interrupted.forEach { entity ->
            repository.updateStatus(entity.id, DownloadStatus.PAUSED, "Paused after app restart — tap Resume")
        }
    }

    suspend fun enqueueDownload(request: NewDownloadRequest): Long {
        val probe = request.probe
        val resolvedUrl = probe?.finalUrl ?: request.url.trim()
        val resolvedName = ProbeService.sanitizeFileName(
            request.fileName.ifBlank { probe?.fileName ?: ProbeService.extractFileName(null, resolvedUrl) }
        )
        val currentSettings = settingsRepository.getCurrentSettings()
        val entity = DownloadEntity(
            url = request.url.trim(),
            finalUrl = resolvedUrl,
            fileName = resolvedName,
            saveUri = request.saveUri.ifBlank { currentSettings.defaultFolderUri },
            totalBytes = probe?.contentLength ?: -1L,
            downloadedBytes = 0L,
            etag = probe?.etag,
            lastModified = probe?.lastModified,
            supportsRanges = probe?.supportsRanges ?: true,
            status = DownloadStatus.QUEUED,
            expectedHash = request.expectedHash.trim().ifBlank { null },
            hashAlgorithm = request.hashAlgorithm,
            networkStreamOverrides = DownloadEntity.encodeStreamOverrides(request.streamOverrides),
            allowedNetworkIds = DownloadEntity.encodeAllowedNetworks(request.allowedNetworkIds),
            chunkSizeBytes = currentSettings.defaultChunkSizeBytes
        )
        val id = repository.insertDownload(entity)
        if (request.startImmediately) {
            manualBypassIds.add(id)
            startDownload(id, bypassResumeCheck = true)
        } else {
            evaluateQueue()
        }
        return id
    }

    suspend fun startDownload(downloadId: Long, bypassResumeCheck: Boolean = false) {
        sessionMutex.withLock {
            if (activeSessions.containsKey(downloadId)) return
            val existing = repository.getDownload(downloadId) ?: return
            if (existing.status == DownloadStatus.COMPLETED) return

            // Ensure active multi-network leases are up before probing/downloading
            networkManager.acquireBondingLeases()

            repository.updateStatus(downloadId, DownloadStatus.DOWNLOADING, null)
            onActiveDownloadsChanged?.invoke(true)

            val job = scope.launch {
                runDownloadSession(downloadId, bypassResumeCheck)
            }
            // Placeholder supervisor registration happens inside runDownloadSession once initialized
            job.invokeOnCompletion {
                scope.launch {
                    evaluateQueue()
                    if (activeSessions.isEmpty()) {
                        onActiveDownloadsChanged?.invoke(false)
                    }
                }
            }
        }
    }

    suspend fun pauseDownload(downloadId: Long) {
        val session = activeSessions[downloadId]
        if (session != null) {
            session.pauseRequested.set(true)
            session.supervisorJob.cancel()
            persistSessionChunks(session)
            activeSessions.remove(downloadId)
        }
        repository.updateStatus(downloadId, DownloadStatus.PAUSED, null)
        manualBypassIds.remove(downloadId)
        removeMetricsFor(downloadId)
        if (activeSessions.isEmpty()) {
            onActiveDownloadsChanged?.invoke(false)
        }
        evaluateQueue()
    }

    suspend fun pauseAll() {
        val ids = activeSessions.keys.toList()
        ids.forEach { pauseDownload(it) }
        val queued = repository.getDownloadsByStatuses(DownloadStatus.QUEUED)
        queued.forEach { repository.updateStatus(it.id, DownloadStatus.PAUSED, null) }
    }

    suspend fun resumeDownload(downloadId: Long) {
        manualBypassIds.add(downloadId)
        startDownload(downloadId, bypassResumeCheck = false)
    }

    suspend fun resumeAll() {
        val paused = repository.getDownloadsByStatuses(DownloadStatus.PAUSED, DownloadStatus.FAILED)
        paused.forEach {
            manualBypassIds.add(it.id)
            repository.updateStatus(it.id, DownloadStatus.QUEUED, null)
        }
        evaluateQueue()
    }

    suspend fun forceRestartDownload(downloadId: Long) {
        pauseDownload(downloadId)
        val entity = repository.getDownload(downloadId) ?: return
        // Clean up stale partial file & chunk records
        if (entity.filePath.isNotBlank()) {
            runCatching { File(entity.filePath).delete() }
        }
        repository.saveChunks(downloadId, emptyList())
        repository.updateDownload(
            entity.copy(
                downloadedBytes = 0L,
                status = DownloadStatus.QUEUED,
                errorMessage = null,
                etag = null,
                lastModified = null
            )
        )
        manualBypassIds.add(downloadId)
        startDownload(downloadId, bypassResumeCheck = true)
    }

    suspend fun refreshUrlAndResume(downloadId: Long, newUrl: String): ProbeResult {
        val trimmed = newUrl.trim()
        val probe = probeService.probe(trimmed, resolveProbeClient())
        if (probe is ProbeResult.Success) {
            repository.updateRefreshedUrl(
                id = downloadId,
                newUrl = trimmed,
                newFinalUrl = probe.finalUrl,
                status = DownloadStatus.QUEUED
            )
            manualBypassIds.add(downloadId)
            startDownload(downloadId, bypassResumeCheck = true)
        }
        return probe
    }

    suspend fun cancelAndDelete(downloadId: Long, deleteFileFromDisk: Boolean = false) {
        pauseDownload(downloadId)
        val entity = repository.getDownload(downloadId)
        if (entity != null && entity.filePath.isNotBlank()) {
            val file = File(entity.filePath)
            if (file.name.endsWith(".plexo.part") || deleteFileFromDisk) {
                runCatching { file.delete() }
            }
        }
        repository.deleteDownload(downloadId)
        removeMetricsFor(downloadId)
    }

    suspend fun updateDownloadStreamLimit(downloadId: Long, networkId: String, newMaxStreams: Int) {
        val entity = repository.getDownload(downloadId) ?: return
        val updatedMap = entity.parsedStreamOverrides().toMutableMap()
        updatedMap[networkId] = newMaxStreams.coerceIn(1, 32)
        repository.updateStreamOverrides(downloadId, updatedMap)

        val session = activeSessions[downloadId] ?: return
        val adapter = networkManager.adapters.value.firstOrNull { it.networkId == networkId } ?: return
        respawnWorkersForNetwork(session, adapter, newMaxStreams.coerceIn(1, 32))
    }

    suspend fun evaluateQueue() {
        val currentSettings = settings
        if (currentSettings.schedulerEnabled && !isWithinScheduleWindow(currentSettings)) {
            // Pause non-manual downloads outside schedule window
            activeSessions.keys.toList().forEach { id ->
                if (!manualBypassIds.contains(id)) {
                    pauseDownload(id)
                    repository.updateStatus(id, DownloadStatus.QUEUED, "Waiting for schedule window")
                }
            }
            return
        }

        val maxActive = currentSettings.maxSimultaneousDownloads.coerceAtLeast(1)
        val availableSlots = maxActive - activeSessions.size
        if (availableSlots <= 0) return

        val queued = repository.getDownloadsByStatuses(DownloadStatus.QUEUED)
            .filter { !activeSessions.containsKey(it.id) }
            .take(availableSlots)

        queued.forEach { entity ->
            startDownload(entity.id, bypassResumeCheck = false)
        }
    }

    private fun isWithinScheduleWindow(cfg: PlexoSettings): Boolean {
        val now = Calendar.getInstance()
        val currentMins = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val startMins = cfg.scheduleStartHour * 60 + cfg.scheduleStartMinute
        val endMins = cfg.scheduleEndHour * 60 + cfg.scheduleEndMinute
        return if (startMins <= endMins) {
            currentMins in startMins..endMins
        } else {
            // Overnight window e.g. 23:00 -> 06:00
            currentMins >= startMins || currentMins <= endMins
        }
    }

    private fun resolveProbeClient(): OkHttpClient {
        val firstUsable = networkManager.getUsableAdapters().firstOrNull()
        return if (firstUsable != null) {
            clientFactory.getClientFor(firstUsable)
        } else {
            clientFactory.getDefaultClient()
        }
    }

    private suspend fun runDownloadSession(downloadId: Long, bypassResumeCheck: Boolean) {
        var entity = repository.getDownload(downloadId) ?: return
        val existingChunks = repository.getChunks(downloadId)
        val isResuming = existingChunks.isNotEmpty() && entity.downloadedBytes > 0L

        // 1. Probe target URL (or re-probe on resume to validate ETag / Last-Modified / Content-Length)
        val probeClient = resolveProbeClient()
        val probeResult = probeService.probe(entity.finalUrl.ifBlank { entity.url }, probeClient)
        if (probeResult is ProbeResult.Failure) {
            if (probeResult.isExpiredLink) {
                repository.updateStatus(downloadId, DownloadStatus.EXPIRED_LINK, probeResult.userMessage)
                _alerts.tryEmit(EngineAlertEvent.ExpiredLink(downloadId, entity.fileName))
            } else {
                repository.updateStatus(downloadId, DownloadStatus.FAILED, probeResult.userMessage)
                _alerts.tryEmit(EngineAlertEvent.Failed(downloadId, entity.fileName, probeResult.userMessage))
            }
            return
        }

        val probe = probeResult as ProbeResult.Success

        // Smart Resume check: verify ETag / Last-Modified / Content-Length still match before resuming
        if (isResuming && !bypassResumeCheck) {
            val mismatchReason = detectResumeMismatch(entity, probe)
            if (mismatchReason != null) {
                repository.updateStatus(
                    downloadId,
                    DownloadStatus.PAUSED,
                    "Remote file changed ($mismatchReason) — confirm restart"
                )
                _alerts.tryEmit(
                    EngineAlertEvent.ResumeMismatch(
                        ResumeMismatchPrompt(
                            downloadId = downloadId,
                            fileName = entity.fileName,
                            reason = mismatchReason
                        )
                    )
                )
                return
            }
        }

        // Update metadata from probe
        val partFile = if (entity.filePath.isNotBlank() && entity.filePath.endsWith(".plexo.part")) {
            File(entity.filePath)
        } else {
            diskWriter.resolvePartFile(
                fileName = entity.fileName,
                downloadId = downloadId,
                autoSortByType = settings.autoSortByType
            )
        }

        val noteNoRange = if (!probe.supportsRanges) {
            "Server doesn't allow split downloads — using single stream"
        } else null

        entity = entity.copy(
            finalUrl = probe.finalUrl,
            totalBytes = probe.contentLength,
            etag = probe.etag ?: entity.etag,
            lastModified = probe.lastModified ?: entity.lastModified,
            supportsRanges = probe.supportsRanges,
            filePath = partFile.absolutePath,
            status = DownloadStatus.DOWNLOADING,
            errorMessage = noteNoRange
        )
        repository.updateDownload(entity)

        // 2. Check free storage & open positional `.plexo.part` writer
        val writer = try {
            diskWriter.openPartWriter(partFile, entity.totalBytes)
        } catch (sfe: StorageFullException) {
            val msg = sfe.message ?: "Storage full"
            repository.updateStatus(downloadId, DownloadStatus.FAILED, msg)
            _alerts.tryEmit(EngineAlertEvent.Failed(downloadId, entity.fileName, msg))
            return
        } catch (e: Exception) {
            val msg = "Unable to open destination file: ${e.message}"
            repository.updateStatus(downloadId, DownloadStatus.FAILED, msg)
            _alerts.tryEmit(EngineAlertEvent.Failed(downloadId, entity.fileName, msg))
            return
        }

        // 3. Build work-stealing scheduler & telemetry tracker
        val scheduler = ChunkScheduler(
            downloadId = downloadId,
            totalBytes = entity.totalBytes,
            supportsRanges = entity.supportsRanges,
            chunkSizeBytes = entity.chunkSizeBytes,
            existingChunks = if (isResuming && partFile.exists()) existingChunks else emptyList()
        )
        val telemetry = TelemetryTracker(
            downloadId = downloadId,
            totalBytes = entity.totalBytes,
            initialDownloadedBytes = scheduler.totalDownloadedBytes()
        )

        val sessionSupervisor = SupervisorJob()
        val session = ActiveDownloadSession(
            downloadId = downloadId,
            entity = entity,
            scheduler = scheduler,
            telemetry = telemetry,
            partFile = partFile,
            writer = writer,
            supervisorJob = sessionSupervisor
        )
        activeSessions[downloadId] = session

        try {
            // Persist initial chunk table immediately for crash safety
            persistSessionChunks(session)

            // 4. Probe reachability on every enabled adapter and launch bound worker pools
            val usableAdapters = resolveAdaptersForSession(entity)
            val reachableAdapters = mutableListOf<NetworkAdapterInfo>()
            for (adapter in usableAdapters) {
                val ok = networkManager.probeReachability(entity.finalUrl, adapter)
                if (ok) {
                    reachableAdapters.add(adapter)
                }
            }

            // Fallback to default OS route if Android ConnectivityManager hasn't surfaced bound handles yet
            val adaptersToRun = if (reachableAdapters.isNotEmpty()) {
                reachableAdapters
            } else {
                listOf(fallbackDefaultAdapter())
            }

            // If server doesn't support byte ranges, run single stream on fastest active adapter
            val effectiveAdapters = if (!entity.supportsRanges) {
                listOf(adaptersToRun.maxByOrNull { it.linkDownstreamKbps } ?: adaptersToRun.first())
            } else {
                adaptersToRun
            }

            effectiveAdapters.forEach { adapter ->
                val maxStreams = if (!entity.supportsRanges) 1 else resolveMaxStreams(entity, adapter)
                spawnWorkersForNetwork(session, adapter, maxStreams)
            }

            // 5. Periodic 2-second crash-proof persistence loop + completion monitor
            val sessionScope = CoroutineScope(sessionSupervisor + Dispatchers.IO)
            val persistJob = sessionScope.launch {
                while (isActive) {
                    delay(persistIntervalMs)
                    persistSessionChunks(session)
                }
            }

            // Wait until all chunks complete, pause is requested, expired link hits, or fatal error occurs
            while (sessionSupervisor.isActive) {
                if (session.pauseRequested.get()) break
                if (session.expiredTriggered.get()) break
                if (session.fatalError.value != null) break
                if (scheduler.isAllCompleted()) break

                // Check if every worker exited without completing (e.g., all networks dropped/parked)
                val anyAlive = session.networkWorkerJobs.values.flatten().any { it.isActive }
                if (!anyAlive && !scheduler.isAllCompleted()) {
                    // Wait briefly for a hot-plugged network or fail cleanly
                    delay(1000L)
                    val stillNone = session.networkWorkerJobs.values.flatten().none { it.isActive }
                    if (stillNone && !scheduler.isAllCompleted()) {
                        session.fatalError.value = "All network adapters lost or unreachable"
                        break
                    }
                }
                delay(200L)
            }

            persistJob.cancel()
            sessionSupervisor.cancel()
            persistSessionChunks(session)

            // 6. Handle exit outcome
            when {
                session.pauseRequested.get() -> {
                    repository.updateStatus(downloadId, DownloadStatus.PAUSED, null)
                }
                session.expiredTriggered.get() -> {
                    val msg = "Link expired — paste a refreshed URL to resume"
                    repository.updateStatus(downloadId, DownloadStatus.EXPIRED_LINK, msg)
                    _alerts.tryEmit(EngineAlertEvent.ExpiredLink(downloadId, entity.fileName))
                }
                session.fatalError.value != null -> {
                    val err = session.fatalError.value!!
                    repository.updateStatus(downloadId, DownloadStatus.FAILED, err)
                    _alerts.tryEmit(EngineAlertEvent.Failed(downloadId, entity.fileName, err))
                }
                scheduler.isAllCompleted() -> {
                    val finalDownloaded = scheduler.totalDownloadedBytes()
                    if (entity.totalBytes <= 0L && finalDownloaded > 0L) {
                        writer.truncateTo(finalDownloaded)
                    }
                    writer.close()

                    // Optional SHA-256 / MD5 checksum verification
                    var hashVerified: Boolean? = null
                    if (entity.hashAlgorithm != HashAlgorithm.NONE && !entity.expectedHash.isNullOrBlank()) {
                        repository.updateStatus(downloadId, DownloadStatus.VERIFYING, "Verifying ${entity.hashAlgorithm.label}…")
                        hashVerified = diskWriter.verifyChecksum(
                            file = partFile,
                            expectedHash = entity.expectedHash,
                            algorithm = entity.hashAlgorithm
                        )
                        if (!hashVerified) {
                            val msg = "${entity.hashAlgorithm.label} checksum mismatch — file may be corrupted"
                            repository.updateDownload(
                                entity.copy(
                                    downloadedBytes = finalDownloaded,
                                    status = DownloadStatus.FAILED,
                                    hashVerified = false,
                                    errorMessage = msg
                                )
                            )
                            _alerts.tryEmit(EngineAlertEvent.Failed(downloadId, entity.fileName, msg))
                            return
                        }
                    }

                    // Rename .plexo.part to final file and publish to SAF / MediaStore
                    val finalized = diskWriter.finalizePartFile(
                        partFile = partFile,
                        desiredFileName = entity.fileName,
                        autoSortByType = settings.autoSortByType,
                        safTreeUri = entity.saveUri
                    )

                    repository.updateDownload(
                        entity.copy(
                            totalBytes = if (entity.totalBytes > 0L) entity.totalBytes else finalDownloaded,
                            downloadedBytes = finalDownloaded,
                            filePath = finalized.finalFilePath,
                            saveUri = finalized.finalUri,
                            status = DownloadStatus.COMPLETED,
                            hashVerified = hashVerified,
                            errorMessage = null,
                            completedAt = System.currentTimeMillis()
                        )
                    )
                    _alerts.tryEmit(
                        EngineAlertEvent.Completed(downloadId, entity.fileName, finalized.finalFilePath)
                    )
                }
            }
        } catch (_: CancellationException) {
            persistSessionChunks(session)
            if (session.pauseRequested.get()) {
                repository.updateStatus(downloadId, DownloadStatus.PAUSED, null)
            }
        } finally {
            runCatching { writer.close() }
            activeSessions.remove(downloadId)
            removeMetricsFor(downloadId)
            _freeStorageBytes.value = diskWriter.getAvailableStorageBytes()
        }
    }

    private fun resolveAdaptersForSession(entity: DownloadEntity): List<NetworkAdapterInfo> {
        val allowed = entity.parsedAllowedNetworks()
        return networkManager.getUsableAdapters(allowed)
    }

    private fun resolveMaxStreams(entity: DownloadEntity, adapter: NetworkAdapterInfo): Int {
        val overrides = entity.parsedStreamOverrides()
        return (overrides[adapter.networkId] ?: adapter.maxStreams).coerceIn(1, 32)
    }

    private fun fallbackDefaultAdapter(): NetworkAdapterInfo = NetworkAdapterInfo(
        networkId = "system_default",
        systemName = "Default Network",
        customName = "Default Network",
        interfaceName = "default",
        transportType = NetworkTransportType.WIFI,
        isMetered = false,
        isEnabled = true,
        isVpn = false,
        isValidated = true,
        hasInternet = true,
        linkDownstreamKbps = 100_000,
        maxStreams = settings.wifiDefaultStreams,
        reachabilityState = ReachabilityState.READY,
        bindingHandle = null
    )

    private fun spawnWorkersForNetwork(
        session: ActiveDownloadSession,
        adapter: NetworkAdapterInfo,
        streamCount: Int
    ) {
        val sessionScope = CoroutineScope(session.supervisorJob + Dispatchers.IO)
        session.telemetry.registerNetwork(
            networkId = adapter.networkId,
            displayName = adapter.displayName,
            transportType = adapter.transportType,
            status = adapter.reachabilityState,
            maxStreams = streamCount
        )

        val jobs = mutableListOf<Job>()
        val client = clientFactory.getClientFor(adapter)

        repeat(streamCount) {
            val workerJob = sessionScope.launch {
                runStreamWorker(session, adapter, client)
            }
            jobs.add(workerJob)
        }
        session.networkWorkerJobs[adapter.networkId] = jobs
    }

    private suspend fun respawnWorkersForNetwork(
        session: ActiveDownloadSession,
        adapter: NetworkAdapterInfo,
        newMaxStreams: Int
    ) {
        val existingJobs = session.networkWorkerJobs.remove(adapter.networkId)
        existingJobs?.forEach { it.cancel() }
        session.scheduler.reclaimChunksForNetwork(adapter.networkId)
        if (session.entity.supportsRanges && adapter.isUsableForDownload) {
            spawnWorkersForNetwork(session, adapter, newMaxStreams)
        }
    }

    private suspend fun runStreamWorker(
        session: ActiveDownloadSession,
        adapter: NetworkAdapterInfo,
        client: OkHttpClient
    ) {
        val buffer = ByteArray(STREAM_BUFFER_SIZE)
        val workerScope = CoroutineScope(session.supervisorJob + Dispatchers.IO)

        while (session.supervisorJob.isActive && !session.pauseRequested.get() && !session.expiredTriggered.get()) {
            when (val acquired = session.scheduler.acquireChunk(adapter.networkId, adapter.transportType)) {
                is AcquireResult.AllDone -> return
                is AcquireResult.NoWorkAvailable -> {
                    if (session.scheduler.isNetworkParked(adapter.networkId)) return
                    delay(300L)
                    continue
                }
                is AcquireResult.WaitBackoff -> {
                    delay(acquired.delayMs.coerceAtMost(2000L))
                    continue
                }
                is AcquireResult.Acquired -> {
                    val chunk = acquired.chunk
                    session.telemetry.incrementStreams(adapter.networkId)
                    try {
                        downloadChunkRange(session, adapter, client, chunk, buffer, workerScope)
                    } catch (_: CancellationException) {
                        session.scheduler.releaseChunkImmediately(chunk)
                        return
                    } catch (sfe: StorageFullException) {
                        session.scheduler.releaseChunkImmediately(chunk)
                        session.fatalError.value = sfe.message ?: "Storage full"
                        return
                    } catch (e: ExpiredLinkException) {
                        session.scheduler.releaseChunkImmediately(chunk)
                        session.expiredTriggered.set(true)
                        return
                    } catch (e: Exception) {
                        val parked = session.scheduler.releaseChunkOnFailure(
                            chunk = chunk,
                            networkId = adapter.networkId,
                            countTowardsNetworkFailures = true
                        )
                        if (parked) {
                            networkManager.markAdapterParked(adapter.networkId)
                            session.telemetry.updateNetworkStatus(adapter.networkId, ReachabilityState.PARKED)
                            return
                        }
                    } finally {
                        session.telemetry.decrementStreams(adapter.networkId)
                    }
                }
            }
        }
    }

    private class ExpiredLinkException(message: String) : IOException(message)

    private suspend fun downloadChunkRange(
        session: ActiveDownloadSession,
        adapter: NetworkAdapterInfo,
        client: OkHttpClient,
        chunk: MutableChunk,
        buffer: ByteArray,
        watchdogScope: CoroutineScope
    ) {
        val startOffset = chunk.currentOffset
        val endOffset = chunk.endByte

        if (session.entity.supportsRanges && endOffset >= chunk.startByte && startOffset > endOffset) {
            session.scheduler.markChunkCompleted(chunk, adapter.networkId)
            return
        }

        val reqBuilder = Request.Builder()
            .url(session.entity.finalUrl)
            .get()
            .header("User-Agent", NetworkBondingManager.USER_AGENT)
            .header("Accept-Encoding", "identity")

        if (session.entity.supportsRanges && endOffset >= startOffset) {
            reqBuilder.header("Range", "bytes=$startOffset-$endOffset")
        }

        val call: Call = client.newCall(reqBuilder.build())
        val lastActivityMs = AtomicLong(System.currentTimeMillis())

        // 15-second stall detector watchdog: cancels stalled TCP stream to force instant reconnect
        val stallWatchdog = watchdogScope.launch {
            while (isActive) {
                delay(1000L)
                if (System.currentTimeMillis() - lastActivityMs.get() >= stallTimeoutMs) {
                    call.cancel()
                    break
                }
            }
        }

        try {
            call.execute().use { response ->
                lastActivityMs.set(System.currentTimeMillis())

                when (response.code) {
                    403, 410 -> throw ExpiredLinkException("HTTP ${response.code} link expired")
                    429, 503 -> throw IOException("Server throttled stream (HTTP ${response.code})")
                    !in listOf(200, 206) -> throw IOException("Unexpected HTTP ${response.code}")
                }

                val body = response.body ?: throw IOException("Empty HTTP response body")
                val inputStream = body.byteStream()
                session.scheduler.recordNetworkSuccess(adapter.networkId)

                var writePos = startOffset
                var chunkBytesSinceSpeedTick = 0L
                var speedTickStartMs = System.currentTimeMillis()

                while (session.supervisorJob.isActive && !session.pauseRequested.get()) {
                    val currentEnd = chunk.endByte
                    val maxAllowed = if (session.entity.supportsRanges && currentEnd >= chunk.startByte) {
                        val rem = (currentEnd - writePos + 1L)
                        if (rem <= 0L) break
                        minOf(buffer.size.toLong(), rem).toInt()
                    } else {
                        buffer.size
                    }

                    val bytesRead = inputStream.read(buffer, 0, maxAllowed)
                    if (bytesRead == -1) break
                    if (bytesRead == 0) continue

                    lastActivityMs.set(System.currentTimeMillis())
                    speedLimiter.acquire(bytesRead)

                    // Re-check dynamic endByte in case a faster worker stole the back half of this chunk
                    val freshEnd = chunk.endByte
                    val bytesToWrite = if (session.entity.supportsRanges && freshEnd >= chunk.startByte) {
                        val rem = (freshEnd - writePos + 1L).coerceAtLeast(0L)
                        minOf(bytesRead.toLong(), rem).toInt()
                    } else {
                        bytesRead
                    }

                    if (bytesToWrite > 0) {
                        session.writer.writeAt(writePos, buffer, 0, bytesToWrite)
                        writePos += bytesToWrite
                        chunk.addDownloaded(bytesToWrite.toLong())
                        session.telemetry.recordBytes(adapter.networkId, bytesToWrite)

                        chunkBytesSinceSpeedTick += bytesToWrite
                        val elapsed = System.currentTimeMillis() - speedTickStartMs
                        if (elapsed >= 500L) {
                            chunk.lastSpeedBytesPerSec = (chunkBytesSinceSpeedTick * 1000L) / elapsed
                            chunkBytesSinceSpeedTick = 0L
                            speedTickStartMs = System.currentTimeMillis()
                        }
                    }

                    if (session.entity.supportsRanges && freshEnd >= chunk.startByte && writePos > freshEnd) {
                        call.cancel()
                        break
                    }
                }

                if (!session.entity.supportsRanges || chunk.isReachedEnd()) {
                    session.scheduler.markChunkCompleted(chunk, adapter.networkId)
                } else if (!session.pauseRequested.get()) {
                    throw IOException("Premature EOF before chunk boundary")
                }
            }
        } finally {
            stallWatchdog.cancel()
        }
    }

    private suspend fun onNetworkHotPlugged(adapter: NetworkAdapterInfo) {
        if (!adapter.isUsableForDownload) return
        activeSessions.values.forEach { session ->
            if (!session.entity.supportsRanges) return@forEach
            val allowed = session.entity.parsedAllowedNetworks()
            if (allowed.isNotEmpty() && !allowed.contains(adapter.networkId)) return@forEach
            if (session.networkWorkerJobs.containsKey(adapter.networkId)) return@forEach

            val reachable = networkManager.probeReachability(session.entity.finalUrl, adapter)
            if (reachable) {
                session.scheduler.unparkNetwork(adapter.networkId)
                val maxStreams = resolveMaxStreams(session.entity, adapter)
                spawnWorkersForNetwork(session, adapter, maxStreams)
            }
        }
    }

    private suspend fun onNetworkUpdated(adapter: NetworkAdapterInfo) {
        if (!adapter.isEnabled) {
            onNetworkHotUnplugged(adapter.networkId, notifyUser = false)
        } else if (adapter.isUsableForDownload) {
            onNetworkHotPlugged(adapter)
        }
    }

    private suspend fun onNetworkHotUnplugged(networkId: String, notifyUser: Boolean = true) {
        var impacted = false
        activeSessions.values.forEach { session ->
            val jobs = session.networkWorkerJobs.remove(networkId)
            if (jobs != null) {
                impacted = true
                jobs.forEach { it.cancel() }
                session.scheduler.reclaimChunksForNetwork(networkId)
                session.telemetry.updateNetworkStatus(networkId, ReachabilityState.DISABLED)
            }
        }
        if (impacted && notifyUser) {
            _alerts.tryEmit(EngineAlertEvent.AdapterDropped(networkId))
        }
    }

    private suspend fun persistSessionChunks(session: ActiveDownloadSession) {
        runCatching {
            val entities: List<ChunkEntity> = session.scheduler.snapshotEntities()
            val downloaded = session.scheduler.totalDownloadedBytes()
            repository.saveChunks(session.downloadId, entities)
            repository.updateProgress(
                id = session.downloadId,
                downloadedBytes = downloaded,
                totalBytes = session.entity.totalBytes
            )
            session.writer.flush()
        }
    }

    private suspend fun publishTelemetryTick() {
        if (activeSessions.isEmpty()) {
            if (_liveMetrics.value.isNotEmpty()) {
                _liveMetrics.value = emptyMap()
            }
            return
        }
        val map = HashMap<Long, DownloadLiveMetrics>(activeSessions.size)
        for ((id, session) in activeSessions) {
            val cells = session.scheduler.buildCellMap()
            val downloaded = session.scheduler.totalDownloadedBytes()
            map[id] = session.telemetry.sampleSnapshot(
                currentDownloadedTotal = downloaded,
                workStealCount = session.scheduler.workStealCount,
                chunkCells = cells
            )
        }
        _liveMetrics.value = map
    }

    private fun removeMetricsFor(downloadId: Long) {
        val current = _liveMetrics.value
        if (current.containsKey(downloadId)) {
            _liveMetrics.value = current - downloadId
        }
    }

    fun hasActiveDownloads(): Boolean = activeSessions.isNotEmpty()

    companion object {
        const val DEFAULT_STALL_TIMEOUT_MS = 15_000L
        const val DEFAULT_PERSIST_INTERVAL_MS = 2_000L
        private const val STREAM_BUFFER_SIZE = 32 * 1024

        fun detectResumeMismatch(saved: DownloadEntity, fresh: ProbeResult.Success): String? {
            if (saved.totalBytes > 0L && fresh.contentLength > 0L && saved.totalBytes != fresh.contentLength) {
                return "file size changed"
            }
            if (!saved.etag.isNullOrBlank() && !fresh.etag.isNullOrBlank() && saved.etag != fresh.etag) {
                return "ETag changed"
            }
            if (!saved.lastModified.isNullOrBlank() && !fresh.lastModified.isNullOrBlank() &&
                saved.lastModified != fresh.lastModified
            ) {
                return "Last-Modified changed"
            }
            return null
        }
    }
}
