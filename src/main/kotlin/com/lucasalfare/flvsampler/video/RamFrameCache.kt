package com.lucasalfare.flvsampler.video

import java.util.LinkedHashMap
import java.util.Locale

/**
 * Thread-safe LRU cache for JPEG video frames.
 *
 * The cache is limited by total byte size rather than number of entries,
 * because individual JPEG frames may have different sizes.
 *
 * When the configured memory limit is reached, the least recently used
 * frames are evicted automatically.
 */
class RamFrameCache(maxBytes: Long) {
  private val maxBytes = maxBytes.coerceAtLeast(1)

  private data class CacheKey(
    val source: String,
    val frameIndex: Int
  )

  private val cache =
    LinkedHashMap<CacheKey, ByteArray>(16, 0.75f, true)

  private var currentBytes = 0L
  private var hits = 0L
  private var misses = 0L
  private var evictions = 0L

  /**
   * Retrieves a cached frame.
   *
   * @return JPEG bytes if the frame is cached, otherwise `null`.
   */
  @Synchronized
  fun get(
    source: String,
    frameIndex: Int
  ): ByteArray? {
    val value = cache[CacheKey(source, frameIndex)]
    if (value != null) hits++ else misses++
    return value
  }

  /**
   * Inserts a frame into the cache.
   *
   * Frames larger than the complete cache capacity are ignored rather than
   * causing an endless eviction cycle.
   */
  @Synchronized
  fun put(
    source: String,
    frameIndex: Int,
    value: ByteArray
  ) {
    val valueSize = value.size.toLong()
    if (valueSize > maxBytes) return

    val key = CacheKey(source, frameIndex)
    cache.remove(key)?.let { currentBytes -= it.size }

    cache[key] = value
    currentBytes += valueSize

    while (currentBytes > maxBytes) {
      val iterator = cache.entries.iterator()
      val eldest = iterator.next()

      currentBytes -= eldest.value.size
      iterator.remove()
      evictions++
    }
  }

  /**
   * Returns human-readable cache statistics.
   */
  @Synchronized
  fun stats(): String =
    String.format(
      Locale.US,
      "Frames=%d | RAM=%.2f/%.2f MB | Hits=%d | Misses=%d | Evictions=%d",
      cache.size,
      currentBytes / MB,
      maxBytes / MB,
      hits,
      misses,
      evictions
    )

  /**
   * Removes all cached frames and resets the current memory usage.
   */
  @Synchronized
  fun clear() {
    cache.clear()
    currentBytes = 0
  }

  private companion object {
    const val MB = 1024.0 * 1024.0
  }
}