package com.lucasalfare.flvsampler.video

import com.lucasalfare.flvsampler.midi.TimelineEvent
import com.lucasalfare.flvsampler.profiler.Profiler
import com.lucasalfare.flvsampler.util.PAUSE_KEY
import com.lucasalfare.flvsampler.util.sampleKey
import java.io.File
import java.util.*
import kotlin.math.ceil

/**
 * Renders video frames lazily from the individual instrument samples.
 *
 * The renderer never loads complete source videos into memory.
 *
 * For each requested frame:
 *
 * 1. Look for the JPEG in the RAM cache.
 * 2. If absent, ask FFmpeg to extract that frame.
 * 3. Store the resulting JPEG in the LRU cache.
 * 4. Stream the JPEG directly to the final FFmpeg encoder.
 *
 * This allows memory consumption to be controlled independently from the
 * duration of the MIDI composition.
 */
class MemoryVideoRenderer(
  private val fps: Int = 60,
  maxCacheMb: Int = 512
) {
  private val frameCache = RamFrameCache(
    maxBytes = maxCacheMb.toLong() * 1024 * 1024
  )

  init {
    require(fps > 0) {
      "Video FPS must be greater than zero."
    }

    require(maxCacheMb > 0) {
      "Frame cache size must be greater than zero."
    }
  }

  /**
   * Returns a single JPEG frame from a source video, using the RAM cache
   * whenever possible.
   */
  private fun getFrame(
    videoFile: File,
    frameIndex: Int
  ): ByteArray {
    val sourceKey = videoFile.absoluteFile.normalize().path
    val safeFrameIndex = frameIndex.coerceAtLeast(0)

    frameCache.get(sourceKey, safeFrameIndex)?.let {
      return it
    }

    return extractSingleFrame(videoFile, safeFrameIndex).also {
      frameCache.put(sourceKey, safeFrameIndex, it)
    }
  }

  /**
   * Extracts exactly one video frame as an MJPEG/JPEG byte array.
   */
  private fun extractSingleFrame(
    videoFile: File,
    frameIndex: Int
  ): ByteArray {
    require(videoFile.exists()) {
      "Video sample not found: ${videoFile.absolutePath}"
    }

    val timestamp = String.format(
      Locale.US,
      "%.6f",
      frameIndex.toDouble() / fps
    )

    val process = ProcessBuilder(
      "ffmpeg",
      "-loglevel", "error",
      "-ss", timestamp,
      "-i", videoFile.absolutePath,
      "-vf", "scale=1280:720,fps=$fps",
      "-frames:v", "1",
      "-f", "mjpeg",
      "-q:v", "3",
      "pipe:1"
    )
      .redirectError(ProcessBuilder.Redirect.INHERIT)
      .start()

    val bytes = process.inputStream.use { it.readBytes() }
    val exitCode = process.waitFor()

    check(exitCode == 0) {
      "FFmpeg failed to extract frame $frameIndex from ${videoFile.name}."
    }

    check(bytes.isNotEmpty()) {
      "FFmpeg returned an empty frame $frameIndex from ${videoFile.name}."
    }

    return bytes
  }

  /**
   * Renders the final MP4 by streaming generated JPEG frames and the
   * synthesized master audio into FFmpeg.
   *
   * @param timeline complete rendering timeline.
   * @param frameSources logical sample names mapped to source videos.
   * @param masterAudioWav synthesized master audio.
   * @param outputMp4 final MP4 output file.
   */
  fun renderVideo(
    timeline: List<TimelineEvent>,
    frameSources: Map<String, File>,
    masterAudioWav: File,
    outputMp4: File
  ) {
    require(timeline.isNotEmpty()) {
      "Cannot render video from an empty timeline."
    }

    require(masterAudioWav.exists()) {
      "Master audio file not found: ${masterAudioWav.absolutePath}"
    }

    val totalMs = timeline.maxOf { it.start + it.duration }
    val frameDurationMs = 1000.0 / fps
    val totalFrames = ceil(totalMs / frameDurationMs).toInt()

    require(totalFrames > 0) {
      "Timeline does not contain any renderable frames."
    }

    val fallbackSource =
      frameSources[PAUSE_KEY]
        ?: frameSources.values.firstOrNull()
        ?: error("No video sample is available for rendering.")

    /*
     * Keep one fallback frame available for missing samples or gaps.
     * This avoids repeatedly invoking FFmpeg when a source is missing.
     */
    val fallbackFrame = getFrame(fallbackSource, 0)

    val process = ProcessBuilder(
      "ffmpeg",
      "-y",
      "-f", "image2pipe",
      "-vcodec", "mjpeg",
      "-r", fps.toString(),
      "-i", "pipe:0",
      "-i", masterAudioWav.absolutePath,
      "-c:v", "libx264",
      "-preset", "fast",
      "-pix_fmt", "yuv420p",
      "-c:a", "aac",
      "-b:a", "192k",
      "-shortest",
      outputMp4.absolutePath
    )
      .redirectError(ProcessBuilder.Redirect.INHERIT)
      .start()

    println("[VIDEO] Streaming $totalFrames frames...")
    println("[FRAME CACHE] Initial: ${frameCache.stats()}")

    var activeIndex = 0

    process.outputStream.use { pipeOut ->
      for (frameIdx in 0 until totalFrames) {
        val timeMs = (frameIdx * frameDurationMs).toLong()

        /*
         * Advance through timeline events that have already ended.
         *
         * The timeline is ordered, so each event is visited at most once.
         */
        while (
          activeIndex < timeline.size &&
          timeMs >= timeline[activeIndex].start +
          timeline[activeIndex].duration
        ) {
          activeIndex++
        }

        val activeEvent =
          timeline.getOrNull(activeIndex)?.takeIf {
            timeMs >= it.start &&
                timeMs < it.start + it.duration
          }

        val frameBytes = when {
          activeEvent == null -> fallbackFrame

          else -> {
            val source =
              frameSources[activeEvent.sampleKey()]

            if (source == null) {
              fallbackFrame
            } else {
              val sampleFrameIdx =
                ((timeMs - activeEvent.start) * fps / 1000.0)
                  .toInt()

              try {
                getFrame(source, sampleFrameIdx)
              } catch (e: Exception) {
                println()
                println(
                  "[WARNING] Failed to obtain frame " +
                      "$sampleFrameIdx from ${source.name}: " +
                      e.message
                )
                fallbackFrame
              }
            }
          }
        }

        pipeOut.write(frameBytes)

        if (frameIdx > 0 && frameIdx % 1000 == 0) {
          println()
          println("[VIDEO] Frame $frameIdx / $totalFrames")
          Profiler.logMemory("Video rendering")
          println("[FRAME CACHE] ${frameCache.stats()}")
        }
      }

      pipeOut.flush()
    }

    check(process.waitFor() == 0) {
      "FFmpeg video encoding failed."
    }

    println()
    println("[FRAME CACHE] Final: ${frameCache.stats()}")
  }
}