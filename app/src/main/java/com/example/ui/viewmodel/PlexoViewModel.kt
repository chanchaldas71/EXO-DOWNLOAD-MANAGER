package com.example.ui.viewmodel

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.PlexoApplication
import com.example.data.local.entity.ChunkEntity
import com.example.data.local.entity.DownloadEntity
import com.example.data.model.DownloadStatus
import com.example.data.model.HashAlgorithm
import com.example.data.model.ThemeMode
import com.example.data.preferences.PlexoSettings
import com.example.engine.download.DiskWriter
import com.example.engine.download.DownloadLiveMetrics
import com.example.engine.download.EngineAlertEvent
import com.example.engine.download.NewDownloadRequest
import com.example.engine.download.ResumeMismatchPrompt
import com.example.engine.network.NetworkAdapterInfo
import com.example.engine.network.ProbeResult
import com.example.ui.util.Formatters
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

sealed interface UrlProbeUiState {
    data object Idle : UrlProbeUiState
    data object Probing : UrlProbeUiState
    data class Success(val result: ProbeResult.Success) : UrlProbeUiState
    data class Error(val message: String) : UrlProbeUiState
}

class PlexoViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as PlexoApplication
    private val repository = app.repository
    private val settingsRepository = app.settingsRepository
    private val networkManager = app.networkBondingManager
    private val engine = app.downloadEngine

    val downloads: StateFlow<List<DownloadEntity>> = repository.allDownloads
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val adapters: StateFlow<List<NetworkAdapterInfo>> = networkManager.adapters
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val settings: StateFlow<PlexoSettings> = settingsRepository.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlexoSettings())

    val liveMetrics: StateFlow<Map<Long, DownloadLiveMetrics>> = engine.liveMetrics
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val freeStorageBytes: StateFlow<Long> = engine.freeStorageBytes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), engine.diskWriter.getAvailableStorageBytes())

    private val _selectedDownloadIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedDownloadIds: StateFlow<Set<Long>> = _selectedDownloadIds.asStateFlow()

    private val _probeUiState = MutableStateFlow<UrlProbeUiState>(UrlProbeUiState.Idle)
    val probeUiState: StateFlow<UrlProbeUiState> = _probeUiState.asStateFlow()

    private val _clipboardSuggestion = MutableStateFlow<String?>(null)
    val clipboardSuggestion: StateFlow<String?> = _clipboardSuggestion.asStateFlow()

    private val _incomingUrl = MutableStateFlow<String?>(null)
    val incomingUrl: StateFlow<String?> = _incomingUrl.asStateFlow()

    private val _resumeMismatchPrompt = MutableStateFlow<ResumeMismatchPrompt?>(null)
    val resumeMismatchPrompt: StateFlow<ResumeMismatchPrompt?> = _resumeMismatchPrompt.asStateFlow()

    private val _expiredLinkTarget = MutableStateFlow<DownloadEntity?>(null)
    val expiredLinkTarget: StateFlow<DownloadEntity?> = _expiredLinkTarget.asStateFlow()

    private val _snackbarMessages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val snackbarMessages: SharedFlow<String> = _snackbarMessages.asSharedFlow()

    private var lastSuggestedClipboardUrl: String? = null

    init {
        viewModelScope.launch {
            engine.alerts.collect { event ->
                when (event) {
                    is EngineAlertEvent.Completed ->
                        _snackbarMessages.tryEmit("Completed: ${event.fileName}")
                    is EngineAlertEvent.Failed ->
                        _snackbarMessages.tryEmit("${event.fileName}: ${event.reason}")
                    is EngineAlertEvent.ExpiredLink -> {
                        val entity = repository.getDownload(event.downloadId)
                        if (entity != null) _expiredLinkTarget.value = entity
                        _snackbarMessages.tryEmit("Link expired for ${event.fileName} — paste a refreshed URL")
                    }
                    is EngineAlertEvent.AdapterDropped ->
                        _snackbarMessages.tryEmit("Network dropped (${event.networkName}) — chunks reassigned")
                    is EngineAlertEvent.ResumeMismatch ->
                        _resumeMismatchPrompt.value = event.prompt
                }
            }
        }
    }

    fun observeDownload(downloadId: Long): Flow<DownloadEntity?> = repository.observeDownload(downloadId)

    fun observeChunks(downloadId: Long): Flow<List<ChunkEntity>> = repository.observeChunks(downloadId)

    fun checkClipboardForUrl() {
        val currentSettings = settings.value
        if (!currentSettings.clipboardWatcherEnabled) return
        val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val clip = cm.primaryClip ?: return
        if (clip.itemCount == 0) return
        val raw = clip.getItemAt(0)?.coerceToText(app)?.toString()
        val url = Formatters.extractFirstHttpUrl(raw)
        if (url != null && url != lastSuggestedClipboardUrl) {
            lastSuggestedClipboardUrl = url
            _clipboardSuggestion.value = url
        }
    }

    fun dismissClipboardSuggestion() {
        _clipboardSuggestion.value = null
    }

    fun handleIncomingSharedUrl(url: String) {
        val extracted = Formatters.extractFirstHttpUrl(url) ?: return
        _incomingUrl.value = extracted
        probeUrl(extracted)
    }

    fun consumeIncomingUrl() {
        _incomingUrl.value = null
    }

    fun probeUrl(url: String) {
        val trimmed = url.trim()
        if (trimmed.isBlank()) {
            _probeUiState.value = UrlProbeUiState.Idle
            return
        }
        viewModelScope.launch {
            _probeUiState.value = UrlProbeUiState.Probing
            when (val res = engine.probeService.probe(trimmed)) {
                is ProbeResult.Success -> _probeUiState.value = UrlProbeUiState.Success(res)
                is ProbeResult.Failure -> _probeUiState.value = UrlProbeUiState.Error(res.userMessage)
            }
        }
    }

    fun resetProbe() {
        _probeUiState.value = UrlProbeUiState.Idle
    }

    fun addDownload(
        url: String,
        fileName: String,
        saveUri: String,
        allowedNetworkIds: Set<String>,
        streamOverrides: Map<String, Int>,
        expectedHash: String,
        hashAlgorithm: HashAlgorithm,
        startImmediately: Boolean
    ) {
        viewModelScope.launch {
            val probeSuccess = (_probeUiState.value as? UrlProbeUiState.Success)?.result
            val id = engine.enqueueDownload(
                NewDownloadRequest(
                    url = url.trim(),
                    fileName = fileName.trim(),
                    saveUri = saveUri,
                    probe = probeSuccess,
                    allowedNetworkIds = allowedNetworkIds,
                    streamOverrides = streamOverrides,
                    expectedHash = expectedHash,
                    hashAlgorithm = hashAlgorithm,
                    startImmediately = startImmediately
                )
            )
            _probeUiState.value = UrlProbeUiState.Idle
            val msg = if (startImmediately) "Download started (#$id)" else "Added to queue (#$id)"
            _snackbarMessages.tryEmit(msg)
        }
    }

    fun pauseDownload(id: Long) = viewModelScope.launch { engine.pauseDownload(id) }

    fun resumeDownload(id: Long) = viewModelScope.launch { engine.resumeDownload(id) }

    fun pauseAll() = viewModelScope.launch { engine.pauseAll() }

    fun resumeAll() = viewModelScope.launch { engine.resumeAll() }

    fun deleteDownload(id: Long, deleteFile: Boolean = false) = viewModelScope.launch {
        engine.cancelAndDelete(id, deleteFile)
        _selectedDownloadIds.value = _selectedDownloadIds.value - id
    }

    fun toggleSelection(id: Long) {
        val current = _selectedDownloadIds.value
        _selectedDownloadIds.value = if (current.contains(id)) current - id else current + id
    }

    fun clearSelection() {
        _selectedDownloadIds.value = emptySet()
    }

    fun pauseSelected() = viewModelScope.launch {
        val ids = _selectedDownloadIds.value.toList()
        ids.forEach { engine.pauseDownload(it) }
        clearSelection()
    }

    fun resumeSelected() = viewModelScope.launch {
        val ids = _selectedDownloadIds.value.toList()
        ids.forEach { engine.resumeDownload(it) }
        clearSelection()
    }

    fun deleteSelected(deleteFiles: Boolean = false) = viewModelScope.launch {
        val ids = _selectedDownloadIds.value.toList()
        ids.forEach { engine.cancelAndDelete(it, deleteFiles) }
        clearSelection()
    }

    fun moveQueuedItem(downloadId: Long, moveUp: Boolean) = viewModelScope.launch {
        val queued = downloads.value
            .filter { it.status == DownloadStatus.QUEUED }
            .sortedBy { it.queueOrder }
            .toMutableList()
        val idx = queued.indexOfFirst { it.id == downloadId }
        if (idx < 0) return@launch
        val target = if (moveUp) idx - 1 else idx + 1
        if (target !in queued.indices) return@launch
        val item = queued.removeAt(idx)
        queued.add(target, item)
        repository.reorderQueue(queued.map { it.id })
    }

    fun toggleNetworkAdapter(networkId: String, enabled: Boolean) = viewModelScope.launch {
        networkManager.toggleAdapter(networkId, enabled)
    }

    fun renameNetworkAdapter(networkId: String, newName: String) = viewModelScope.launch {
        networkManager.renameAdapter(networkId, newName)
    }

    fun setNetworkMaxStreams(networkId: String, streams: Int) = viewModelScope.launch {
        networkManager.setAdapterMaxStreams(networkId, streams)
    }

    fun updateDownloadStreamsForNetwork(downloadId: Long, networkId: String, streams: Int) = viewModelScope.launch {
        engine.updateDownloadStreamLimit(downloadId, networkId, streams)
    }

    fun confirmForceRestartFromMismatch() {
        val prompt = _resumeMismatchPrompt.value ?: return
        _resumeMismatchPrompt.value = null
        viewModelScope.launch {
            engine.forceRestartDownload(prompt.downloadId)
        }
    }

    fun dismissResumeMismatch() {
        _resumeMismatchPrompt.value = null
    }

    fun openExpiredLinkDialog(entity: DownloadEntity) {
        _expiredLinkTarget.value = entity
    }

    fun dismissExpiredLinkDialog() {
        _expiredLinkTarget.value = null
    }

    fun submitRefreshedUrl(downloadId: Long, newUrl: String) = viewModelScope.launch {
        val res = engine.refreshUrlAndResume(downloadId, newUrl)
        if (res is ProbeResult.Success) {
            _expiredLinkTarget.value = null
            _snackbarMessages.tryEmit("Link refreshed — resuming without losing downloaded chunks")
        } else if (res is ProbeResult.Failure) {
            _snackbarMessages.tryEmit("Refresh failed: ${res.userMessage}")
        }
    }

    fun openCompletedFile(context: Context, entity: DownloadEntity) {
        try {
            val uri: Uri = when {
                entity.saveUri.startsWith("content://") -> Uri.parse(entity.saveUri)
                entity.filePath.isNotBlank() && File(entity.filePath).exists() -> {
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        File(entity.filePath)
                    )
                }
                else -> {
                    _snackbarMessages.tryEmit("File not found on storage")
                    return
                }
            }
            val mime = DiskWriter.mimeTypeFor(entity.fileName)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Open ${entity.fileName}"))
        } catch (e: Exception) {
            Toast.makeText(context, "No app available to open this file", Toast.LENGTH_SHORT).show()
        }
    }

    // Settings updates
    fun toggleSlowMode() = viewModelScope.launch {
        settingsRepository.update { it.copy(slowModeEnabled = !it.slowModeEnabled) }
    }

    fun toggleSpeedUnit() = viewModelScope.launch {
        settingsRepository.update { it.copy(speedUnitMbps = !it.speedUnitMbps) }
    }

    fun updateDefaultFolder(uri: String, label: String) = viewModelScope.launch {
        settingsRepository.update { it.copy(defaultFolderUri = uri, defaultFolderLabel = label) }
    }

    fun updateMaxSimultaneous(value: Int) = viewModelScope.launch {
        settingsRepository.update { it.copy(maxSimultaneousDownloads = value.coerceIn(1, 6)) }
    }

    fun updateDefaultChunkSize(bytes: Long) = viewModelScope.launch {
        settingsRepository.update { it.copy(defaultChunkSizeBytes = bytes) }
    }

    fun updateDefaultStreams(ethernet: Int? = null, wifi: Int? = null, cellular: Int? = null) = viewModelScope.launch {
        settingsRepository.update { s ->
            s.copy(
                ethernetDefaultStreams = ethernet ?: s.ethernetDefaultStreams,
                wifiDefaultStreams = wifi ?: s.wifiDefaultStreams,
                cellularDefaultStreams = cellular ?: s.cellularDefaultStreams
            )
        }
    }

    fun updateGlobalSpeedLimit(bytesPerSec: Long) = viewModelScope.launch {
        settingsRepository.update { it.copy(globalSpeedLimitBytesPerSec = bytesPerSec.coerceAtLeast(0L)) }
    }

    fun updateSlowModeLimit(bytesPerSec: Long) = viewModelScope.launch {
        settingsRepository.update { it.copy(slowModeLimitBytesPerSec = bytesPerSec.coerceAtLeast(64 * 1024L)) }
    }

    fun updateWifiOnlyGuardrail(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.update { it.copy(wifiOnlyGuardrail = enabled) }
    }

    fun updateAllowVpnBonding(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.update { it.copy(allowVpnBonding = enabled) }
    }

    fun updateAutoSort(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.update { it.copy(autoSortByType = enabled) }
    }

    fun updateClipboardWatcher(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.update { it.copy(clipboardWatcherEnabled = enabled) }
    }

    fun updateScheduler(enabled: Boolean, startHour: Int, startMin: Int, endHour: Int, endMin: Int) =
        viewModelScope.launch {
            settingsRepository.update {
                it.copy(
                    schedulerEnabled = enabled,
                    scheduleStartHour = startHour.coerceIn(0, 23),
                    scheduleStartMinute = startMin.coerceIn(0, 59),
                    scheduleEndHour = endHour.coerceIn(0, 23),
                    scheduleEndMinute = endMin.coerceIn(0, 59)
                )
            }
        }

    fun updateThemeMode(mode: ThemeMode) = viewModelScope.launch {
        settingsRepository.update { it.copy(themeMode = mode) }
    }

    fun updateDynamicColor(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.update { it.copy(dynamicColor = enabled) }
    }
}
