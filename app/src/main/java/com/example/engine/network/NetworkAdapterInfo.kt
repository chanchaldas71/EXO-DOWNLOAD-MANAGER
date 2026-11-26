package com.example.engine.network

import android.net.Network
import com.example.data.model.NetworkTransportType
import com.example.data.model.ReachabilityState
import java.net.InetAddress
import javax.net.SocketFactory

/**
 * Abstraction over [android.net.Network] so OkHttp binding can be tested deterministically on JVM.
 */
interface NetworkBindingHandle {
    val networkId: String
    val networkHandle: Long
    val socketFactory: SocketFactory
    fun resolveDns(hostname: String): List<InetAddress>
}

class AndroidNetworkBindingHandle(
    override val networkId: String,
    val network: Network
) : NetworkBindingHandle {
    override val networkHandle: Long
        get() = network.networkHandle

    override val socketFactory: SocketFactory
        get() = network.socketFactory

    override fun resolveDns(hostname: String): List<InetAddress> =
        network.getAllByName(hostname).toList()
}

data class NetworkAdapterInfo(
    val networkId: String,
    val systemName: String,
    val customName: String,
    val interfaceName: String,
    val transportType: NetworkTransportType,
    val isMetered: Boolean,
    val isEnabled: Boolean,
    val isVpn: Boolean,
    val isValidated: Boolean,
    val hasInternet: Boolean,
    val hasDefaultRoute: Boolean = true,
    val supportsIpv4: Boolean = true,
    val supportsIpv6: Boolean = false,
    val linkDownstreamKbps: Int,
    val maxStreams: Int,
    val reachabilityState: ReachabilityState = ReachabilityState.READY,
    val reachabilityMessage: String? = null,
    val bindingHandle: NetworkBindingHandle? = null
) {
    val displayName: String
        get() = customName.ifBlank { systemName }

    val isUsableForDownload: Boolean
        get() = isEnabled &&
            hasInternet &&
            isValidated &&
            hasDefaultRoute &&
            reachabilityState != ReachabilityState.UNREACHABLE &&
            reachabilityState != ReachabilityState.PARKED
}

enum class TransportLeaseState {
    IDLE,
    REQUESTING,
    ACTIVE,
    UNAVAILABLE
}

data class BondingStatus(
    val leasesHeld: Boolean = false,
    val transportStates: Map<NetworkTransportType, TransportLeaseState> = emptyMap(),
    val activeUsableCount: Int = 0
) {
    val isMultiNetworkBonded: Boolean
        get() = activeUsableCount >= 2
}

sealed interface NetworkHotPlugEvent {
    data class NetworkAdded(val adapter: NetworkAdapterInfo) : NetworkHotPlugEvent
    data class NetworkLost(val networkId: String) : NetworkHotPlugEvent
    data class NetworkUpdated(val adapter: NetworkAdapterInfo) : NetworkHotPlugEvent
}
