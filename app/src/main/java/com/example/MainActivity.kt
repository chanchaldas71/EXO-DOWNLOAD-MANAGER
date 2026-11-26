package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.ui.components.NetworksManagerSheet
import com.example.ui.components.NewDownloadSheet
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.DownloadDetailScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.PlexoTheme
import com.example.ui.viewmodel.PlexoViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: PlexoViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIncomingIntent(intent)

        setContent {
            PlexoAppRoot(viewModel = viewModel)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        window.decorView.post {
            viewModel.checkClipboardForUrl()
        }
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                if (intent.type == "text/plain") {
                    intent.getStringExtra(Intent.EXTRA_TEXT)?.let { shared ->
                        viewModel.handleIncomingSharedUrl(shared)
                    }
                }
            }
            Intent.ACTION_VIEW -> {
                intent.dataString?.let { uriString ->
                    viewModel.handleIncomingSharedUrl(uriString)
                }
            }
        }
    }
}

private const val ROUTE_DASHBOARD = "dashboard"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_DETAIL = "detail/{downloadId}"

@Composable
private fun PlexoAppRoot(viewModel: PlexoViewModel) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }

    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val adapters by viewModel.adapters.collectAsStateWithLifecycle()
    val liveMetrics by viewModel.liveMetrics.collectAsStateWithLifecycle()
    val freeStorageBytes by viewModel.freeStorageBytes.collectAsStateWithLifecycle()
    val selectedIds by viewModel.selectedDownloadIds.collectAsStateWithLifecycle()
    val clipboardSuggestion by viewModel.clipboardSuggestion.collectAsStateWithLifecycle()
    val incomingUrl by viewModel.incomingUrl.collectAsStateWithLifecycle()
    val probeState by viewModel.probeUiState.collectAsStateWithLifecycle()
    val resumeMismatchPrompt by viewModel.resumeMismatchPrompt.collectAsStateWithLifecycle()
    val expiredLinkTarget by viewModel.expiredLinkTarget.collectAsStateWithLifecycle()

    var showNetworksSheet by remember { mutableStateOf(false) }
    var showNewDownloadSheet by remember { mutableStateOf(false) }
    var newDownloadInitialUrl by remember { mutableStateOf("") }

    // Request POST_NOTIFICATIONS on Android 13+ gracefully
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* Ongoing service continues regardless */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    LaunchedEffect(incomingUrl) {
        val url = incomingUrl
        if (!url.isNullOrBlank()) {
            newDownloadInitialUrl = url
            showNewDownloadSheet = true
            viewModel.consumeIncomingUrl()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.snackbarMessages.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    PlexoTheme(
        themeMode = settings.themeMode,
        dynamicColor = settings.dynamicColor
    ) {
        NavHost(
            navController = navController,
            startDestination = ROUTE_DASHBOARD
        ) {
            composable(ROUTE_DASHBOARD) {
                DashboardScreen(
                    downloads = downloads,
                    adapters = adapters,
                    liveMetrics = liveMetrics,
                    settings = settings,
                    freeStorageBytes = freeStorageBytes,
                    selectedIds = selectedIds,
                    clipboardSuggestion = clipboardSuggestion,
                    resumeMismatchPrompt = resumeMismatchPrompt,
                    expiredLinkTarget = expiredLinkTarget,
                    snackbarHostState = snackbarHostState,
                    onOpenNetworks = { showNetworksSheet = true },
                    onOpenSettings = { navController.navigate(ROUTE_SETTINGS) },
                    onOpenNewDownload = { prefill ->
                        newDownloadInitialUrl = prefill
                        viewModel.resetProbe()
                        showNewDownloadSheet = true
                    },
                    onDismissClipboard = { viewModel.dismissClipboardSuggestion() },
                    onOpenDetail = { id -> navController.navigate("detail/$id") },
                    onToggleSelect = { viewModel.toggleSelection(it) },
                    onClearSelection = { viewModel.clearSelection() },
                    onPauseDownload = { viewModel.pauseDownload(it) },
                    onResumeDownload = { viewModel.resumeDownload(it) },
                    onDeleteDownload = { viewModel.deleteDownload(it) },
                    onPauseSelected = { viewModel.pauseSelected() },
                    onResumeSelected = { viewModel.resumeSelected() },
                    onDeleteSelected = { viewModel.deleteSelected() },
                    onMoveQueued = { id, up -> viewModel.moveQueuedItem(id, up) },
                    onOpenExpiredLinkDialog = { viewModel.openExpiredLinkDialog(it) },
                    onDismissExpiredLinkDialog = { viewModel.dismissExpiredLinkDialog() },
                    onSubmitRefreshedUrl = { id, url -> viewModel.submitRefreshedUrl(id, url) },
                    onConfirmResumeMismatch = { viewModel.confirmForceRestartFromMismatch() },
                    onDismissResumeMismatch = { viewModel.dismissResumeMismatch() },
                    onOpenCompletedFile = { viewModel.openCompletedFile(context, it) },
                    onToggleSlowMode = { viewModel.toggleSlowMode() },
                    onToggleSpeedUnit = { viewModel.toggleSpeedUnit() }
                )
            }

            composable(
                route = ROUTE_DETAIL,
                arguments = listOf(navArgument("downloadId") { type = NavType.LongType })
            ) { backStackEntry ->
                val downloadId = backStackEntry.arguments?.getLong("downloadId") ?: -1L
                val download by remember(downloadId) { viewModel.observeDownload(downloadId) }
                    .collectAsStateWithLifecycle(initialValue = downloads.firstOrNull { it.id == downloadId })
                val chunks by remember(downloadId) { viewModel.observeChunks(downloadId) }
                    .collectAsStateWithLifecycle(initialValue = emptyList())

                DownloadDetailScreen(
                    download = download,
                    chunks = chunks,
                    liveMetrics = liveMetrics[downloadId],
                    useMbps = settings.speedUnitMbps,
                    onBack = { navController.popBackStack() },
                    onPause = { viewModel.pauseDownload(downloadId) },
                    onResume = { viewModel.resumeDownload(downloadId) },
                    onDelete = {
                        viewModel.deleteDownload(downloadId)
                        navController.popBackStack()
                    },
                    onOpenFile = { download?.let { viewModel.openCompletedFile(context, it) } },
                    onRefreshExpiredLink = {
                        download?.let {
                            viewModel.openExpiredLinkDialog(it)
                            navController.popBackStack()
                        }
                    },
                    onAdjustNetworkStreams = { netId, streams ->
                        viewModel.updateDownloadStreamsForNetwork(downloadId, netId, streams)
                    }
                )
            }

            composable(ROUTE_SETTINGS) {
                SettingsScreen(
                    settings = settings,
                    onBack = { navController.popBackStack() },
                    onUpdateFolder = { uri, label -> viewModel.updateDefaultFolder(uri, label) },
                    onUpdateMaxSimultaneous = { viewModel.updateMaxSimultaneous(it) },
                    onUpdateChunkSize = { viewModel.updateDefaultChunkSize(it) },
                    onUpdateDefaultStreams = { eth, wifi, cell -> viewModel.updateDefaultStreams(eth, wifi, cell) },
                    onUpdateGlobalSpeedLimit = { viewModel.updateGlobalSpeedLimit(it) },
                    onUpdateSlowModeLimit = { viewModel.updateSlowModeLimit(it) },
                    onUpdateWifiOnlyGuardrail = { viewModel.updateWifiOnlyGuardrail(it) },
                    onUpdateAllowVpn = { viewModel.updateAllowVpnBonding(it) },
                    onUpdateAutoSort = { viewModel.updateAutoSort(it) },
                    onUpdateClipboardWatcher = { viewModel.updateClipboardWatcher(it) },
                    onUpdateScheduler = { en, sh, sm, eh, em -> viewModel.updateScheduler(en, sh, sm, eh, em) },
                    onUpdateThemeMode = { viewModel.updateThemeMode(it) },
                    onUpdateDynamicColor = { viewModel.updateDynamicColor(it) }
                )
            }
        }

        if (showNetworksSheet) {
            NetworksManagerSheet(
                adapters = adapters,
                onToggleAdapter = { id, enabled -> viewModel.toggleNetworkAdapter(id, enabled) },
                onRenameAdapter = { id, name -> viewModel.renameNetworkAdapter(id, name) },
                onSetStreams = { id, streams -> viewModel.setNetworkMaxStreams(id, streams) },
                onDismiss = { showNetworksSheet = false }
            )
        }

        if (showNewDownloadSheet) {
            NewDownloadSheet(
                initialUrl = newDownloadInitialUrl,
                adapters = adapters,
                settings = settings,
                probeState = probeState,
                onProbeUrl = { viewModel.probeUrl(it) },
                onSubmit = { url, name, saveUri, allowed, overrides, hash, algo, startNow ->
                    viewModel.addDownload(
                        url = url,
                        fileName = name,
                        saveUri = saveUri,
                        allowedNetworkIds = allowed,
                        streamOverrides = overrides,
                        expectedHash = hash,
                        hashAlgorithm = algo,
                        startImmediately = startNow
                    )
                    showNewDownloadSheet = false
                },
                onDismiss = { showNewDownloadSheet = false }
            )
        }
    }
}
