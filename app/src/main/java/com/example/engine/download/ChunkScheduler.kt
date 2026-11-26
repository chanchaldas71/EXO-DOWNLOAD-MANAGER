package com.example.engine.download

import com.example.data.local.entity.ChunkEntity
import com.example.data.model.ChunkState
import com.example.data.model.NetworkTransportType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * In-memory runtime state for a single byte-range chunk.
 * [endByte] is atomic so a fast idle worker can shrink an in-progress chunk during work-stealing
 * safely without blocking the active stream's read loop.
 */
class MutableChunk(
    val chunkIndex: Int,
    val startByte: Long,
    initialEndByte: Long,
    initialDownloaded: Long = 0L,
    var state: ChunkState = ChunkState.PENDING,
    var assignedNetworkId: String? = null,
    var assignedTransportColorIndex: Int = 0,
    var retryCount: Int = 0,
    var nextRetryAtMs: Long = 0L,
    var lastSpeedBytesPerSec: Long = 0L
) {
    private val endByteAtomic = AtomicLong(initialEndByte)
    private val downloadedAtomic = AtomicLong(initialDownloaded)

    val endByte: Long
        get() = endByteAtomic.get()

    val downloadedBytes: Long
        get() = downloadedAtomic.get()

    val totalLength: Long
        get() = (endByteAtomic.get() - startByte + 1L).coerceAtLeast(0L)

    val currentOffset: Long
        get() = startByte + downloadedAtomic.get()

    val remainingBytes: Long
        get() = (totalLength - downloadedAtomic.get()).coerceAtLeast(0L)

    fun addDownloaded(delta: Long): Long {
        if (delta <= 0L) return downloadedAtomic.get()
        return downloadedAtomic.addAndGet(delta)
    }

    fun updateEndByte(newEndByte: Long) {
        endByteAtomic.set(newEndByte)
    }

    fun isReachedEnd(): Boolean {
        val end = endByteAtomic.get()
        return end >= startByte && (startByte + downloadedAtomic.get()) > end
    }

    fun toEntity(downloadId: Long): ChunkEntity = ChunkEntity(
        downloadId = downloadId,
        chunkIndex = chunkIndex,
        startByte = startByte,
        endByte = endByteAtomic.get(),
        downloadedBytes = downloadedAtomic.get().coerceAtMost(totalLength),
        state = state,
        assignedNetworkId = assignedNetworkId
    )
}

sealed interface AcquireResult {
    data class Acquired(val chunk: MutableChunk, val wasStolen: Boolean = false) : AcquireResult
    data class WaitBackoff(val delayMs: Long) : AcquireResult
    data object AllDone : AcquireResult
    data object NoWorkAvailable : AcquireResult
}

/**
 * Work-stealing chunk scheduler shared across every active network interface.
 *
 * - Faster networks naturally pull more chunks from the shared queue.
 * - When the unassigned queue empties, a fast idle worker splits the slowest in-progress chunk
 *   in half and takes over the back half ([minStealRemainingBytes] threshold).
 * - Tracks consecutive failures per network and parks unstable adapters after
 *   [deadAdapterFailureThreshold] failures, returning their unfinished chunks immediately.
 */
class ChunkScheduler(
    private val downloadId: Long,
    val totalBytes: Long,
    val supportsRanges: Boolean,
    private val chunkSizeBytes: Long = DEFAULT_CHUNK_SIZE_BYTES,
    private val minStealRemainingBytes: Long = MIN_STEAL_REMAINING_BYTES,
    private val deadAdapterFailureThreshold: Int = DEFAULT_DEAD_ADAPTER_THRESHOLD,
    existingChunks: List<ChunkEntity> = emptyList()
) {
    private val mutex = Mutex()
    private val chunks = mutableListOf<MutableChunk>()
    private val nextChunkIndex = AtomicInteger(0)
    private val consecutiveNetworkFailures = mutableMapOf<String, Int>()
    private val parkedNetworkIds = mutableSetOf<String>()

    private val workStealCountAtomic = AtomicInteger(0)
    val workStealCount: Int
        get() = workStealCountAtomic.get()

    init {
        if (existingChunks.isNotEmpty()) {
            existingChunks.sortedBy { it.startByte }.forEach { entity ->
                val restoredState = if (entity.state == ChunkState.COMPLETED) {
                    ChunkState.COMPLETED
                } else {
                    ChunkState.PENDING
                }
                chunks.add(
                    MutableChunk(
                        chunkIndex = entity.chunkIndex,
                        startByte = entity.startByte,
                        initialEndByte = entity.endByte,
                        initialDownloaded = entity.downloadedBytes,
                        state = restoredState,
                        assignedNetworkId = null
                    )
                )
            }
            nextChunkIndex.set((existingChunks.maxOfOrNull { it.chunkIndex } ?: -1) + 1)
        } else {
            initializeChunks()
        }
    }

    private fun initializeChunks() {
        if (!supportsRanges || totalBytes <= 0L) {
            chunks.add(
                MutableChunk(
                    chunkIndex = 0,
                    startByte = 0L,
                    initialEndByte = if (totalBytes > 0L) totalBytes - 1L else -1L,
                    state = ChunkState.PENDING
                )
            )
            nextChunkIndex.set(1)
            return
        }

        val effectiveChunkSize = chunkSizeBytes.coerceAtLeast(MIN_CHUNK_SIZE_BYTES)
        var offset = 0L
        var idx = 0
        while (offset < totalBytes) {
            val end = min(offset + effectiveChunkSize - 1L, totalBytes - 1L)
            chunks.add(
                MutableChunk(
                    chunkIndex = idx,
                    startByte = offset,
                    initialEndByte = end,
                    state = ChunkState.PENDING
                )
            )
            offset = end + 1L
            idx++
        }
        nextChunkIndex.set(idx)
    }

    suspend fun acquireChunk(
        networkId: String,
        transportType: NetworkTransportType,
        nowMs: Long = System.currentTimeMillis()
    ): AcquireResult = mutex.withLock {
        if (parkedNetworkIds.contains(networkId)) {
            return AcquireResult.NoWorkAvailable
        }

        if (chunks.all { it.state == ChunkState.COMPLETED }) {
            return AcquireResult.AllDone
        }

        // 1. Pull next ready PENDING chunk
        val readyPending = chunks
            .filter { it.state == ChunkState.PENDING && it.nextRetryAtMs <= nowMs }
            .minByOrNull { it.startByte }

        if (readyPending != null) {
            readyPending.state = ChunkState.IN_PROGRESS
            readyPending.assignedNetworkId = networkId
            readyPending.assignedTransportColorIndex = transportType.colorIndex
            return AcquireResult.Acquired(readyPending, wasStolen = false)
        }

        // 2. If some PENDING chunks are waiting on backoff, tell worker how long to wait
        val waitingPending = chunks
            .filter { it.state == ChunkState.PENDING && it.nextRetryAtMs > nowMs }
            .minByOrNull { it.nextRetryAtMs }
        if (waitingPending != null) {
            return AcquireResult.WaitBackoff((waitingPending.nextRetryAtMs - nowMs).coerceAtLeast(100L))
        }

        // 3. Work-stealing: queue is empty -> split the slowest in-progress chunk in half
        if (supportsRanges && totalBytes > 0L) {
            val candidate = chunks
                .filter { it.state == ChunkState.IN_PROGRESS && it.remainingBytes >= minStealRemainingBytes }
                .maxByOrNull { chunk ->
                    val speed = chunk.lastSpeedBytesPerSec.coerceAtLeast(1L)
                    // Higher estimated time remaining (or larger remaining bytes if speed unknown)
                    (chunk.remainingBytes.toDouble() / speed.toDouble())
                }

            if (candidate != null) {
                val currentPos = candidate.currentOffset
                val originalEnd = candidate.endByte
                val remaining = originalEnd - currentPos + 1L
                if (remaining >= minStealRemainingBytes) {
                    val splitPoint = currentPos + (remaining / 2L)
                    if (splitPoint in (currentPos + 1L)..originalEnd) {
                        candidate.updateEndByte(splitPoint - 1L)
                        val stolen = MutableChunk(
                            chunkIndex = nextChunkIndex.getAndIncrement(),
                            startByte = splitPoint,
                            initialEndByte = originalEnd,
                            initialDownloaded = 0L,
                            state = ChunkState.IN_PROGRESS,
                            assignedNetworkId = networkId,
                            assignedTransportColorIndex = transportType.colorIndex
                        )
                        chunks.add(stolen)
                        workStealCountAtomic.incrementAndGet()
                        return AcquireResult.Acquired(stolen, wasStolen = true)
                    }
                }
            }
        }

        return if (chunks.all { it.state == ChunkState.COMPLETED }) {
            AcquireResult.AllDone
        } else {
            AcquireResult.NoWorkAvailable
        }
    }

    suspend fun markChunkCompleted(chunk: MutableChunk, networkId: String) = mutex.withLock {
        chunk.state = ChunkState.COMPLETED
        chunk.assignedNetworkId = null
        consecutiveNetworkFailures[networkId] = 0
    }

    suspend fun recordNetworkSuccess(networkId: String) = mutex.withLock {
        consecutiveNetworkFailures[networkId] = 0
    }

    /**
     * Releases an unfinished chunk back to PENDING with exponential backoff + jitter (1s, 2s, 4s... max 60s),
     * and returns `true` if [networkId] has crossed the consecutive failure threshold and is now parked.
     */
    suspend fun releaseChunkOnFailure(
        chunk: MutableChunk,
        networkId: String,
        countTowardsNetworkFailures: Boolean = true,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean = mutex.withLock {
        if (chunk.state == ChunkState.COMPLETED) return false

        chunk.state = ChunkState.PENDING
        chunk.assignedNetworkId = null
        chunk.retryCount += 1
        chunk.nextRetryAtMs = nowMs + computeBackoffMs(chunk.retryCount)

        if (!countTowardsNetworkFailures) return false

        val failures = (consecutiveNetworkFailures[networkId] ?: 0) + 1
        consecutiveNetworkFailures[networkId] = failures
        if (failures >= deadAdapterFailureThreshold) {
            parkedNetworkIds.add(networkId)
            // Also immediately reclaim any other chunks assigned to this dead adapter
            chunks.forEach { other ->
                if (other.assignedNetworkId == networkId && other.state == ChunkState.IN_PROGRESS) {
                    other.state = ChunkState.PENDING
                    other.assignedNetworkId = null
                    other.nextRetryAtMs = nowMs
                }
            }
            return true
        }
        return false
    }

    suspend fun releaseChunkImmediately(chunk: MutableChunk) = mutex.withLock {
        if (chunk.state != ChunkState.COMPLETED) {
            chunk.state = ChunkState.PENDING
            chunk.assignedNetworkId = null
            chunk.nextRetryAtMs = 0L
        }
    }

    /**
     * Called when an adapter unplugs or is disabled mid-download: immediately releases all of its
     * in-progress chunks back to the shared queue with zero backoff.
     */
    suspend fun reclaimChunksForNetwork(networkId: String): Int = mutex.withLock {
        var reclaimed = 0
        chunks.forEach { chunk ->
            if (chunk.assignedNetworkId == networkId && chunk.state == ChunkState.IN_PROGRESS) {
                chunk.state = ChunkState.PENDING
                chunk.assignedNetworkId = null
                chunk.nextRetryAtMs = 0L
                reclaimed++
            }
        }
        reclaimed
    }

    suspend fun unparkNetwork(networkId: String) = mutex.withLock {
        parkedNetworkIds.remove(networkId)
        consecutiveNetworkFailures[networkId] = 0
    }

    suspend fun isNetworkParked(networkId: String): Boolean = mutex.withLock {
        parkedNetworkIds.contains(networkId)
    }

    suspend fun isAllCompleted(): Boolean = mutex.withLock {
        chunks.isNotEmpty() && chunks.all { it.state == ChunkState.COMPLETED }
    }

    suspend fun totalDownloadedBytes(): Long = mutex.withLock {
        chunks.sumOf { chunk ->
            val dl = chunk.downloadedBytes
            if (chunk.endByte >= chunk.startByte) dl.coerceAtMost(chunk.totalLength) else dl
        }
    }

    suspend fun snapshotEntities(): List<ChunkEntity> = mutex.withLock {
        chunks.sortedBy { it.startByte }.map { it.toEntity(downloadId) }
    }

    /**
     * Generates a 192-cell visualization array for the Canvas chunk map:
     * - `0` = Pending
     * - `1` = Completed
     * - `10 + colorIndex` = Actively downloading on transport `colorIndex` (0=Wi-Fi, 1=Cellular, 2=Ethernet, 3=VPN/Other)
     */
    suspend fun buildCellMap(cellCount: Int = CHUNK_MAP_CELL_COUNT): IntArray = mutex.withLock {
        val cells = IntArray(cellCount)
        if (totalBytes <= 0L) {
            val active = chunks.firstOrNull { it.state == ChunkState.IN_PROGRESS }
            if (active != null) {
                val fill = (cellCount / 4).coerceAtLeast(1)
                for (i in 0 until fill) cells[i] = CELL_ACTIVE_BASE + active.assignedTransportColorIndex
            } else if (chunks.all { it.state == ChunkState.COMPLETED }) {
                cells.fill(CELL_COMPLETED)
            }
            return cells
        }

        val bytesPerCell = totalBytes.toDouble() / cellCount.toDouble()
        for (chunk in chunks) {
            val startCell = (chunk.startByte / bytesPerCell).toInt().coerceIn(0, cellCount - 1)
            val endCell = (chunk.endByte / bytesPerCell).toInt().coerceIn(0, cellCount - 1)
            val completedUpToByte = (chunk.startByte + chunk.downloadedBytes - 1L)
                .coerceAtMost(chunk.endByte)

            for (c in startCell..endCell) {
                val cellStart = (c * bytesPerCell).toLong()
                val cellEnd = (((c + 1) * bytesPerCell).toLong() - 1L).coerceAtLeast(cellStart)
                when {
                    chunk.state == ChunkState.COMPLETED || completedUpToByte >= cellEnd -> {
                        cells[c] = CELL_COMPLETED
                    }
                    chunk.state == ChunkState.IN_PROGRESS && completedUpToByte >= cellStart -> {
                        if (cells[c] != CELL_COMPLETED) {
                            cells[c] = CELL_ACTIVE_BASE + chunk.assignedTransportColorIndex
                        }
                    }
                    chunk.state == ChunkState.IN_PROGRESS && cells[c] == CELL_PENDING -> {
                        cells[c] = CELL_ACTIVE_BASE + chunk.assignedTransportColorIndex
                    }
                }
            }
        }
        cells
    }

    companion object {
        const val DEFAULT_CHUNK_SIZE_BYTES = 4L * 1024L * 1024L // 4 MB
        const val MIN_CHUNK_SIZE_BYTES = 64L * 1024L // 64 KB floor for tests/small files
        const val MIN_STEAL_REMAINING_BYTES = 256L * 1024L // 256 KB minimum to split
        const val DEFAULT_DEAD_ADAPTER_THRESHOLD = 4
        const val CHUNK_MAP_CELL_COUNT = 192

        const val CELL_PENDING = 0
        const val CELL_COMPLETED = 1
        const val CELL_ACTIVE_BASE = 10

        fun computeBackoffMs(retryCount: Int): Long {
            val exponent = (retryCount - 1).coerceIn(0, 6)
            val baseMs = (1000.0 * 2.0.pow(exponent.toDouble())).toLong().coerceAtMost(60_000L)
            val jitter = Random.nextLong(0L, (baseMs / 4L).coerceAtLeast(100L))
            return (baseMs + jitter).coerceAtMost(60_000L)
        }
    }
}
