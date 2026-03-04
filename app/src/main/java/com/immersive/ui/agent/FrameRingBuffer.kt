package com.immersive.ui.agent

/**
 * 固定容量环形缓冲区，用于存储最近 N 帧截图。
 *
 * 可靠性优化：
 * - 使用固定大小 Array 替代 ArrayDeque，避免频繁扩容/缩容产生的 GC 压力
 * - 写指针循环覆盖最旧槽位，零额外分配
 * - snapshot() 返回按时间顺序排列的副本列表
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
     * 返回按时间顺序（最旧在前）排列的帧列表。
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
