package com.example

import com.example.data.model.HashAlgorithm
import com.example.data.model.NetworkTransportType
import com.example.engine.download.AcquireResult
import com.example.engine.download.ChunkScheduler
import com.example.engine.download.DiskWriter
import com.example.engine.download.PositionalPartWriter
import com.example.engine.download.SpeedLimiter
import com.example.engine.network.OkHttpBoundNetworkClientFactory
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class ChunkSchedulerAndDiskWriterTest {

    private lateinit var server: HttpServer
    private var port: Int = 0
    private val testBytes = ByteArray(512 * 1024) { idx -> ((idx * 31 + 7) % 256).toByte() }
    private val requestCounter = AtomicInteger(0)

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newFixedThreadPool(8)
        port = server.address.port

        server.createContext("/bonded.bin") { ex ->
            val reqNum = requestCounter.incrementAndGet()
            // Simulate occasional transient failure on every 7th request
            if (reqNum % 7 == 0) {
                ex.sendResponseHeaders(503, -1)
                ex.close()
                return@createContext
            }

            val rangeHeader = ex.requestHeaders.getFirst("Range")
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                val parts = rangeHeader.removePrefix("bytes=").split("-")
                val start = parts[0].toLong()
                val end = parts[1].toLong().coerceAtMost(testBytes.size.toLong() - 1L)
                val len = (end - start + 1L).toInt()
                ex.responseHeaders.add("Accept-Ranges", "bytes")
                ex.responseHeaders.add("Content-Range", "bytes $start-$end/${testBytes.size}")
                ex.sendResponseHeaders(206, len.toLong())
                ex.responseBody.write(testBytes, start.toInt(), len)
            } else {
                ex.sendResponseHeaders(200, testBytes.size.toLong())
                ex.responseBody.write(testBytes)
            }
            ex.close()
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun `work stealing splits slowest in-progress chunk in half when queue is empty`() = runTest {
        val totalSize = 1024L * 1024L // 1 MB
        val scheduler = ChunkScheduler(
            downloadId = 1L,
            totalBytes = totalSize,
            supportsRanges = true,
            chunkSizeBytes = 1024L * 1024L, // Single initial chunk
            minStealRemainingBytes = 64L * 1024L
        )

        val first = scheduler.acquireChunk("cellular_0", NetworkTransportType.CELLULAR)
        assertTrue(first is AcquireResult.Acquired)
        val slowChunk = (first as AcquireResult.Acquired).chunk
        slowChunk.lastSpeedBytesPerSec = 50_000L

        // Second faster network finds queue empty -> must steal back half
        val second = scheduler.acquireChunk("wifi_0", NetworkTransportType.WIFI)
        assertTrue(second is AcquireResult.Acquired)
        val stolenResult = second as AcquireResult.Acquired
        assertTrue("Expected work-stealing flag", stolenResult.wasStolen)
        assertEquals(1, scheduler.workStealCount)

        // Verify non-overlapping contiguous split
        assertEquals(slowChunk.endByte + 1L, stolenResult.chunk.startByte)
        assertEquals(totalSize - 1L, stolenResult.chunk.endByte)
    }

    @Test
    fun `dead adapter parks after N failures and mid-download unplug reclaims chunks`() = runTest {
        val scheduler = ChunkScheduler(
            downloadId = 2L,
            totalBytes = 512L * 1024L,
            supportsRanges = true,
            chunkSizeBytes = 128L * 1024L,
            deadAdapterFailureThreshold = 3
        )

        val c1 = (scheduler.acquireChunk("flaky_cell", NetworkTransportType.CELLULAR) as AcquireResult.Acquired).chunk
        val c2 = (scheduler.acquireChunk("flaky_cell", NetworkTransportType.CELLULAR) as AcquireResult.Acquired).chunk

        // Simulate 3 consecutive failures on flaky_cell -> adapter must be parked and c2 reclaimed
        assertEquals(false, scheduler.releaseChunkOnFailure(c1, "flaky_cell", nowMs = 1000L))
        val retry1 = (scheduler.acquireChunk("flaky_cell", NetworkTransportType.CELLULAR, nowMs = 100_000L) as AcquireResult.Acquired).chunk
        assertEquals(false, scheduler.releaseChunkOnFailure(retry1, "flaky_cell", nowMs = 100_000L))
        val retry2 = (scheduler.acquireChunk("flaky_cell", NetworkTransportType.CELLULAR, nowMs = 200_000L) as AcquireResult.Acquired).chunk
        val parked = scheduler.releaseChunkOnFailure(retry2, "flaky_cell", nowMs = 200_000L)

        assertTrue("Adapter should be parked after threshold failures", parked)
        assertTrue(scheduler.isNetworkParked("flaky_cell"))

        // Reclaimed c2 is immediately available to surviving Wi-Fi adapter
        val wifiAcquired = scheduler.acquireChunk("wifi_0", NetworkTransportType.WIFI, nowMs = 200_000L)
        assertTrue(wifiAcquired is AcquireResult.Acquired)
        assertEquals(c2.chunkIndex, (wifiAcquired as AcquireResult.Acquired).chunk.chunkIndex)

        // Now simulate Wi-Fi unplugging mid-download -> chunk reclaimed instantly
        val reclaimed = scheduler.reclaimChunksForNetwork("wifi_0")
        assertEquals(1, reclaimed)
    }

    @Test
    fun `parallel multi-stream download with throttling and mid-download failures matches SHA-256`() = runBlocking<Unit> {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "plexo_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        val partFile = File(tempDir, "bonded.bin.plexo.part")

        val scheduler = ChunkScheduler(
            downloadId = 3L,
            totalBytes = testBytes.size.toLong(),
            supportsRanges = true,
            chunkSizeBytes = 64L * 1024L,
            minStealRemainingBytes = 32L * 1024L,
            deadAdapterFailureThreshold = 10
        )
        val limiter = SpeedLimiter().apply { setLimitBytesPerSec(2L * 1024L * 1024L) } // 2 MB/s throttle
        val client = OkHttpBoundNetworkClientFactory().getDefaultClient()
        val writer = PositionalPartWriter(partFile, testBytes.size.toLong())

        try {
            coroutineScope {
                val workers = listOf("wifi_0" to NetworkTransportType.WIFI, "eth_0" to NetworkTransportType.ETHERNET)
                    .flatMap { (netId, type) ->
                        (0 until 2).map {
                            async(Dispatchers.IO) {
                                val buf = ByteArray(16 * 1024)
                                while (true) {
                                    when (val res = scheduler.acquireChunk(netId, type)) {
                                        is AcquireResult.AllDone -> break
                                        is AcquireResult.WaitBackoff -> delay(50L)
                                        is AcquireResult.NoWorkAvailable -> delay(30L)
                                        is AcquireResult.Acquired -> {
                                            val chunk = res.chunk
                                            try {
                                                val req = Request.Builder()
                                                    .url("http://127.0.0.1:$port/bonded.bin")
                                                    .header("Range", "bytes=${chunk.currentOffset}-${chunk.endByte}")
                                                    .build()
                                                client.newCall(req).execute().use { resp ->
                                                    if (resp.code != 206) error("Transient HTTP ${resp.code}")
                                                    val stream = resp.body!!.byteStream()
                                                    var pos = chunk.currentOffset
                                                    while (pos <= chunk.endByte) {
                                                        val maxRead = minOf(buf.size.toLong(), chunk.endByte - pos + 1L).toInt()
                                                        val n = stream.read(buf, 0, maxRead)
                                                        if (n <= 0) break
                                                        limiter.acquire(n)
                                                        val clamped = minOf(n.toLong(), (chunk.endByte - pos + 1L).coerceAtLeast(0L)).toInt()
                                                        if (clamped > 0) {
                                                            writer.writeAt(pos, buf, 0, clamped)
                                                            pos += clamped
                                                            chunk.addDownloaded(clamped.toLong())
                                                        }
                                                    }
                                                }
                                                scheduler.markChunkCompleted(chunk, netId)
                                            } catch (_: Exception) {
                                                // Immediately requeue with tiny backoff for test speed
                                                scheduler.releaseChunkImmediately(chunk)
                                                delay(10L)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                workers.awaitAll()
            }
        } finally {
            writer.close()
        }

        assertTrue(scheduler.isAllCompleted())
        val assembled = partFile.readBytes()
        assertArrayEquals("Assembled file must match source byte-for-byte", testBytes, assembled)

        val expectedSha = MessageDigest.getInstance("SHA-256").digest(testBytes).joinToString("") { "%02x".format(it) }
        val diskWriter = DiskWriter(null)
        assertTrue(diskWriter.verifyChecksum(partFile, expectedSha, HashAlgorithm.SHA256))

        tempDir.deleteRecursively()
    }
}
