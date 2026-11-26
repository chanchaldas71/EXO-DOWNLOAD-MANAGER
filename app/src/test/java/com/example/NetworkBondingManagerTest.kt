package com.example

import com.example.data.local.entity.NetworkConfigEntity
import com.example.data.model.NetworkTransportType
import com.example.data.model.ReachabilityState
import com.example.data.preferences.PlexoSettings
import com.example.engine.network.BondingNetworkListener
import com.example.engine.network.ConnectivityBackend
import com.example.engine.network.DiscoveredNetworkSnapshot
import com.example.engine.network.NetworkBindingHandle
import com.example.engine.network.NetworkBondingManager
import com.example.engine.network.NetworkHotPlugEvent
import com.example.engine.network.TransportLeaseState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import javax.net.SocketFactory

class NetworkBondingManagerTest {

    private class FakeConnectivityBackend : ConnectivityBackend {
        val requestedTransports = mutableListOf<NetworkTransportType>()
        var passiveRegistered = false
        var releasedLeaseCount = 0
        var activeListener: BondingNetworkListener? = null

        override fun snapshotExistingNetworks(): List<DiscoveredNetworkSnapshot> = emptyList()

        override fun registerPassiveMonitor(listener: BondingNetworkListener): AutoCloseable {
            passiveRegistered = true
            activeListener = listener
            return AutoCloseable { passiveRegistered = false }
        }

        override fun requestTransportNetwork(
            transportType: NetworkTransportType,
            listener: BondingNetworkListener
        ): AutoCloseable {
            requestedTransports.add(transportType)
            activeListener = listener
            return AutoCloseable { releasedLeaseCount++ }
        }
    }

    private fun fakeHandle(id: String, handle: Long) = object : NetworkBindingHandle {
        override val networkId = id
        override val networkHandle = handle
        override val socketFactory: SocketFactory = SocketFactory.getDefault()
        override fun resolveDns(hostname: String): List<InetAddress> = listOf(InetAddress.getLoopbackAddress())
    }

    private fun createManager(
        backend: FakeConnectivityBackend,
        settingsFlow: MutableStateFlow<PlexoSettings> = MutableStateFlow(PlexoSettings()),
        configsFlow: MutableStateFlow<List<NetworkConfigEntity>> = MutableStateFlow(emptyList()),
        scope: CoroutineScope
    ): NetworkBondingManager {
        return NetworkBondingManager(
            backend = backend,
            configsFlow = configsFlow,
            settingsFlow = settingsFlow,
            loadConfig = { id -> configsFlow.value.firstOrNull { it.networkId == id } },
            upsertConfig = { cfg ->
                configsFlow.value = configsFlow.value.filterNot { it.networkId == cfg.networkId } + cfg
            },
            renameConfig = { id, name ->
                configsFlow.value = configsFlow.value.map {
                    if (it.networkId == id) it.copy(customName = name) else it
                }
            },
            setEnabledConfig = { id, enabled ->
                configsFlow.value = configsFlow.value.map {
                    if (it.networkId == id) it.copy(isEnabled = enabled) else it
                }
            },
            setStreamsConfig = { id, streams ->
                configsFlow.value = configsFlow.value.map {
                    if (it.networkId == id) it.copy(maxStreams = streams) else it
                }
            },
            liftWifiGuardrail = {
                settingsFlow.value = settingsFlow.value.copy(wifiOnlyGuardrail = false)
            },
            scope = scope
        )
    }

    @Test
    fun `startMonitoring issues simultaneous leases for Wi-Fi Cellular and Ethernet`() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val backend = FakeConnectivityBackend()
        val manager = createManager(backend, scope = scope)

        manager.startMonitoring()

        assertTrue(backend.passiveRegistered)
        assertEquals(
            listOf(NetworkTransportType.WIFI, NetworkTransportType.CELLULAR, NetworkTransportType.ETHERNET),
            backend.requestedTransports
        )
        assertTrue(manager.bondingStatus.value.leasesHeld)

        // Simulate Wi-Fi + Cellular + USB Ethernet all coming online concurrently
        val listener = backend.activeListener!!
        listener.onNetworkUpdated(
            DiscoveredNetworkSnapshot(
                networkHandle = 101L,
                transportType = NetworkTransportType.WIFI,
                interfaceName = "wlan0",
                hasInternet = true,
                isValidated = true,
                isMetered = false,
                isVpn = false,
                isBluetooth = false,
                hasDefaultRoute = true,
                supportsIpv4 = true,
                supportsIpv6 = true,
                linkDownstreamKbps = 400_000,
                bindingHandle = fakeHandle("wifi_wlan0", 101L)
            )
        )
        listener.onNetworkUpdated(
            DiscoveredNetworkSnapshot(
                networkHandle = 202L,
                transportType = NetworkTransportType.CELLULAR,
                interfaceName = "rmnet_data0",
                hasInternet = true,
                isValidated = true,
                isMetered = true,
                isVpn = false,
                isBluetooth = false,
                hasDefaultRoute = true,
                supportsIpv4 = true,
                supportsIpv6 = true,
                linkDownstreamKbps = 250_000,
                bindingHandle = fakeHandle("cellular_rmnet_data0", 202L)
            )
        )
        listener.onNetworkUpdated(
            DiscoveredNetworkSnapshot(
                networkHandle = 303L,
                transportType = NetworkTransportType.ETHERNET,
                interfaceName = "eth0",
                hasInternet = true,
                isValidated = true,
                isMetered = false,
                isVpn = false,
                isBluetooth = false,
                hasDefaultRoute = true,
                supportsIpv4 = true,
                supportsIpv6 = false,
                linkDownstreamKbps = 1_000_000,
                bindingHandle = fakeHandle("ethernet_eth0", 303L)
            )
        )

        assertEquals(3, manager.adapters.value.size)
        assertEquals(TransportLeaseState.ACTIVE, manager.bondingStatus.value.transportStates[NetworkTransportType.WIFI])
        assertEquals(TransportLeaseState.ACTIVE, manager.bondingStatus.value.transportStates[NetworkTransportType.CELLULAR])
        assertEquals(TransportLeaseState.ACTIVE, manager.bondingStatus.value.transportStates[NetworkTransportType.ETHERNET])

        // Metered Cellular is disabled by default while Wi-Fi + Ethernet are active -> 2 usable bonded links
        val cellular = manager.adapters.value.first { it.transportType == NetworkTransportType.CELLULAR }
        assertFalse(cellular.isEnabled)
        assertEquals(ReachabilityState.METERED_OFF, cellular.reachabilityState)
        assertEquals(2, manager.getUsableAdapters().size)
        assertTrue(manager.bondingStatus.value.isMultiNetworkBonded)

        // Enabling Cellular lifts Wi-Fi-only guardrail and brings all 3 concurrent links online
        manager.toggleAdapter(cellular.networkId, true)
        delay(20L)
        assertEquals(3, manager.getUsableAdapters().size)

        manager.stopMonitoring()
        assertEquals(3, backend.releasedLeaseCount)
        scope.cancel()
    }

    @Test
    fun `deduplicates handle when interfaceName arrives later and filters loopback or VPN`() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val backend = FakeConnectivityBackend()
        val settingsFlow = MutableStateFlow(PlexoSettings(allowVpnBonding = false))
        val manager = createManager(backend, settingsFlow = settingsFlow, scope = scope)
        val events = mutableListOf<NetworkHotPlugEvent>()
        val collectJob = scope.launch { manager.hotPlugEvents.collect { events.add(it) } }

        manager.startMonitoring()
        val listener = backend.activeListener!!

        // 1. Loopback and VPN (when disallowed) and no-default-route interfaces must be filtered out
        listener.onNetworkUpdated(
            DiscoveredNetworkSnapshot(
                networkHandle = 1L,
                transportType = NetworkTransportType.OTHER,
                interfaceName = "lo",
                hasInternet = true,
                isValidated = true,
                isMetered = false,
                isVpn = false,
                isBluetooth = false,
                hasDefaultRoute = true,
                supportsIpv4 = true,
                supportsIpv6 = false,
                linkDownstreamKbps = 1000,
                bindingHandle = fakeHandle("other_lo", 1L)
            )
        )
        listener.onNetworkUpdated(
            DiscoveredNetworkSnapshot(
                networkHandle = 2L,
                transportType = NetworkTransportType.VPN,
                interfaceName = "tun0",
                hasInternet = true,
                isValidated = true,
                isMetered = false,
                isVpn = true,
                isBluetooth = false,
                hasDefaultRoute = true,
                supportsIpv4 = true,
                supportsIpv6 = false,
                linkDownstreamKbps = 100_000,
                bindingHandle = fakeHandle("vpn_tun0", 2L)
            )
        )
        assertTrue(manager.adapters.value.isEmpty())

        // 2. First callback arrives without interfaceName, second supplies wlan0 -> must NOT duplicate
        listener.onNetworkUpdated(
            DiscoveredNetworkSnapshot(
                networkHandle = 555L,
                transportType = NetworkTransportType.WIFI,
                interfaceName = "",
                hasInternet = true,
                isValidated = false,
                isMetered = false,
                isVpn = false,
                isBluetooth = false,
                hasDefaultRoute = true,
                supportsIpv4 = true,
                supportsIpv6 = false,
                linkDownstreamKbps = 200_000,
                bindingHandle = fakeHandle("wifi_555", 555L)
            )
        )
        assertEquals(1, manager.adapters.value.size)

        listener.onNetworkUpdated(
            DiscoveredNetworkSnapshot(
                networkHandle = 555L,
                transportType = NetworkTransportType.WIFI,
                interfaceName = "wlan0",
                hasInternet = true,
                isValidated = true,
                isMetered = false,
                isVpn = false,
                isBluetooth = false,
                hasDefaultRoute = true,
                supportsIpv4 = true,
                supportsIpv6 = true,
                linkDownstreamKbps = 350_000,
                bindingHandle = fakeHandle("wifi_wlan0", 555L)
            )
        )
        assertEquals(1, manager.adapters.value.size)
        assertEquals("wifi_wlan0", manager.adapters.value.first().networkId)

        // 3. Hot-unplug fires NetworkLost and removes adapter cleanly
        listener.onNetworkLost(555L)
        assertTrue(manager.adapters.value.isEmpty())
        assertTrue(events.any { it is NetworkHotPlugEvent.NetworkLost && it.networkId == "wifi_wlan0" })

        collectJob.cancel()
        scope.cancel()
    }
}
