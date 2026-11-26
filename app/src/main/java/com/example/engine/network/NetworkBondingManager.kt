package com.example.engine.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.example.data.local.entity.NetworkConfigEntity
import com.example.data.model.NetworkTransportType
import com.example.data.model.ReachabilityState
import com.example.data.preferences.PlexoSettings
import com.example.data.preferences.SettingsRepository
import com.example.data.repository.DownloadRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.net.Inet6Address
import java.util.concurrent.ConcurrentHashMap

/**
 * Snapshot of a discovered OS network interface decoupled from raw Android framework classes
 * so filtering, deduplication, and multi-network lease orchestration are cleanly testable.
 */
data class DiscoveredNetworkSnapshot(
    val networkHandle: Long,
    val transportType: NetworkTransportType,
    val interfaceName: String,
    val hasInternet: Boolean,
    val isValidated: Boolean,
    val isMetered: Boolean,
    val isVpn: Boolean,
    val isBluetooth: Boolean,
    val hasDefaultRoute: Boolean,
    val supportsIpv4: Boolean,
    val supportsIpv6: Boolean,
    val linkDownstreamKbps: Int,
    val bindingHandle: NetworkBindingHandle
)

interface BondingNetworkListener {
    fun onNetworkUpdated(snapshot: DiscoveredNetworkSnapshot)
    fun onNetworkLost(networkHandle: Long)
    fun onTransportUnavailable(transportType: NetworkTransportType)
}

/**
 * Platform backend that wraps Android's [ConnectivityManager] `requestNetwork()` and
 * `registerNetworkCallback()` APIs.
 */
interface ConnectivityBackend {
    fun snapshotExistingNetworks(): List<DiscoveredNetworkSnapshot>
    fun registerPassiveMonitor(listener: BondingNetworkListener): AutoCloseable?
    fun requestTransportNetwork(
        transportType: NetworkTransportType,
        listener: BondingNetworkListener
    ): AutoCloseable?
}

/**
 * Real Android [ConnectivityManager] implementation that issues dedicated `requestNetwork()`
 * calls per physical transport (`TRANSPORT_WIFI`, `TRANSPORT_CELLULAR`, `TRANSPORT_ETHERNET`)
 * so Android keeps multiple radios/interfaces active concurrently instead of tearing down
 * Cellular when Wi-Fi connects.
 */
class AndroidConnectivityBackend(
    private val connectivityManager: ConnectivityManager
) : ConnectivityBackend {

    override fun snapshotExistingNetworks(): List<DiscoveredNetworkSnapshot> {
        return try {
            @Suppress("DEPRECATION")
            connectivityManager.allNetworks.mapNotNull { network ->
                buildSnapshot(network, null, null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to enumerate existing networks", e)
            emptyList()
        }
    }

    override fun registerPassiveMonitor(listener: BondingNetworkListener): AutoCloseable? {
        // Remove NET_CAPABILITY_NOT_VPN so VPN tunnels are also observed and controlled by user setting
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        return registerCallback(
            request = request,
            transportHint = null,
            useRequestNetwork = false,
            listener = listener
        )
    }

    override fun requestTransportNetwork(
        transportType: NetworkTransportType,
        listener: BondingNetworkListener
    ): AutoCloseable? {
        val androidTransport = when (transportType) {
            NetworkTransportType.WIFI -> NetworkCapabilities.TRANSPORT_WIFI
            NetworkTransportType.CELLULAR -> NetworkCapabilities.TRANSPORT_CELLULAR
            NetworkTransportType.ETHERNET -> NetworkCapabilities.TRANSPORT_ETHERNET
            else -> return null
        }

        // Request INTERNET without pre-requiring VALIDATED so sleeping Cellular PDNs actually
        // wake up alongside Wi-Fi; validation is checked in onAvailable/onCapabilitiesChanged.
        val request = NetworkRequest.Builder()
            .addTransportType(androidTransport)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()

        return registerCallback(
            request = request,
            transportHint = transportType,
            useRequestNetwork = true,
            listener = listener
        )
    }

    private fun registerCallback(
        request: NetworkRequest,
        transportHint: NetworkTransportType?,
        useRequestNetwork: Boolean,
        listener: BondingNetworkListener
    ): AutoCloseable? {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                buildSnapshot(network, null, null)?.let { listener.onNetworkUpdated(it) }
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                buildSnapshot(network, capabilities, null)?.let { listener.onNetworkUpdated(it) }
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                buildSnapshot(network, null, linkProperties)?.let { listener.onNetworkUpdated(it) }
            }

            override fun onLost(network: Network) {
                listener.onNetworkLost(network.networkHandle)
            }

            override fun onUnavailable() {
                if (transportHint != null) {
                    listener.onTransportUnavailable(transportHint)
                }
            }
        }

        return try {
            if (useRequestNetwork) {
                connectivityManager.requestNetwork(request, callback)
            } else {
                connectivityManager.registerNetworkCallback(request, callback)
            }
            AutoCloseable {
                runCatching { connectivityManager.unregisterNetworkCallback(callback) }
            }
        } catch (se: SecurityException) {
            Log.w(TAG, "requestNetwork SecurityException, falling back to passive callback: ${se.message}")
            if (useRequestNetwork) {
                runCatching {
                    connectivityManager.registerNetworkCallback(request, callback)
                    AutoCloseable {
                        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
                    }
                }.getOrNull()
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register ConnectivityManager callback: ${e.message}")
            null
        }
    }

    private fun buildSnapshot(
        network: Network,
        capsOverride: NetworkCapabilities?,
        linkPropsOverride: LinkProperties?
    ): DiscoveredNetworkSnapshot? {
        val caps = capsOverride ?: runCatching {
            connectivityManager.getNetworkCapabilities(network)
        }.getOrNull() ?: return null

        val linkProps = linkPropsOverride ?: runCatching {
            connectivityManager.getLinkProperties(network)
        }.getOrNull()

        val interfaceName = linkProps?.interfaceName.orEmpty()
        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkTransportType.VPN
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkTransportType.ETHERNET
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTransportType.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTransportType.CELLULAR
            else -> NetworkTransportType.OTHER
        }

        val routes = linkProps?.routes.orEmpty()
        // If LinkProperties hasn't arrived yet, optimistically treat as routable until updated
        val hasDefaultRoute = if (linkProps == null || routes.isEmpty()) {
            true
        } else {
            routes.any { it.isDefaultRoute }
        }

        val addresses = linkProps?.linkAddresses.orEmpty().map { it.address }
        val supportsIpv4 = addresses.isEmpty() || addresses.any { it is Inet4Address }
        val supportsIpv6 = addresses.any { it is Inet6Address }

        val tempId = NetworkBondingManager.buildStableNetworkId(
            networkHandle = network.networkHandle,
            transport = transport,
            interfaceName = interfaceName
        )

        return DiscoveredNetworkSnapshot(
            networkHandle = network.networkHandle,
            transportType = transport,
            interfaceName = interfaceName,
            hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            isValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            isMetered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            isVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
            isBluetooth = caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH),
            hasDefaultRoute = hasDefaultRoute,
            supportsIpv4 = supportsIpv4,
            supportsIpv6 = supportsIpv6,
            linkDownstreamKbps = caps.linkDownstreamBandwidthKbps.coerceAtLeast(0),
            bindingHandle = AndroidNetworkBindingHandle(tempId, network)
        )
    }

    companion object {
        private const val TAG = "AndroidConnBackend"
    }
}

/**
 * Manages simultaneous multi-network bonding on Android by issuing independent
 * [ConnectivityManager.requestNetwork] leases for Wi-Fi, Cellular, and Ethernet so Android
 * keeps every physical interface active concurrently instead of disconnecting Cellular when Wi-Fi joins.
 *
 * Key responsibilities:
 * - Concurrent per-transport leases (`TRANSPORT_WIFI`, `TRANSPORT_CELLULAR`, `TRANSPORT_ETHERNET`)
 * - Socket-level and DNS-level OkHttp isolation via [BoundNetworkClientFactory]
 * - Deduplication across asynchronous `onAvailable` / `onLinkPropertiesChanged` updates via `networkHandle`
 * - Filtering of loopback, dummy/virtual, no-default-route, unvalidated, and VPN (unless enabled) links
 * - Metered-data protection keeping Cellular disabled by default until explicitly enabled
 * - Per-adapter reachability probing (`"Can't reach server"`), parking unstable links, and hot-plug events
 */
class NetworkBondingManager internal constructor(
    private val backend: ConnectivityBackend,
    private val configsFlow: Flow<List<NetworkConfigEntity>>,
    private val settingsFlow: Flow<PlexoSettings>,
    private val loadConfig: suspend (String) -> NetworkConfigEntity?,
    private val upsertConfig: suspend (NetworkConfigEntity) -> Unit,
    private val renameConfig: suspend (String, String) -> Unit,
    private val setEnabledConfig: suspend (String, Boolean) -> Unit,
    private val setStreamsConfig: suspend (String, Int) -> Unit,
    private val liftWifiGuardrail: suspend () -> Unit,
    val clientFactory: BoundNetworkClientFactory = OkHttpBoundNetworkClientFactory(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    constructor(
        context: Context,
        repository: DownloadRepository,
        settingsRepository: SettingsRepository,
        clientFactory: BoundNetworkClientFactory = OkHttpBoundNetworkClientFactory()
    ) : this(
        backend = AndroidConnectivityBackend(
            context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        ),
        configsFlow = repository.allNetworkConfigs,
        settingsFlow = settingsRepository.settingsFlow,
        loadConfig = { id -> repository.getNetworkConfig(id) },
        upsertConfig = { cfg -> repository.upsertNetworkConfig(cfg) },
        renameConfig = { id, name -> repository.renameNetwork(id, name) },
        setEnabledConfig = { id, enabled -> repository.setNetworkEnabled(id, enabled) },
        setStreamsConfig = { id, streams -> repository.setNetworkMaxStreams(id, streams) },
        liftWifiGuardrail = { settingsRepository.update { it.copy(wifiOnlyGuardrail = false) } },
        clientFactory = clientFactory
    )

    private val _adapters = MutableStateFlow<List<NetworkAdapterInfo>>(emptyList())
    val adapters: StateFlow<List<NetworkAdapterInfo>> = _adapters.asStateFlow()

    private val _bondingStatus = MutableStateFlow(BondingStatus())
    val bondingStatus: StateFlow<BondingStatus> = _bondingStatus.asStateFlow()

    private val _hotPlugEvents = MutableSharedFlow<NetworkHotPlugEvent>(extraBufferCapacity = 64)
    val hotPlugEvents: SharedFlow<NetworkHotPlugEvent> = _hotPlugEvents.asSharedFlow()

    private val activeNetworks = ConcurrentHashMap<String, NetworkAdapterInfo>()
    private val rawSnapshotsByHandle = ConcurrentHashMap<Long, DiscoveredNetworkSnapshot>()
    private val handleToNetworkId = ConcurrentHashMap<Long, String>()

    private val transportLeaseHandles = ConcurrentHashMap<NetworkTransportType, AutoCloseable>()
    private val transportLeaseStates = ConcurrentHashMap<NetworkTransportType, TransportLeaseState>()
    private var passiveMonitorHandle: AutoCloseable? = null

    private val parkedNetworks = ConcurrentHashMap<String, String>()
    private val unreachableByTarget = ConcurrentHashMap<String, String>()

    @Volatile
    private var currentSettings: PlexoSettings = PlexoSettings()

    @Volatile
    private var configsCache: Map<String, NetworkConfigEntity> = emptyMap()

    @Volatile
    private var isStarted = false

    @Volatile
    private var bondingLeasesActive = false

    private val networkListener = object : BondingNetworkListener {
        override fun onNetworkUpdated(snapshot: DiscoveredNetworkSnapshot) {
            handleDiscoveredSnapshot(snapshot)
        }

        override fun onNetworkLost(networkHandle: Long) {
            handleLostNetwork(networkHandle)
        }

        override fun onTransportUnavailable(transportType: NetworkTransportType) {
            transportLeaseStates[transportType] = TransportLeaseState.UNAVAILABLE
            publishSnapshot()
        }
    }

    init {
        scope.launch {
            settingsFlow.collect { settings ->
                val vpnSettingChanged = currentSettings.allowVpnBonding != settings.allowVpnBonding
                currentSettings = settings
                if (vpnSettingChanged) {
                    reconcileAllRawSnapshots()
                } else {
                    refreshEffectiveStates()
                }
            }
        }
        scope.launch {
            configsFlow.collect { configs ->
                configsCache = configs.associateBy { it.networkId }
                refreshEffectiveStates()
            }
        }
    }

    /**
     * Begins passive monitoring of all internet-capable interfaces and immediately acquires
     * simultaneous Wi-Fi, Cellular, and Ethernet bonding leases so concurrent networks stay up.
     */
    @Synchronized
    fun startMonitoring() {
        if (isStarted) return
        isStarted = true

        backend.snapshotExistingNetworks().forEach { snapshot ->
            handleDiscoveredSnapshot(snapshot)
        }

        passiveMonitorHandle = backend.registerPassiveMonitor(networkListener)
        acquireBondingLeases()
    }

    /**
     * Issues separate [ConnectivityManager.requestNetwork] leases for Wi-Fi, Cellular, and
     * Ethernet simultaneously, overriding Android's default behavior of tearing down Cellular
     * whenever Wi-Fi is connected.
     */
    @Synchronized
    fun acquireBondingLeases() {
        bondingLeasesActive = true
        BONDED_TRANSPORTS.forEach { transport ->
            if (!transportLeaseHandles.containsKey(transport)) {
                transportLeaseStates[transport] = TransportLeaseState.REQUESTING
                val handle = backend.requestTransportNetwork(transport, networkListener)
                if (handle != null) {
                    transportLeaseHandles[transport] = handle
                } else {
                    transportLeaseStates[transport] = TransportLeaseState.UNAVAILABLE
                }
            }
        }
        publishSnapshot()
    }

    /**
     * Releases active per-transport `requestNetwork` leases (for instance, when idle and saving battery)
     * while keeping passive observation intact.
     */
    @Synchronized
    fun releaseBondingLeases() {
        if (!bondingLeasesActive && transportLeaseHandles.isEmpty()) return
        bondingLeasesActive = false
        transportLeaseHandles.values.forEach { runCatching { it.close() } }
        transportLeaseHandles.clear()
        BONDED_TRANSPORTS.forEach { transportLeaseStates[it] = TransportLeaseState.IDLE }
        publishSnapshot()
    }

    @Synchronized
    fun stopMonitoring() {
        releaseBondingLeases()
        runCatching { passiveMonitorHandle?.close() }
        passiveMonitorHandle = null
        activeNetworks.clear()
        rawSnapshotsByHandle.clear()
        handleToNetworkId.clear()
        clientFactory.evictAll()
        isStarted = false
        publishSnapshot()
    }

    fun getBoundClient(networkId: String): OkHttpClient {
        val adapter = activeNetworks[networkId] ?: return clientFactory.getDefaultClient()
        return clientFactory.getClientFor(adapter)
    }

    private fun reconcileAllRawSnapshots() {
        // Evict VPN adapters immediately if allowVpnBonding was switched OFF
        if (!currentSettings.allowVpnBonding) {
            val vpnEntries = activeNetworks.entries.filter { it.value.isVpn }
            vpnEntries.forEach { (id, adapter) ->
                activeNetworks.remove(id)
                adapter.bindingHandle?.let { handleToNetworkId.remove(it.networkHandle) }
                clientFactory.evict(id)
                _hotPlugEvents.tryEmit(NetworkHotPlugEvent.NetworkLost(id))
            }
        }
        rawSnapshotsByHandle.values.forEach { snapshot ->
            handleDiscoveredSnapshot(snapshot)
        }
        publishSnapshot()
    }

    internal fun handleDiscoveredSnapshot(snapshot: DiscoveredNetworkSnapshot) {
        rawSnapshotsByHandle[snapshot.networkHandle] = snapshot

        if (isFilteredOutSnapshot(snapshot)) {
            // If a previously valid interface became filtered (e.g. lost default route or VPN disallowed), remove it
            val existingId = handleToNetworkId.remove(snapshot.networkHandle)
            if (existingId != null && activeNetworks.remove(existingId) != null) {
                clientFactory.evict(existingId)
                publishSnapshot()
                _hotPlugEvents.tryEmit(NetworkHotPlugEvent.NetworkLost(existingId))
            }
            return
        }

        if (snapshot.transportType in BONDED_TRANSPORTS && snapshot.hasInternet) {
            transportLeaseStates[snapshot.transportType] = TransportLeaseState.ACTIVE
        }

        val networkId = buildStableNetworkId(
            networkHandle = snapshot.networkHandle,
            transport = snapshot.transportType,
            interfaceName = snapshot.interfaceName
        )

        // Deduplicate: if this networkHandle was previously keyed under a temporary fallback ID
        // before onLinkPropertiesChanged supplied interfaceName, migrate it cleanly.
        val previousIdForHandle = handleToNetworkId.put(snapshot.networkHandle, networkId)
        if (previousIdForHandle != null && previousIdForHandle != networkId) {
            activeNetworks.remove(previousIdForHandle)
            clientFactory.evict(previousIdForHandle)
        }

        val systemName = buildSystemName(snapshot.transportType, snapshot.interfaceName)
        val existingConfig = configsCache[networkId]

        // Default metered Cellular to OFF so users never burn cellular data accidentally
        val defaultEnabled = when {
            snapshot.transportType == NetworkTransportType.CELLULAR && snapshot.isMetered -> false
            snapshot.transportType == NetworkTransportType.CELLULAR && currentSettings.wifiOnlyGuardrail -> false
            else -> true
        }
        val effectiveEnabled = when {
            snapshot.transportType == NetworkTransportType.CELLULAR && currentSettings.wifiOnlyGuardrail -> false
            existingConfig != null -> existingConfig.isEnabled
            else -> defaultEnabled
        }

        val effectiveMaxStreams = existingConfig?.maxStreams
            ?: currentSettings.defaultStreamsFor(snapshot.transportType)

        val reachability = computeReachabilityState(
            networkId = networkId,
            isEnabled = effectiveEnabled,
            isMetered = snapshot.isMetered,
            transport = snapshot.transportType,
            isValidated = snapshot.isValidated
        )

        val adapter = NetworkAdapterInfo(
            networkId = networkId,
            systemName = systemName,
            customName = existingConfig?.customName ?: systemName,
            interfaceName = snapshot.interfaceName.ifBlank { snapshot.transportType.name.lowercase() },
            transportType = snapshot.transportType,
            isMetered = snapshot.isMetered,
            isEnabled = effectiveEnabled,
            isVpn = snapshot.isVpn,
            isValidated = snapshot.isValidated,
            hasInternet = snapshot.hasInternet,
            hasDefaultRoute = snapshot.hasDefaultRoute,
            supportsIpv4 = snapshot.supportsIpv4,
            supportsIpv6 = snapshot.supportsIpv6,
            linkDownstreamKbps = snapshot.linkDownstreamKbps,
            maxStreams = effectiveMaxStreams,
            reachabilityState = reachability.first,
            reachabilityMessage = reachability.second,
            bindingHandle = snapshot.bindingHandle
        )

        val previous = activeNetworks.put(networkId, adapter)
        if (existingConfig == null) {
            scope.launch {
                upsertConfig(
                    NetworkConfigEntity(
                        networkId = networkId,
                        customName = adapter.customName,
                        transportType = snapshot.transportType,
                        isEnabled = effectiveEnabled,
                        maxStreams = effectiveMaxStreams,
                        isMetered = snapshot.isMetered
                    )
                )
            }
        }

        publishSnapshot()
        if (previous == null) {
            _hotPlugEvents.tryEmit(NetworkHotPlugEvent.NetworkAdded(adapter))
        } else if (previous != adapter) {
            _hotPlugEvents.tryEmit(NetworkHotPlugEvent.NetworkUpdated(adapter))
        }
    }

    internal fun handleLostNetwork(networkHandle: Long) {
        val raw = rawSnapshotsByHandle.remove(networkHandle)
        val removedId = handleToNetworkId.remove(networkHandle)
            ?: activeNetworks.entries.firstOrNull {
                it.value.bindingHandle?.networkHandle == networkHandle
            }?.key

        if (raw != null && raw.transportType in BONDED_TRANSPORTS) {
            val stillHasSameTransport = activeNetworks.values.any {
                it.networkId != removedId && it.transportType == raw.transportType
            }
            if (!stillHasSameTransport) {
                transportLeaseStates[raw.transportType] = if (bondingLeasesActive) {
                    TransportLeaseState.REQUESTING
                } else {
                    TransportLeaseState.IDLE
                }
            }
        }

        if (removedId != null) {
            activeNetworks.remove(removedId)
            clientFactory.evict(removedId)
            publishSnapshot()
            _hotPlugEvents.tryEmit(NetworkHotPlugEvent.NetworkLost(removedId))
        }
    }

    private fun isFilteredOutSnapshot(snapshot: DiscoveredNetworkSnapshot): Boolean {
        if (!snapshot.hasInternet) return true
        if (!snapshot.hasDefaultRoute) return true
        if (snapshot.isVpn && !currentSettings.allowVpnBonding) return true

        val lower = snapshot.interfaceName.lowercase()
        if (lower == "lo" ||
            lower.startsWith("dummy") ||
            lower.startsWith("sit") ||
            lower.startsWith("ip6tnl") ||
            lower.startsWith("v4-") ||
            lower.startsWith("tun") && !snapshot.isVpn
        ) {
            return true
        }
        if (snapshot.isBluetooth && !snapshot.isValidated) {
            return true
        }
        return false
    }

    private fun buildSystemName(transport: NetworkTransportType, interfaceName: String): String {
        val suffix = interfaceName.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
        return "${transport.displayName}$suffix"
    }

    private fun computeReachabilityState(
        networkId: String,
        isEnabled: Boolean,
        isMetered: Boolean,
        transport: NetworkTransportType,
        isValidated: Boolean
    ): Pair<ReachabilityState, String?> {
        if (!isEnabled) {
            return if (isMetered && transport == NetworkTransportType.CELLULAR) {
                ReachabilityState.METERED_OFF to "Metered — off by default"
            } else {
                ReachabilityState.DISABLED to "Disabled"
            }
        }
        if (!isValidated) {
            return ReachabilityState.PROBING to "Validating internet…"
        }
        parkedNetworks[networkId]?.let { reason ->
            return ReachabilityState.PARKED to reason
        }
        unreachableByTarget[networkId]?.let { reason ->
            return ReachabilityState.UNREACHABLE to reason
        }
        return ReachabilityState.READY to null
    }

    private fun refreshEffectiveStates() {
        var changed = false
        activeNetworks.entries.forEach { (id, current) ->
            val cfg = configsCache[id]
            val enabled = when {
                current.transportType == NetworkTransportType.CELLULAR && currentSettings.wifiOnlyGuardrail -> false
                cfg != null -> cfg.isEnabled
                else -> current.isEnabled
            }
            val reach = computeReachabilityState(
                networkId = id,
                isEnabled = enabled,
                isMetered = current.isMetered,
                transport = current.transportType,
                isValidated = current.isValidated
            )
            val updated = current.copy(
                customName = cfg?.customName ?: current.customName,
                isEnabled = enabled,
                maxStreams = cfg?.maxStreams ?: currentSettings.defaultStreamsFor(current.transportType),
                reachabilityState = reach.first,
                reachabilityMessage = reach.second
            )
            if (updated != current) {
                activeNetworks[id] = updated
                changed = true
                _hotPlugEvents.tryEmit(NetworkHotPlugEvent.NetworkUpdated(updated))
            }
        }
        if (changed) publishSnapshot()
    }

    private fun publishSnapshot() {
        val sorted = activeNetworks.values.sortedWith(
            compareBy<NetworkAdapterInfo> { it.transportType.ordinal }
                .thenBy { it.displayName }
        )
        _adapters.value = sorted
        _bondingStatus.value = BondingStatus(
            leasesHeld = bondingLeasesActive,
            transportStates = transportLeaseStates.toMap(),
            activeUsableCount = sorted.count { it.isUsableForDownload }
        )
    }

    suspend fun probeReachability(url: String, adapter: NetworkAdapterInfo): Boolean =
        withContext(Dispatchers.IO) {
            if (!adapter.isEnabled || !adapter.isValidated) return@withContext false
            updateAdapterState(adapter.networkId, ReachabilityState.PROBING, "Checking server…")
            val client = clientFactory.getClientFor(adapter)
            val request = Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", USER_AGENT)
                .build()
            val reachable = try {
                client.newCall(request).execute().use { response ->
                    response.code in 200..499
                }
            } catch (_: Exception) {
                false
            }
            if (reachable) {
                unreachableByTarget.remove(adapter.networkId)
                parkedNetworks.remove(adapter.networkId)
                updateAdapterState(adapter.networkId, ReachabilityState.READY, null)
            } else {
                unreachableByTarget[adapter.networkId] = "Can't reach server"
                updateAdapterState(adapter.networkId, ReachabilityState.UNREACHABLE, "Can't reach server")
            }
            reachable
        }

    fun markAdapterParked(networkId: String, reason: String = "Parked after consecutive failures") {
        parkedNetworks[networkId] = reason
        updateAdapterState(networkId, ReachabilityState.PARKED, reason)
    }

    fun clearAdapterPark(networkId: String) {
        parkedNetworks.remove(networkId)
        unreachableByTarget.remove(networkId)
        refreshEffectiveStates()
    }

    private fun updateAdapterState(networkId: String, state: ReachabilityState, message: String?) {
        val current = activeNetworks[networkId] ?: return
        val updated = current.copy(reachabilityState = state, reachabilityMessage = message)
        activeNetworks[networkId] = updated
        publishSnapshot()
    }

    suspend fun toggleAdapter(networkId: String, enabled: Boolean) {
        val adapter = activeNetworks[networkId]
        if (enabled && adapter?.transportType == NetworkTransportType.CELLULAR && currentSettings.wifiOnlyGuardrail) {
            liftWifiGuardrail()
        }
        if (enabled) {
            parkedNetworks.remove(networkId)
            unreachableByTarget.remove(networkId)
        }
        val existing = loadConfig(networkId)
        if (existing != null) {
            setEnabledConfig(networkId, enabled)
        } else if (adapter != null) {
            upsertConfig(
                NetworkConfigEntity(
                    networkId = networkId,
                    customName = adapter.customName,
                    transportType = adapter.transportType,
                    isEnabled = enabled,
                    maxStreams = adapter.maxStreams,
                    isMetered = adapter.isMetered
                )
            )
        }
    }

    suspend fun renameAdapter(networkId: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        val existing = loadConfig(networkId)
        if (existing != null) {
            renameConfig(networkId, trimmed)
        } else {
            val adapter = activeNetworks[networkId] ?: return
            upsertConfig(
                NetworkConfigEntity(
                    networkId = networkId,
                    customName = trimmed,
                    transportType = adapter.transportType,
                    isEnabled = adapter.isEnabled,
                    maxStreams = adapter.maxStreams,
                    isMetered = adapter.isMetered
                )
            )
        }
    }

    suspend fun setAdapterMaxStreams(networkId: String, maxStreams: Int) {
        val clamped = maxStreams.coerceIn(1, 32)
        val existing = loadConfig(networkId)
        if (existing != null) {
            setStreamsConfig(networkId, clamped)
        } else {
            val adapter = activeNetworks[networkId] ?: return
            upsertConfig(
                NetworkConfigEntity(
                    networkId = networkId,
                    customName = adapter.customName,
                    transportType = adapter.transportType,
                    isEnabled = adapter.isEnabled,
                    maxStreams = clamped,
                    isMetered = adapter.isMetered
                )
            )
        }
    }

    fun getUsableAdapters(allowedNetworkIds: Set<String> = emptySet()): List<NetworkAdapterInfo> {
        return _adapters.value.filter { adapter ->
            adapter.isUsableForDownload &&
                (allowedNetworkIds.isEmpty() || allowedNetworkIds.contains(adapter.networkId))
        }
    }

    companion object {
        const val USER_AGENT = "Plexo/1.0 (Android; Multi-Network Bonding Downloader)"

        val BONDED_TRANSPORTS = listOf(
            NetworkTransportType.WIFI,
            NetworkTransportType.CELLULAR,
            NetworkTransportType.ETHERNET
        )

        fun buildStableNetworkId(
            networkHandle: Long,
            transport: NetworkTransportType,
            interfaceName: String?
        ): String {
            val cleanIface = interfaceName?.takeIf { it.isNotBlank() }
            return if (cleanIface != null) {
                "${transport.name.lowercase()}_$cleanIface"
            } else {
                "${transport.name.lowercase()}_$networkHandle"
            }
        }
    }
}
