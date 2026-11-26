package com.example.engine.download

import com.example.data.model.NetworkTransportType
import com.example.data.model.ReachabilityState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class NetworkDownloadStats(
    val networkId: String,
    val displayName: String,
    val transportType: NetworkTransportType,
    val status: ReachabilityState,
    val activeStreams: Int,
    val maxStreams: Int,
    val speedBytesPerSec: Long,
    val downloadedBytes: Long,
    val sharePercent: Float
)

data class DownloadLiveMetrics(
    val downloadId: Long,
    val totalSpeedBytesPerSec: Long = 0L,
    val averageSpeedBytesPerSec: Long = 0L,
    val peakSpeedBytesPerSec: Long = 0L,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = -1L,
    val etaSeconds: Long = -1L,
    val activeStreamCount: Int = 0,
    val workStealCount: Int = 0,
    val throughputHistory: List<Long> = List(60) { 0L },
    val chunkCells: IntArray = IntArray(ChunkScheduler.CHUNK_MAP_CELL_COUNT),
    val perNetworkStats: List<NetworkDownloadStats> = emptyList(),
    val sharedUpstreamHint: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DownloadLiveMetrics) return false
        return downloadId == other.downloadId &&
            totalSpeedBytesPerSec == other.totalSpeedBytesPerSec &&
            averageSpeedBytesPerSec == other.averageSpeedBytesPerSec &&
            peakSpeedBytesPerSec == other.peakSpeedBytesPerSec &&
            downloadedBytes == other.downloadedBytes &&
            totalBytes == other.totalBytes &&
            etaSeconds == other.etaSeconds &&
            activeStreamCount == other.activeStreamCount &&
            workStealCount == other.workStealCount &&
            throughputHistory == other.throughputHistory &&
            chunkCells.contentEquals(other.chunkCells) &&
            perNetworkStats == other.perNetworkStats &&
            sharedUpstreamHint == other.sharedUpstreamHint
    }

    override fun hashCode(): Int {
        var result = downloadId.hashCode()
        result = 31 * result + totalSpeedBytesPerSec.hashCode()
        result = 31 * result + downloadedBytes.hashCode()
        result = 31 * result + chunkCells.contentHashCode()
        result = 31 * result + perNetworkStats.hashCode()
        return result
    }
}

/**
 * Tracks smoothed real-time speeds (2-second moving window), 60-second throughput chart history,
 * peak/average speed, ETA, and per-network contribution shares for a running download.
 */
class TelemetryTracker(
    private val downloadId: Long,
    private val totalBytes: Long,
    initialDownloadedBytes: Long
) {
    private class NetworkCounters(
        @Volatile var displayName: String,
        val transportType: NetworkTransportType,
        @Volatile var status: ReachabilityState,
        @Volatile var maxStreams: Int
    ) {
        val activeStreams = AtomicInteger(0)
        val totalBytes = AtomicLong(0L)
        val windowBytes = AtomicLong(0L)
        @Volatile var smoothedSpeedBps: Long = 0L
    }

    private val perNetwork = ConcurrentHashMap<String, NetworkCounters>()
    private val sessionDownloaded = AtomicLong(0L)
    private val baseDownloaded = initialDownloadedBytes
    private val startTimeMs = System.currentTimeMillis()

    private val history = LongArray(60)
    private var lastSampleTimeMs = System.currentTimeMillis()
    private var lastHistoryTickMs = System.currentTimeMillis()
    private var peakSpeedBps = 0L
    private var smoothedTotalSpeedBps = 0L

    fun registerNetwork(
        networkId: String,
        displayName: String,
        transportType: NetworkTransportType,
        status: ReachabilityState,
        maxStreams: Int
    ) {
        val existing = perNetwork[networkId]
        if (existing != null) {
            existing.displayName = displayName
            existing.status = status
            existing.maxStreams = maxStreams
        } else {
            perNetwork[networkId] = NetworkCounters(displayName, transportType, status, maxStreams)
        }
    }

    fun updateNetworkStatus(networkId: String, status: ReachabilityState) {
        perNetwork[networkId]?.status = status
    }

    fun incrementStreams(networkId: String) {
        perNetwork[networkId]?.activeStreams?.incrementAndGet()
    }

    fun decrementStreams(networkId: String) {
        perNetwork[networkId]?.activeStreams?.updateAndGet { (it - 1).coerceAtLeast(0) }
    }

    fun recordBytes(networkId: String, bytes: Int) {
        if (bytes <= 0) return
        sessionDownloaded.addAndGet(bytes.toLong())
        perNetwork[networkId]?.let {
            it.totalBytes.addAndGet(bytes.toLong())
            it.windowBytes.addAndGet(bytes.toLong())
        }
    }

    @Synchronized
    fun sampleSnapshot(
        currentDownloadedTotal: Long,
        workStealCount: Int,
        chunkCells: IntArray
    ): DownloadLiveMetrics {
        val now = System.currentTimeMillis()
        val dtMs = (now - lastSampleTimeMs).coerceAtLeast(100L)
        lastSampleTimeMs = now

        var instantTotal = 0L
        perNetwork.values.forEach { counter ->
            val delta = counter.windowBytes.getAndSet(0L)
            val instantNetSpeed = (delta * 1000L) / dtMs
            // Exponential moving average over ~2 seconds
            counter.smoothedSpeedBps = if (counter.smoothedSpeedBps == 0L) {
                instantNetSpeed
            } else {
                ((counter.smoothedSpeedBps * 65L) + (instantNetSpeed * 35L)) / 100L
            }
            instantTotal += counter.smoothedSpeedBps
        }

        smoothedTotalSpeedBps = instantTotal
        if (smoothedTotalSpeedBps > peakSpeedBps) {
            peakSpeedBps = smoothedTotalSpeedBps
        }

        // Shift 60-second chart history once per second
        if (now - lastHistoryTickMs >= 1000L) {
            val steps = ((now - lastHistoryTickMs) / 1000L).toInt().coerceIn(1, 10)
            lastHistoryTickMs = now
            for (s in 0 until steps) {
                System.arraycopy(history, 1, history, 0, history.size - 1)
                history[history.size - 1] = smoothedTotalSpeedBps
            }
        }

        val elapsedSec = ((now - startTimeMs) / 1000.0).coerceAtLeast(1.0)
        val avgSpeed = (sessionDownloaded.get() / elapsedSec).toLong()

        val effectiveDownloaded = maxOf(currentDownloadedTotal, baseDownloaded + sessionDownloaded.get())
        val etaSeconds = if (totalBytes > 0L && smoothedTotalSpeedBps > 0L) {
            ((totalBytes - effectiveDownloaded).coerceAtLeast(0L)) / smoothedTotalSpeedBps
        } else {
            -1L
        }

        val totalNetworkBytes = perNetwork.values.sumOf { it.totalBytes.get() }.coerceAtLeast(1L)
        val statsList = perNetwork.entries.map { (id, counter) ->
            val dl = counter.totalBytes.get()
            NetworkDownloadStats(
                networkId = id,
                displayName = counter.displayName,
                transportType = counter.transportType,
                status = counter.status,
                activeStreams = counter.activeStreams.get(),
                maxStreams = counter.maxStreams,
                speedBytesPerSec = counter.smoothedSpeedBps,
                downloadedBytes = dl,
                sharePercent = (dl.toFloat() / totalNetworkBytes.toFloat()) * 100f
            )
        }.sortedByDescending { it.speedBytesPerSec }

        val activeCount = statsList.sumOf { it.activeStreams }

        // Detect shared upstream hint if 2+ active Wi-Fi + Ethernet adapters are running but peak didn't rise
        val activeMultiTypes = statsList.filter { it.activeStreams > 0 && it.speedBytesPerSec > 0L }
        val sharedHint = activeMultiTypes.size >= 2 &&
            activeMultiTypes.any { it.transportType == NetworkTransportType.WIFI } &&
            activeMultiTypes.any { it.transportType == NetworkTransportType.ETHERNET } &&
            smoothedTotalSpeedBps > 0L &&
            activeMultiTypes.maxOf { it.speedBytesPerSec } > (smoothedTotalSpeedBps * 0.92).toLong()

        return DownloadLiveMetrics(
            downloadId = downloadId,
            totalSpeedBytesPerSec = smoothedTotalSpeedBps,
            averageSpeedBytesPerSec = avgSpeed,
            peakSpeedBytesPerSec = peakSpeedBps,
            downloadedBytes = effectiveDownloaded,
            totalBytes = totalBytes,
            etaSeconds = etaSeconds,
            activeStreamCount = activeCount,
            workStealCount = workStealCount,
            throughputHistory = history.toList(),
            chunkCells = chunkCells,
            perNetworkStats = statsList,
            sharedUpstreamHint = sharedHint
        )
    }
}
