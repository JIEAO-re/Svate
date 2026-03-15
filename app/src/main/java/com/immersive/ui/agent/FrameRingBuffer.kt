package com.immersive.ui.agent

/**
 * Fixed-capacity ring buffer that stores the most recent N screenshot frames.
 *
 * Reliability optimizations:
 * - Use a fixed-size Array instead of ArrayDeque to avoid resize-related GC pressure
 * - Overwrite the oldest slot in a circular manner with zero extra allocations
 * - snapshot() returns a copy ordered by time
 */
class FrameRingBuffer(
    private val maxSize: Int = 6,
) {
    private val ring = arrayOfNulls<CapturedFrame>(maxSize)
    private var writeIndex = 0
    private var count = 0

    @Synchronized
    fun push(frame: CapturedFrame) {
        ring[writeIndex] = frame
        writeIndex = (writeIndex + 1) % maxSize
        if (count < maxSize) count++
    }

    /**
     * Return frames ordered by time, with the oldest entry first.
     */
    @Synchronized
    fun snapshot(): List<CapturedFrame> {
        if (count == 0) return emptyList()
        val result = ArrayList<CapturedFrame>(count)
        val startIndex = if (count < maxSize) 0 else writeIndex
        for (i in 0 until count) {
            val idx = (startIndex + i) % maxSize
            ring[idx]?.let { result.add(it) }
        }
        return result
    }

    @Synchronized
    fun latest(): CapturedFrame? {
        if (count == 0) return null
        val idx = (writeIndex - 1 + maxSize) % maxSize
        return ring[idx]
    }

    @Synchronized
    fun size(): Int = count

    @Synchronized
    fun clear() {
        ring.fill(null)
        writeIndex = 0
        count = 0
    }
}
