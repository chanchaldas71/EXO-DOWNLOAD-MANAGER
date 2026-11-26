package com.example.engine.network

import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

interface BoundNetworkClientFactory {
    fun getClientFor(adapter: NetworkAdapterInfo): OkHttpClient
    fun getDefaultClient(): OkHttpClient
    fun evict(networkId: String)
    fun evictAll()
}

/**
 * Creates dedicated [OkHttpClient] instances bound strictly to a specific network's
 * [javax.net.SocketFactory] and DNS resolver so traffic cannot silently fall back
 * to the Android system default route.
 */
class OkHttpBoundNetworkClientFactory(
    private val connectTimeoutSec: Long = 15L,
    private val readTimeoutSec: Long = 15L
) : BoundNetworkClientFactory {

    private val clients = ConcurrentHashMap<String, OkHttpClient>()

    private val sharedDefaultClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(connectTimeoutSec, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
            .writeTimeout(15L, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(false)
            .connectionPool(ConnectionPool(16, 30, TimeUnit.SECONDS))
            .build()
    }

    override fun getDefaultClient(): OkHttpClient = sharedDefaultClient

    override fun getClientFor(adapter: NetworkAdapterInfo): OkHttpClient {
        val handle = adapter.bindingHandle ?: return sharedDefaultClient
        return clients.computeIfAbsent(adapter.networkId) {
            buildBoundClient(handle, adapter.maxStreams)
        }
    }

    fun buildBoundClient(handle: NetworkBindingHandle, maxStreams: Int = 16): OkHttpClient {
        val dns = object : Dns {
            override fun lookup(hostname: String) = handle.resolveDns(hostname)
        }
        return OkHttpClient.Builder()
            .socketFactory(handle.socketFactory)
            .dns(dns)
            .connectTimeout(connectTimeoutSec, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
            .writeTimeout(15L, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(false)
            .connectionPool(ConnectionPool(maxStreams.coerceAtLeast(8), 30, TimeUnit.SECONDS))
            .build()
    }

    override fun evict(networkId: String) {
        clients.remove(networkId)?.let { client ->
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
        }
    }

    override fun evictAll() {
        val keys = clients.keys.toList()
        keys.forEach { evict(it) }
    }
}
