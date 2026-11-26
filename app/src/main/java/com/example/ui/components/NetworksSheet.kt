package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.NetworkTransportType
import com.example.data.model.ReachabilityState
import com.example.engine.network.NetworkAdapterInfo
import com.example.ui.theme.LocalPlexoIsDark
import com.example.ui.theme.PlexoNetworkColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworksManagerSheet(
    adapters: List<NetworkAdapterInfo>,
    onToggleAdapter: (networkId: String, enabled: Boolean) -> Unit,
    onRenameAdapter: (networkId: String, newName: String) -> Unit,
    onSetStreams: (networkId: String, streams: Int) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var renameTarget by remember { mutableStateOf<NetworkAdapterInfo?>(null) }
    var renameInput by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Text(
                text = "Network Bonding Adapters",
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = "Enable multiple interfaces to bond Wi-Fi, 5G/4G Cellular, and USB Ethernet simultaneously.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 14.dp)
            )

            val hasMeteredDisabled = adapters.any { it.isMetered && !it.isEnabled }
            if (hasMeteredDisabled) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.65f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = stringResource(R.string.metered_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }

            if (adapters.isEmpty()) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp)
                ) {
                    Text(
                        text = "No validated internet interfaces detected yet. Connect Wi-Fi, Mobile Data, or USB Ethernet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(adapters, key = { it.networkId }) { adapter ->
                        AdapterCardRow(
                            adapter = adapter,
                            onToggle = { enabled -> onToggleAdapter(adapter.networkId, enabled) },
                            onRenameClick = {
                                renameTarget = adapter
                                renameInput = adapter.displayName
                            },
                            onDecrementStreams = {
                                onSetStreams(adapter.networkId, (adapter.maxStreams - 1).coerceAtLeast(1))
                            },
                            onIncrementStreams = {
                                onSetStreams(adapter.networkId, (adapter.maxStreams + 1).coerceAtMost(32))
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename Network Adapter") },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    singleLine = true,
                    label = { Text("Display name") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("rename_network_input")
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRenameAdapter(target.networkId, renameInput)
                        renameTarget = null
                    },
                    modifier = Modifier.testTag("confirm_rename_network")
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun AdapterCardRow(
    adapter: NetworkAdapterInfo,
    onToggle: (Boolean) -> Unit,
    onRenameClick: () -> Unit,
    onDecrementStreams: () -> Unit,
    onIncrementStreams: () -> Unit
) {
    val isDark = LocalPlexoIsDark.current
    val accent = PlexoNetworkColors.colorFor(adapter.transportType, isDark)

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .background(accent.copy(alpha = 0.16f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = transportIcon(adapter.transportType),
                        contentDescription = adapter.transportType.displayName,
                        tint = accent,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = adapter.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        IconButton(
                            onClick = onRenameClick,
                            modifier = Modifier
                                .size(32.dp)
                                .testTag("rename_adapter_${adapter.networkId}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = stringResource(R.string.cd_rename_network),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val statusColor = when (adapter.reachabilityState) {
                            ReachabilityState.READY -> MaterialTheme.colorScheme.secondary
                            ReachabilityState.UNREACHABLE, ReachabilityState.PARKED -> MaterialTheme.colorScheme.error
                            ReachabilityState.METERED_OFF -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Text(
                            text = adapter.reachabilityMessage ?: adapter.reachabilityState.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor
                        )
                        if (adapter.isMetered) {
                            Surface(
                                shape = MaterialTheme.shapes.extraSmall,
                                color = MaterialTheme.colorScheme.tertiaryContainer
                            ) {
                                Text(
                                    text = "METERED",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                }

                Switch(
                    checked = adapter.isEnabled,
                    onCheckedChange = onToggle,
                    modifier = Modifier.testTag("toggle_adapter_${adapter.networkId}")
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Interface: ${adapter.interfaceName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Max streams:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    IconButton(onClick = onDecrementStreams, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Remove, contentDescription = "Decrease streams", modifier = Modifier.size(16.dp))
                    }
                    Text(
                        text = "${adapter.maxStreams}",
                        style = MaterialTheme.typography.labelMedium,
                        color = accent
                    )
                    IconButton(onClick = onIncrementStreams, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Add, contentDescription = "Increase streams", modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

fun transportIcon(type: NetworkTransportType): ImageVector = when (type) {
    NetworkTransportType.WIFI -> Icons.Default.Wifi
    NetworkTransportType.CELLULAR -> Icons.Default.CellTower
    NetworkTransportType.ETHERNET -> Icons.Default.SettingsEthernet
    NetworkTransportType.VPN -> Icons.Default.VpnKey
    NetworkTransportType.OTHER -> Icons.Default.SettingsEthernet
}
