package com.example.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.HashAlgorithm
import com.example.data.preferences.PlexoSettings
import com.example.engine.network.NetworkAdapterInfo
import com.example.ui.theme.LocalPlexoIsDark
import com.example.ui.theme.PlexoNetworkColors
import com.example.ui.util.Formatters
import com.example.ui.viewmodel.UrlProbeUiState
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewDownloadSheet(
    initialUrl: String,
    adapters: List<NetworkAdapterInfo>,
    settings: PlexoSettings,
    probeState: UrlProbeUiState,
    onProbeUrl: (String) -> Unit,
    onSubmit: (
        url: String,
        fileName: String,
        saveUri: String,
        allowedNetworkIds: Set<String>,
        streamOverrides: Map<String, Int>,
        expectedHash: String,
        hashAlgorithm: HashAlgorithm,
        startNow: Boolean
    ) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val isDark = LocalPlexoIsDark.current

    var url by remember { mutableStateOf(initialUrl) }
    var fileName by remember { mutableStateOf("") }
    var saveUri by remember { mutableStateOf(settings.defaultFolderUri) }
    var saveFolderLabel by remember { mutableStateOf(settings.defaultFolderLabel) }
    var hashAlgorithm by remember { mutableStateOf(HashAlgorithm.NONE) }
    var expectedHash by remember { mutableStateOf("") }

    val selectedNetworks = remember {
        mutableStateMapOf<String, Boolean>().apply {
            adapters.forEach { put(it.networkId, it.isEnabled) }
        }
    }
    val streamLimits = remember {
        mutableStateMapOf<String, Int>().apply {
            adapters.forEach { put(it.networkId, it.maxStreams) }
        }
    }

    LaunchedEffect(adapters) {
        adapters.forEach { adapter ->
            if (!selectedNetworks.containsKey(adapter.networkId)) {
                selectedNetworks[adapter.networkId] = adapter.isEnabled
            }
            if (!streamLimits.containsKey(adapter.networkId)) {
                streamLimits[adapter.networkId] = adapter.maxStreams
            }
        }
    }

    LaunchedEffect(initialUrl) {
        if (initialUrl.isNotBlank()) {
            url = initialUrl
            onProbeUrl(initialUrl)
        }
    }

    LaunchedEffect(probeState) {
        if (probeState is UrlProbeUiState.Success) {
            if (fileName.isBlank()) {
                fileName = probeState.result.fileName
            }
        }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            saveUri = uri.toString()
            saveFolderLabel = uri.lastPathSegment?.substringAfterLast(':') ?: "Custom Folder"
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "New Bonded Download",
                style = MaterialTheme.typography.headlineSmall
            )

            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Download URL") },
                placeholder = { Text("https://example.com/archive.zip") },
                leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) },
                trailingIcon = {
                    IconButton(
                        onClick = { onProbeUrl(url) },
                        modifier = Modifier.testTag("probe_url_button")
                    ) {
                        if (probeState is UrlProbeUiState.Probing) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Search, contentDescription = "Probe URL")
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("new_download_url_input")
            )

            // Probe feedback pill
            when (probeState) {
                is UrlProbeUiState.Success -> {
                    val r = probeState.result
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(18.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Size: ${Formatters.formatBytes(r.contentLength)} · " +
                                        if (r.supportsRanges) "Split bonding ready" else "Single-stream only",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                if (!r.etag.isNullOrBlank()) {
                                    Text(
                                        text = "ETag verified · Smart resume supported",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
                is UrlProbeUiState.Error -> {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = probeState.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
                else -> Unit
            }

            OutlinedTextField(
                value = fileName,
                onValueChange = { fileName = it },
                label = { Text("File Name (optional — auto-detected)") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("new_download_filename_input")
            )

            // Save folder row
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.Default.FolderOpen,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Save Location",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = saveFolderLabel,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = { folderPickerLauncher.launch(null) },
                        modifier = Modifier.testTag("choose_folder_button")
                    ) {
                        Text("Change")
                    }
                }
            }

            // Active network checkboxes + per-network stream sliders
            Text(
                text = "Bonded Networks & Stream Allocation",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (adapters.isEmpty()) {
                Text(
                    text = "Will use system default route (connect Wi-Fi + 5G/Ethernet to bond).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                adapters.forEach { adapter ->
                    val checked = selectedNetworks[adapter.networkId] ?: adapter.isEnabled
                    val streams = streamLimits[adapter.networkId] ?: adapter.maxStreams
                    val accent = PlexoNetworkColors.colorFor(adapter.transportType, isDark)

                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { selectedNetworks[adapter.networkId] = it }
                                )
                                Icon(
                                    imageVector = transportIcon(adapter.transportType),
                                    contentDescription = null,
                                    tint = accent,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = adapter.displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    if (adapter.isMetered) {
                                        Text(
                                            text = "Metered data connection",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.tertiary
                                        )
                                    }
                                }
                                Text(
                                    text = "$streams streams",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = accent
                                )
                            }
                            if (checked) {
                                Slider(
                                    value = streams.toFloat(),
                                    onValueChange = { streamLimits[adapter.networkId] = it.roundToInt().coerceIn(1, 32) },
                                    valueRange = 1f..32f,
                                    steps = 30,
                                    modifier = Modifier.padding(horizontal = 10.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Optional checksum verification
            Text(
                text = "Checksum Verification (Optional)",
                style = MaterialTheme.typography.titleSmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HashAlgorithm.entries.forEach { algo ->
                    FilterChip(
                        selected = hashAlgorithm == algo,
                        onClick = { hashAlgorithm = algo },
                        label = { Text(algo.label) }
                    )
                }
            }
            if (hashAlgorithm != HashAlgorithm.NONE) {
                OutlinedTextField(
                    value = expectedHash,
                    onValueChange = { expectedHash = it },
                    label = { Text("Expected ${hashAlgorithm.label} hex digest") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            val isValidUrl = url.trim().startsWith("http://", ignoreCase = true) ||
                url.trim().startsWith("https://", ignoreCase = true)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val allowed = selectedNetworks.filterValues { it }.keys
                        onSubmit(
                            url.trim(),
                            fileName.trim(),
                            saveUri,
                            allowed,
                            streamLimits.toMap(),
                            expectedHash,
                            hashAlgorithm,
                            false
                        )
                    },
                    enabled = isValidUrl,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("add_to_queue_button")
                ) {
                    Icon(Icons.Default.PlaylistAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Queue")
                }

                Button(
                    onClick = {
                        val allowed = selectedNetworks.filterValues { it }.keys
                        onSubmit(
                            url.trim(),
                            fileName.trim(),
                            saveUri,
                            allowed,
                            streamLimits.toMap(),
                            expectedHash,
                            hashAlgorithm,
                            true
                        )
                    },
                    enabled = isValidUrl,
                    modifier = Modifier
                        .weight(1.3f)
                        .height(48.dp)
                        .testTag("start_now_button")
                ) {
                    Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Start Now")
                }
            }
        }
    }
}
