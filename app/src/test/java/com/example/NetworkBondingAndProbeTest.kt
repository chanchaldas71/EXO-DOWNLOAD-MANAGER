package com.example

import com.example.data.model.NetworkTransportType
import com.example.engine.network.NetworkAdapterInfo
import com.example.engine.network.NetworkBindingHandle
import com.example.engine.network.OkHttpBoundNetworkClientFactory
import com.example.engine.network.ProbeResult
import com.example.engine.network.ProbeService
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory

class NetworkBondingAndProbeTest {

    private lateinit var server: HttpServer
    private var port: Int = 0
    private val payload = ByteArray(128 * 1024) { (it % 251).toByte() }

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        port = server.address.port

        // Standard HEAD + Range endpoint
        server.createContext("/file.bin") { ex ->
            ex.responseHeaders.add("Accept-Ranges", "bytes")
            ex.responseHeaders.add("ETag", "\"etag-v1\"")
            ex.responseHeaders.add("Last-Modified", "Wed, 21 Oct 2026 07:28:00 GMT")
            ex.responseHeaders.add("Content-Disposition", "attachment; filename*=UTF-8''Plexo%20Release%201.0.zip")
            if (ex.requestMethod.equals("HEAD", ignoreCase = true)) {
                ex.responseHeaders.add("Content-Length", payload.size.toString())
                ex.sendResponseHeaders(200, -1)
            } else {
                ex.sendResponseHeaders(200, payload.size.toLong())
                ex.responseBody.write(payload)
            }
            ex.close()
        }

        // Endpoint that refuses HEAD (405) and supports GET Range: bytes=0-0
        server.createContext("/no-head.iso") { ex ->
            if (ex.requestMethod.equals("HEAD", ignoreCase = true)) {
                ex.sendResponseHeaders(405, -1)
                ex.close()
                return@createContext
            }
            val range = ex.requestHeaders.getFirst("Range")
            if (range == "bytes=0-0") {
                ex.responseHeaders.add("Content-Range", "bytes 0-0/${payload.size}")
                ex.responseHeaders.add("Accept-Ranges", "bytes")
                ex.responseHeaders.add("ETag", "\"nohead-etag\"")
                ex.sendResponseHeaders(206, 1)
                ex.responseBody.write(byteArrayOf(payload[0]))
            } else {
                ex.sendResponseHeaders(200, payload.size.toLong())
                ex.responseBody.write(payload)
            }
            ex.close()
        }

        // Redirect endpoint -> /file.bin
        server.createContext("/redirect") { ex ->
            ex.responseHeaders.add("Location", "http://127.0.0.1:$port/file.bin")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }

        // Expired link endpoint (410 Gone)
        server.createContext("/expired") { ex ->
            ex.sendResponseHeaders(410, -1)
            ex.close()
        }

        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    private class CountingSocketFactory : SocketFactory() {
        val socketsCreated = AtomicInteger(0)
        private val delegate = getDefault()

        override fun createSocket(): Socket {
            socketsCreated.incrementAndGet()
            return delegate.createSocket()
        }

        override fun createSocket(host: String?, port: Int): Socket {
            socketsCreated.incrementAndGet()
            return delegate.createSocket(host, port)
        }

        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket {
            socketsCreated.incrementAndGet()
            return delegate.createSocket(host, port, localHost, localPort)
        }

        override fun createSocket(host: InetAddress?, port: Int): Socket {
            socketsCreated.incrementAndGet()
            return delegate.createSocket(host, port)
        }

        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket {
            socketsCreated.incrementAndGet()
            return delegate.createSocket(address, port, localAddress, localPort)
        }
    }

    @Test
    fun `bound clients route strictly through their own SocketFactory and DNS`() {
        val wifiFactory = CountingSocketFactory()
        val cellularFactory = CountingSocketFactory()
        val wifiDnsCalls = AtomicInteger(0)
        val cellularDnsCalls = AtomicInteger(0)

        val wifiAdapter = NetworkAdapterInfo(
            networkId = "wifi_wlan0",
            systemName = "Wi-Fi",
            customName = "Home Wi-Fi",
            interfaceName = "wlan0",
            transportType = NetworkTransportType.WIFI,
            isMetered = false,
            isEnabled = true,
            isVpn = false,
            isValidated = true,
            hasInternet = true,
            linkDownstreamKbps = 300_000,
            maxStreams = 8,
            bindingHandle = object : NetworkBindingHandle {
                override val networkId = "wifi_wlan0"
                override val networkHandle = 1001L
                override val socketFactory: SocketFactory = wifiFactory
                override fun resolveDns(hostname: String): List<InetAddress> {
                    wifiDnsCalls.incrementAndGet()
                    return listOf(InetAddress.getByName("127.0.0.1"))
                }
            }
        )

        val cellularAdapter = wifiAdapter.copy(
            networkId = "cellular_rmnet0",
            systemName = "5G / Cellular",
            customName = "5G SIM",
            interfaceName = "rmnet0",
            transportType = NetworkTransportType.CELLULAR,
            isMetered = true,
            maxStreams = 4,
            bindingHandle = object : NetworkBindingHandle {
                override val networkId = "cellular_rmnet0"
                override val networkHandle = 1002L
                override val socketFactory: SocketFactory = cellularFactory
                override fun resolveDns(hostname: String): List<InetAddress> {
                    cellularDnsCalls.incrementAndGet()
                    return listOf(InetAddress.getByName("127.0.0.1"))
                }
            }
        )

        val factory = OkHttpBoundNetworkClientFactory()
        val wifiClient = factory.getClientFor(wifiAdapter)
        val cellularClient = factory.getClientFor(cellularAdapter)

        assertNotSame(wifiClient, cellularClient)

        val req = Request.Builder().url("http://localhost:$port/file.bin").head().build()
        wifiClient.newCall(req).execute().use { assertEquals(200, it.code) }
        cellularClient.newCall(req).execute().use { assertEquals(200, it.code) }

        assertTrue("Wi-Fi SocketFactory must be invoked", wifiFactory.socketsCreated.get() >= 1)
        assertTrue("Cellular SocketFactory must be invoked", cellularFactory.socketsCreated.get() >= 1)
        assertTrue("Wi-Fi DNS must be invoked", wifiDnsCalls.get() >= 1)
        assertTrue("Cellular DNS must be invoked", cellularDnsCalls.get() >= 1)
    }

    @Test
    fun `probe resolves redirects HEAD headers and RFC5987 filename`() = runTest {
        val factory = OkHttpBoundNetworkClientFactory()
        val probe = ProbeService(factory.getDefaultClient())

        val result = probe.probe("http://127.0.0.1:$port/redirect")
        assertTrue(result is ProbeResult.Success)
        val success = result as ProbeResult.Success
        assertEquals("http://127.0.0.1:$port/file.bin", success.finalUrl)
        assertEquals("Plexo Release 1.0.zip", success.fileName)
        assertEquals(payload.size.toLong(), success.contentLength)
        assertTrue(success.supportsRanges)
        assertEquals("\"etag-v1\"", success.etag)
    }

    @Test
    fun `probe falls back to GET Range 0-0 when HEAD returns 405`() = runTest {
        val factory = OkHttpBoundNetworkClientFactory()
        val probe = ProbeService(factory.getDefaultClient())

        val result = probe.probe("http://127.0.0.1:$port/no-head.iso")
        assertTrue(result is ProbeResult.Success)
        val success = result as ProbeResult.Success
        assertEquals("no-head.iso", success.fileName)
        assertEquals(payload.size.toLong(), success.contentLength)
        assertTrue(success.supportsRanges)
        assertEquals("\"nohead-etag\"", success.etag)
    }

    @Test
    fun `probe marks HTTP 410 as expired link with user readable message`() = runTest {
        val factory = OkHttpBoundNetworkClientFactory()
        val probe = ProbeService(factory.getDefaultClient())

        val result = probe.probe("http://127.0.0.1:$port/expired")
        assertTrue(result is ProbeResult.Failure)
        val failure = result as ProbeResult.Failure
        assertEquals(410, failure.httpCode)
        assertTrue(failure.isExpiredLink)
        assertTrue(failure.userMessage.contains("Link expired", ignoreCase = true))
        assertFalse(failure.userMessage.isBlank())
    }
}
