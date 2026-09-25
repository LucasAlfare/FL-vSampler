package com.lucasalfare.flvsampler.audio

import com.lucasalfare.flvsampler.midi.TimelineEvent
import com.lucasalfare.flvsampler.util.sampleKey
import com.lucasalfare.flvsampler.util.writeAscii
import java.io.BufferedOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*
import kotlin.math.abs
import kotlin.math.cos

/**
 * Synthesizes the audio track by combining the audio extracted from the
 * individual video samples.
 *
 * The complete master audio is never kept in RAM. Instead, the master is
 * processed in configurable chunks.
 *
 * Audio synthesis uses two passes:
 *
 * 1. Render every chunk to determine the global peak.
 * 2. Render every chunk again while applying the normalization scale and
 *    writing the final WAV file directly to disk.
 *
 * Individual instrument samples remain in memory because they are reused
 * throughout the synthesis process.
 */
class AudioSynthesizer(
  private val sampleRate: Int = 48_000,
  private val chunkDurationSeconds: Int = 10
) {
  /**
   * Fade-out duration applied after the requested note duration.
   *
   * This prevents abrupt sample termination and reduces audible clicks.
   */
  private val fadeDurationMs = 15.0

  init {
    require(sampleRate > 0) {
      "Sample rate must be greater than zero."
    }
    require(chunkDurationSeconds > 0) {
      "Audio chunk duration must be greater than zero."
    }
  }

  /**
   * Extracts the audio stream from a video file and converts it into
   * stereo Float32 PCM.
   *
   * @param videoFile source video.
   * @param tempWav temporary WAV file used during extraction.
   */
  fun extractSamplePcm(
    videoFile: File,
    tempWav: File
  ): AudioSample {
    require(videoFile.exists()) {
      "Sample video not found: ${videoFile.absolutePath}"
    }

    runFfmpeg(
      "-y",
      "-i", videoFile.absolutePath,
      "-vn",
      "-ar", sampleRate.toString(),
      "-ac", "2",
      "-c:a", "pcm_s16le",
      tempWav.absolutePath
    )

    val bytes = tempWav.readBytes()
    if (bytes.size < 44) return emptyAudioSample()

    /*
     * WAV files may contain chunks before the actual PCM data.
     * Search for the "data" chunk rather than assuming a fixed offset.
     */
    val dataOffset = findDataChunk(bytes)
    if (dataOffset >= bytes.size) return emptyAudioSample()

    val pcmBytes = bytes.copyOfRange(dataOffset, bytes.size)
    val totalSamples = pcmBytes.size / 4

    val left = FloatArray(totalSamples)
    val right = FloatArray(totalSamples)

    ByteBuffer
      .wrap(pcmBytes)
      .order(ByteOrder.LITTLE_ENDIAN)
      .also { buffer ->
        repeat(totalSamples) { i ->
          left[i] = buffer.short / 32768.0f
          right[i] = buffer.short / 32768.0f
        }
      }

    return AudioSample(left, right)
  }

  /**
   * Synthesizes the complete master audio track.
   *
   * Memory usage is bounded by the configured chunk size rather than
   * the total duration of the MIDI composition.
   *
   * @param timeline rendering timeline.
   * @param samples audio samples indexed by logical sample name.
   * @param outputFile destination WAV file.
   */
  fun synthesize(
    timeline: List<TimelineEvent>,
    samples: Map<String, AudioSample>,
    outputFile: File
  ) {
    require(timeline.isNotEmpty()) {
      "Cannot synthesize audio from an empty timeline."
    }

    val totalMs = timeline.maxOf { it.start + it.duration }
    val totalSamplesLong = (totalMs + 1_000L) * sampleRate / 1_000L

    require(totalSamplesLong <= Int.MAX_VALUE) {
      "Audio is too long for the current buffer implementation: $totalSamplesLong samples."
    }

    val totalSamples = totalSamplesLong.toInt()
    val chunkSamples =
      (chunkDurationSeconds.toLong() * sampleRate)
        .coerceAtMost(totalSamples.toLong())
        .coerceAtLeast(1L)
        .toInt()

    println("[AUDIO] Master duration: ${totalMs / 1000.0}s")
    println("[AUDIO] Total samples: $totalSamples")
    println("[AUDIO] Chunk duration: $chunkDurationSeconds s")
    println("[AUDIO] Samples per chunk: $chunkSamples")

    val masterL = FloatArray(chunkSamples)
    val masterR = FloatArray(chunkSamples)
    val totalChunks = (totalSamples + chunkSamples - 1) / chunkSamples

    // --------------------------------------------------------------------
    // PASS 1 — FIND GLOBAL PEAK
    // --------------------------------------------------------------------

    println()
    println("[AUDIO] Pass 1/2: calculating global peak...")

    var maxPeak = 0.0f
    var chunkStart = 0
    var chunkNumber = 0

    while (chunkStart < totalSamples) {
      val count = minOf(chunkSamples, totalSamples - chunkStart)

      clearBuffers(masterL, masterR, count)

      mixChunk(
        timeline,
        samples,
        masterL,
        masterR,
        chunkStart,
        count
      )

      repeat(count) { i ->
        maxPeak = maxOf(
          maxPeak,
          abs(masterL[i]),
          abs(masterR[i])
        )
      }

      println("[AUDIO] Peak analysis — chunk ${++chunkNumber}/$totalChunks")
      chunkStart += count
    }

    val scale =
      if (maxPeak > NORMALIZATION_PEAK) {
        NORMALIZATION_PEAK / maxPeak
      } else {
        1.0f
      }

    println("[AUDIO] Global peak: $maxPeak")
    println("[AUDIO] Normalization scale: $scale")

    // --------------------------------------------------------------------
    // PASS 2 — RENDER NORMALIZED WAV
    // --------------------------------------------------------------------

    println()
    println("[AUDIO] Pass 2/2: writing normalized WAV...")

    writeWavStreaming(outputFile, totalSamples, sampleRate) { out ->
      var processedSamples = 0
      var currentChunk = 0

      while (processedSamples < totalSamples) {
        val count = minOf(
          chunkSamples,
          totalSamples - processedSamples
        )

        clearBuffers(masterL, masterR, count)

        mixChunk(
          timeline,
          samples,
          masterL,
          masterR,
          processedSamples,
          count
        )

        writeChunkAsPcm16(
          out,
          masterL,
          masterR,
          count,
          scale
        )

        println(
          "[AUDIO] WAV writing — " +
              "chunk ${++currentChunk}/$totalChunks"
        )

        processedSamples += count
      }
    }

    println("[AUDIO] Audio synthesis completed.")
    println("[AUDIO] Output: ${outputFile.absolutePath}")
  }

  /**
   * Mixes only the portion of the timeline that intersects the current
   * audio chunk.
   */
  private fun mixChunk(
    timeline: List<TimelineEvent>,
    samples: Map<String, AudioSample>,
    masterL: FloatArray,
    masterR: FloatArray,
    chunkStartSample: Int,
    chunkSampleCount: Int
  ) {
    val fadeSamples =
      (fadeDurationMs * sampleRate / 1000.0).toInt()

    val chunkEnd = chunkStartSample + chunkSampleCount

    for (event in timeline) {
      val key = event.sampleKey()
      val sample = samples[key] ?: continue

      val startSample =
        (event.start * sampleRate / 1000.0).toInt()

      val durationSamples =
        (event.duration * sampleRate / 1000.0).toInt()

      /*
       * The sample is allowed to continue for the fade duration
       * beyond the requested event duration.
       */
      val maxCopy = minOf(
        sample.left.size,
        durationSamples + fadeSamples
      )

      if (maxCopy <= 0) continue

      val eventEnd = startSample + maxCopy

      /*
       * Skip events that do not intersect this chunk.
       */
      if (
        eventEnd <= chunkStartSample ||
        startSample >= chunkEnd
      ) {
        continue
      }

      val sourceStart = maxOf(0, chunkStartSample - startSample)
      val sourceEnd = minOf(maxCopy, chunkEnd - startSample)

      for (sourceIndex in sourceStart until sourceEnd) {
        val gain =
          if (
            fadeSamples > 0 &&
            sourceIndex >= durationSamples
          ) {
            val progress = (sourceIndex - durationSamples).toFloat() / fadeSamples
            ((1.0 + cos(Math.PI * progress)) * 0.5).toFloat()
          } else {
            1.0f
          }

        val targetIndex = startSample + sourceIndex - chunkStartSample
        masterL[targetIndex] += sample.left[sourceIndex] * gain
        masterR[targetIndex] += sample.right[sourceIndex] * gain
      }
    }
  }

  /**
   * Creates a classic RIFF/WAV PCM16 file and writes its audio payload
   * incrementally.
   *
   * Only the current audio chunk is held in memory.
   */
  private fun writeWavStreaming(
    file: File,
    totalSamples: Int,
    sampleRate: Int,
    block: (BufferedOutputStream) -> Unit
  ) {
    val totalDataLen = totalSamples.toLong() * 4
    val totalSize = totalDataLen + 36

    require(totalDataLen <= 0xFFFFFFFFL) {
      "WAV file is too large for classic RIFF PCM."
    }

    file.outputStream().buffered(64 * 1024).use { out ->
      out.write(
        createWavHeader(
          totalDataLen,
          totalSize,
          sampleRate
        )
      )

      block(out)
    }
  }

  /**
   * Converts one stereo Float32 chunk into interleaved signed PCM16 and
   * writes it directly to the output stream.
   */
  private fun writeChunkAsPcm16(
    out: BufferedOutputStream,
    masterL: FloatArray,
    masterR: FloatArray,
    sampleCount: Int,
    scale: Float
  ) {
    val buffer = ByteBuffer
      .allocate(sampleCount * 4)
      .order(ByteOrder.LITTLE_ENDIAN)

    repeat(sampleCount) { i ->
      buffer.putShort(toPcm16(masterL[i] * scale))
      buffer.putShort(toPcm16(masterR[i] * scale))
    }

    out.write(buffer.array())
  }

  /**
   * Creates the 44-byte WAV header for stereo PCM16 audio.
   */
  private fun createWavHeader(
    totalDataLen: Long,
    totalSize: Long,
    sampleRate: Int
  ): ByteArray {
    val header = ByteArray(44)
    val byteRate = sampleRate.toLong() * 4

    header.writeAscii(0, "RIFF")
    writeIntLE(header, 4, totalSize)
    header.writeAscii(8, "WAVE")
    header.writeAscii(12, "fmt ")

    writeIntLE(header, 16, 16)
    writeShortLE(header, 20, 1)
    writeShortLE(header, 22, 2)
    writeIntLE(header, 24, sampleRate.toLong())
    writeIntLE(header, 28, byteRate)
    writeShortLE(header, 32, 4)
    writeShortLE(header, 34, 16)

    header.writeAscii(36, "data")
    writeIntLE(header, 40, totalDataLen)

    return header
  }

  /**
   * Writes a 32-bit little-endian integer into a byte array.
   */
  private fun writeIntLE(
    array: ByteArray,
    offset: Int,
    value: Long
  ) {
    repeat(4) {
      array[offset + it] = (value shr (it * 8)).toByte()
    }
  }

  /**
   * Writes a 16-bit little-endian integer into a byte array.
   */
  private fun writeShortLE(
    array: ByteArray,
    offset: Int,
    value: Int
  ) {
    array[offset] = value.toByte()
    array[offset + 1] = (value shr 8).toByte()
  }

  /**
   * Executes FFmpeg and fails immediately if the process exits with an
   * error status.
   */
  private fun runFfmpeg(vararg arguments: String) {
    val exitCode = ProcessBuilder(listOf("ffmpeg", "-loglevel", "error") + arguments)
      .inheritIO()
      .start()
      .waitFor()

    check(exitCode == 0) {
      "FFmpeg audio extraction failed with exit code $exitCode."
    }
  }

  private fun findDataChunk(bytes: ByteArray): Int {
    for (i in 0..bytes.size - 8) {
      if (
        bytes[i] == 'd'.code.toByte() &&
        bytes[i + 1] == 'a'.code.toByte() &&
        bytes[i + 2] == 't'.code.toByte() &&
        bytes[i + 3] == 'a'.code.toByte()
      ) {
        return i + 8
      }
    }

    return bytes.size
  }

  private fun emptyAudioSample() =
    AudioSample(FloatArray(0), FloatArray(0))

  private fun clearBuffers(
    left: FloatArray,
    right: FloatArray,
    size: Int
  ) {
    Arrays.fill(left, 0, size, 0.0f)
    Arrays.fill(right, 0, size, 0.0f)
  }

  private fun toPcm16(value: Float): Short =
    (value * 32767.0f).toInt()
      .coerceIn(-32768, 32767)
      .toShort()

  private companion object {
    const val NORMALIZATION_PEAK = 0.95f
  }
}