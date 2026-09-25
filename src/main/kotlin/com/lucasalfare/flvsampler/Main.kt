package com.lucasalfare.flvsampler

import com.lucasalfare.flmidi.Event
import com.lucasalfare.flmidi.MidiReader
import com.lucasalfare.flmidi.NoteOffControlEvent
import com.lucasalfare.flmidi.NoteOnControlEvent
import com.lucasalfare.flmidi.SetTempoMetaEvent
import java.io.BufferedOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays
import java.util.LinkedHashMap
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.system.measureTimeMillis

// ============================================================================
// PROFILER
// ============================================================================

/**
 * Small utility used to measure execution time and JVM memory consumption
 * during the rendering pipeline.
 *
 * The profiler is intentionally simple and has no effect on the rendering
 * architecture itself.
 */
object Profiler {

  /**
   * Prints the current JVM heap usage.
   *
   * @param tag descriptive label identifying the current operation.
   */
  fun logMemory(tag: String) {
    val runtime = Runtime.getRuntime()
    val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
    val maxMb = runtime.maxMemory() / (1024 * 1024)

    println(
      "[PROFILER] Memory — $tag: " +
          "$usedMb MB used / $maxMb MB JVM maximum"
    )
  }

  /**
   * Measures the execution time and memory usage of an operation.
   *
   * @param tag descriptive label for the operation.
   * @param block operation to execute.
   * @return the value returned by [block].
   */
  inline fun <T> measure(tag: String, block: () -> T): T {
    println()
    println("[PROFILER] Starting: $tag")

    logMemory("Before $tag")

    var result: T

    val elapsed = measureTimeMillis {
      result = block()
    }

    logMemory("After $tag")

    println("[PROFILER] Completed: $tag in " + "$elapsed ms (${elapsed / 1000.0}s)")

    return result
  }
}

// ============================================================================
// DOMAIN MODELS AND MIDI PARSING
// ============================================================================

/**
 * Represents a MIDI note after being converted from MIDI ticks into
 * milliseconds.
 *
 * @property note musical note name, such as `C4` or `F#3`.
 * @property velocity MIDI velocity associated with the note-on event.
 * @property start note start position in milliseconds.
 * @property duration note duration in milliseconds.
 */
data class NoteEvent(
  val note: String,
  val velocity: Int,
  val start: Long,
  val duration: Long
)

/**
 * Reads MIDI files and converts note events into time-based [NoteEvent]
 * objects.
 *
 * The reader handles:
 *
 * - multiple MIDI tracks;
 * - delta-time accumulation;
 * - note-on events;
 * - note-off events;
 * - note-on events with velocity zero;
 * - tempo changes;
 * - conversion from MIDI ticks to milliseconds.
 */
class MidiEventReader {

  /**
   * Reads all playable note events from a MIDI file.
   *
   * @param file MIDI input file.
   * @return note events sorted by their start time.
   */
  fun read(file: File): List<NoteEvent> {
    require(file.exists()) { "MIDI file not found: ${file.absolutePath}" }
    val midi = MidiReader.fromFile(file.path)
    require(midi.division > 0) { "Invalid MIDI time division: ${midi.division}" }

    val events = mutableListOf<TimedEvent>()

    for (track in midi.tracks) {
      var tick = 0L

      for (event in track) {
        tick += event.deltaTime
        events += TimedEvent(tick, event)
      }
    }

    events.sortBy { it.tick }

    val tempos = events
      .filter { it.event is SetTempoMetaEvent }
      .map {
        TempoEvent(
          tick = it.tick,
          tempo = (it.event as SetTempoMetaEvent).tempo
        )
      }
      .toMutableList()

    /*
     * MIDI files commonly start with 500,000 microseconds per quarter
     * note (120 BPM). If the file does not explicitly define a tempo
     * at tick zero, use that standard default.
     */
    if (tempos.none { it.tick == 0L }) {
      tempos += TempoEvent(tick = 0L, tempo = 500_000)
    }

    tempos.sortBy { it.tick }

    /*
     * Multiple overlapping instances of the same note/channel are
     * supported by keeping a queue of active note starts.
     */
    val activeNotes =
      mutableMapOf<Pair<Int, Int>, MutableList<StartedNote>>()

    val result = mutableListOf<NoteEvent>()

    for (timedEvent in events) {
      when (val event = timedEvent.event) {
        is NoteOnControlEvent -> {
          if (event.velocity == 0) {
            finishNote(
              channel = event.channel,
              note = event.note,
              endTick = timedEvent.tick,
              activeNotes = activeNotes,
              tempos = tempos,
              division = midi.division,
              result = result
            )
          } else {
            activeNotes
              .getOrPut(event.channel to event.note) {
                mutableListOf()
              }
              .add(
                StartedNote(
                  tick = timedEvent.tick,
                  velocity = event.velocity
                )
              )
          }
        }

        is NoteOffControlEvent -> {
          finishNote(
            channel = event.channel,
            note = event.note,
            endTick = timedEvent.tick,
            activeNotes = activeNotes,
            tempos = tempos,
            division = midi.division,
            result = result
          )
        }
      }
    }

    return result.sortedBy { it.start }
  }

  /**
   * Closes the oldest active instance of a MIDI note.
   */
  private fun finishNote(
    channel: Int,
    note: Int,
    endTick: Long,
    activeNotes: MutableMap<Pair<Int, Int>, MutableList<StartedNote>>,
    tempos: List<TempoEvent>,
    division: Int,
    result: MutableList<NoteEvent>
  ) {
    val key = channel to note
    val notes = activeNotes[key] ?: return

    if (notes.isEmpty()) {
      return
    }

    val started = notes.removeAt(0)

    val start = ticksToMillis(
      targetTick = started.tick,
      tempos = tempos,
      division = division
    )

    val end = ticksToMillis(
      targetTick = endTick,
      tempos = tempos,
      division = division
    )

    val duration = (end - start).coerceAtLeast(0L)

    result += NoteEvent(
      note = midiNoteName(note),
      velocity = started.velocity,
      start = start,
      duration = duration
    )

    if (notes.isEmpty()) {
      activeNotes.remove(key)
    }
  }

  /**
   * Converts a MIDI tick position into milliseconds while accounting for
   * all tempo changes occurring before the target position.
   */
  private fun ticksToMillis(
    targetTick: Long,
    tempos: List<TempoEvent>,
    division: Int
  ): Long {
    var currentTick = 0L
    var tempo = 500_000L
    var microseconds = 0L

    for (change in tempos) {
      if (change.tick > targetTick) {
        break
      }

      microseconds += (change.tick - currentTick) * tempo / division
      currentTick = change.tick
      tempo = change.tempo.toLong()
    }

    microseconds += (targetTick - currentTick) * tempo / division

    return microseconds / 1_000L
  }

  /**
   * Converts a MIDI note number to scientific pitch notation.
   *
   * For example:
   *
   * `60 -> C4`
   * `61 -> C#4`
   */
  private fun midiNoteName(note: Int): String {
    val names = listOf(
      "C",
      "C#",
      "D",
      "D#",
      "E",
      "F",
      "F#",
      "G",
      "G#",
      "A",
      "A#",
      "B"
    )

    return names[note % 12] + (note / 12 - 1)
  }

  private data class TimedEvent(
    val tick: Long,
    val event: Event
  )

  private data class TempoEvent(
    val tick: Long,
    val tempo: Int
  )

  private data class StartedNote(
    val tick: Long,
    val velocity: Int
  )
}

// ============================================================================
// TIMELINE
// ============================================================================

/**
 * Common interface for all events occupying time in the rendered output.
 */
sealed interface TimelineEvent {
  /**
   * Event start position in milliseconds.
   */
  val start: Long

  /**
   * Event duration in milliseconds.
   */
  val duration: Long
}

/**
 * Timeline representation of a musical note.
 */
data class TimelineNote(
  val event: NoteEvent
) : TimelineEvent {
  override val start: Long = event.start
  override val duration: Long = event.duration
}

/**
 * Timeline representation of a silent or pause interval.
 */
data class TimelinePause(
  val durationMs: Long,
  override val start: Long
) : TimelineEvent {
  override val duration: Long = durationMs
}

/**
 * Converts MIDI note events into a continuous rendering timeline.
 *
 * Explicit pause events are inserted wherever there is no active note.
 *
 * Overlapping notes are preserved and are therefore not converted into
 * additional pauses.
 */
class MidiTimeline {

  /**
   * Creates the timeline used by both the audio synthesizer and video
   * renderer.
   *
   * @param notes MIDI note events.
   * @return ordered timeline containing notes and pauses.
   */
  fun create(notes: List<NoteEvent>): List<TimelineEvent> {
    if (notes.isEmpty()) {
      return emptyList()
    }

    val timeline = mutableListOf<TimelineEvent>()
    var position = 0L

    for (note in notes.sortedBy { it.start }) {
      if (note.start > position) {
        timeline += TimelinePause(
          durationMs = note.start - position,
          start = position
        )
      }

      timeline += TimelineNote(note)

      position = maxOf(
        position,
        note.start + note.duration
      )
    }

    return timeline
  }
}

// ============================================================================
// AUDIO SYNTHESIS
// ============================================================================

/**
 * Stereo floating-point PCM audio sample.
 *
 * Each element represents one sample frame, with the corresponding left
 * and right channel values stored separately.
 */
class AudioSample(
  val left: FloatArray,
  val right: FloatArray
)

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
      "-i",
      videoFile.absolutePath,
      "-vn",
      "-ar",
      sampleRate.toString(),
      "-ac",
      "2",
      "-c:a",
      "pcm_s16le",
      tempWav.absolutePath
    )

    val bytes = tempWav.readBytes()

    if (bytes.size < 44) {
      return AudioSample(
        left = FloatArray(0),
        right = FloatArray(0)
      )
    }

    /*
     * WAV files may contain chunks before the actual PCM data.
     * Search for the "data" chunk rather than assuming a fixed offset.
     */
    var dataOffset = 44

    for (i in 0 until bytes.size - 4) {
      if (
        bytes[i + 0] == 'd'.code.toByte() &&
        bytes[i + 1] == 'a'.code.toByte() &&
        bytes[i + 2] == 't'.code.toByte() &&
        bytes[i + 3] == 'a'.code.toByte()
      ) {
        dataOffset = i + 8
        break
      }
    }

    if (dataOffset >= bytes.size) {
      return AudioSample(
        left = FloatArray(0),
        right = FloatArray(0)
      )
    }

    val pcmBytes = bytes.copyOfRange(
      dataOffset,
      bytes.size
    )

    val totalSamples = pcmBytes.size / 4
    val left = FloatArray(totalSamples)
    val right = FloatArray(totalSamples)

    val buffer = ByteBuffer
      .wrap(pcmBytes)
      .order(ByteOrder.LITTLE_ENDIAN)

    for (i in 0 until totalSamples) {
      left[i] = buffer.short / 32768.0f
      right[i] = buffer.short / 32768.0f
    }

    return AudioSample(
      left = left,
      right = right
    )
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
    if (timeline.isEmpty()) {
      error("Cannot synthesize audio from an empty timeline.")
    }

    val totalMs = timeline.maxOfOrNull {
      it.start + it.duration
    } ?: 0L

    val totalSamplesLong =
      (totalMs + 1000L) * sampleRate / 1000L

    require(totalSamplesLong <= Int.MAX_VALUE) {
      "Audio is too long for the current buffer implementation: " +
          "$totalSamplesLong samples."
    }

    val totalSamples = totalSamplesLong.toInt()

    val chunkSamples =
      (chunkDurationSeconds.toLong() * sampleRate)
        .coerceAtLeast(1L)
        .coerceAtMost(totalSamples.toLong())
        .toInt()

    println("[AUDIO] Master duration: ${totalMs / 1000.0}s")
    println("[AUDIO] Total samples: $totalSamples")
    println("[AUDIO] Chunk duration: $chunkDurationSeconds s")
    println("[AUDIO] Samples per chunk: $chunkSamples")

    val masterL = FloatArray(chunkSamples)
    val masterR = FloatArray(chunkSamples)

    // --------------------------------------------------------------------
    // PASS 1 — FIND GLOBAL PEAK
    // --------------------------------------------------------------------

    println()
    println("[AUDIO] Pass 1/2: calculating global peak...")

    var maxPeak = 0.0f
    var chunkStartSample = 0
    var chunkNumber = 0

    val totalChunks = ceil(
      totalSamples.toDouble() / chunkSamples.toDouble()
    ).toInt()

    while (chunkStartSample < totalSamples) {
      val chunkSampleCount = minOf(
        chunkSamples,
        totalSamples - chunkStartSample
      )

      Arrays.fill(
        masterL,
        0,
        chunkSampleCount,
        0.0f
      )

      Arrays.fill(
        masterR,
        0,
        chunkSampleCount,
        0.0f
      )

      mixChunk(
        timeline = timeline,
        samples = samples,
        masterL = masterL,
        masterR = masterR,
        chunkStartSample = chunkStartSample,
        chunkSampleCount = chunkSampleCount
      )

      for (i in 0 until chunkSampleCount) {
        maxPeak = maxOf(
          maxPeak,
          abs(masterL[i]),
          abs(masterR[i])
        )
      }

      chunkNumber++

      println(
        "[AUDIO] Peak analysis — " +
            "chunk $chunkNumber/$totalChunks"
      )

      chunkStartSample += chunkSampleCount
    }

    val scale = if (maxPeak > 0.95f) {
      0.95f / maxPeak
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

    writeWavStreaming(
      file = outputFile,
      totalSamples = totalSamples,
      sampleRate = sampleRate
    ) { out ->
      var processedSamples = 0
      var currentChunk = 0

      while (processedSamples < totalSamples) {
        val chunkSampleCount = minOf(
          chunkSamples,
          totalSamples - processedSamples
        )

        Arrays.fill(
          masterL,
          0,
          chunkSampleCount,
          0.0f
        )

        Arrays.fill(
          masterR,
          0,
          chunkSampleCount,
          0.0f
        )

        mixChunk(
          timeline = timeline,
          samples = samples,
          masterL = masterL,
          masterR = masterR,
          chunkStartSample = processedSamples,
          chunkSampleCount = chunkSampleCount
        )

        writeChunkAsPcm16(
          out = out,
          masterL = masterL,
          masterR = masterR,
          sampleCount = chunkSampleCount,
          scale = scale
        )

        currentChunk++

        println(
          "[AUDIO] WAV writing — " +
              "chunk $currentChunk/$totalChunks"
        )

        processedSamples += chunkSampleCount
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

    val chunkEndSampleExclusive =
      chunkStartSample + chunkSampleCount

    for (event in timeline) {
      val key = when (event) {
        is TimelineNote -> event.event.note
        is TimelinePause -> "__pause__"
      }

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

      if (maxCopy <= 0) {
        continue
      }

      val eventEndSampleExclusive =
        startSample + maxCopy

      /*
       * Skip events that do not intersect this chunk.
       */
      if (eventEndSampleExclusive <= chunkStartSample) {
        continue
      }

      if (startSample >= chunkEndSampleExclusive) {
        continue
      }

      val sourceStart = maxOf(
        0,
        chunkStartSample - startSample
      )

      val sourceEnd = minOf(
        maxCopy,
        chunkEndSampleExclusive - startSample
      )

      for (sourceIndex in sourceStart until sourceEnd) {
        var gain = 1.0f

        if (
          fadeSamples > 0 &&
          sourceIndex >= durationSamples
        ) {
          val progress =
            (sourceIndex - durationSamples).toFloat() /
                fadeSamples

          gain = (
              0.5 *
                  (
                      1.0 +
                          cos(Math.PI * progress)
                      )
              ).toFloat()
        }

        val targetIndex =
          startSample +
              sourceIndex -
              chunkStartSample

        masterL[targetIndex] +=
          sample.left[sourceIndex] * gain

        masterR[targetIndex] +=
          sample.right[sourceIndex] * gain
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
    val totalDataLen = totalSamples.toLong() * 4L
    val totalSize = totalDataLen + 36L

    require(totalDataLen <= 0xFFFFFFFFL) {
      "WAV file is too large for classic RIFF PCM."
    }

    require(totalSize <= 0xFFFFFFFFL) {
      "WAV file is too large for classic RIFF PCM."
    }

    file.outputStream().buffered().use { rawOut ->
      val out = BufferedOutputStream(
        rawOut,
        64 * 1024
      )

      val header = createWavHeader(
        totalDataLen = totalDataLen,
        totalSize = totalSize,
        sampleRate = sampleRate
      )

      out.write(header)
      block(out)
      out.flush()
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

    for (i in 0 until sampleCount) {
      val left = (masterL[i] * scale * 32767.0f).toInt()
        .coerceIn(-32768, 32767)
        .toShort()

      val right = (masterR[i] * scale * 32767.0f).toInt()
        .coerceIn(-32768, 32767)
        .toShort()

      buffer.putShort(left)
      buffer.putShort(right)
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
    val byteRate = sampleRate.toLong() * 4L

    header[0] = 'R'.code.toByte()
    header[1] = 'I'.code.toByte()
    header[2] = 'F'.code.toByte()
    header[3] = 'F'.code.toByte()

    writeIntLE(header, 4, totalSize)

    header[8] = 'W'.code.toByte()
    header[9] = 'A'.code.toByte()
    header[10] = 'V'.code.toByte()
    header[11] = 'E'.code.toByte()

    header[12] = 'f'.code.toByte()
    header[13] = 'm'.code.toByte()
    header[14] = 't'.code.toByte()
    header[15] = ' '.code.toByte()

    writeIntLE(header, 16, 16)
    writeShortLE(header, 20, 1)
    writeShortLE(header, 22, 2)
    writeIntLE(header, 24, sampleRate.toLong())
    writeIntLE(header, 28, byteRate)
    writeShortLE(header, 32, 4)
    writeShortLE(header, 34, 16)

    header[36] = 'd'.code.toByte()
    header[37] = 'a'.code.toByte()
    header[38] = 't'.code.toByte()
    header[39] = 'a'.code.toByte()

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
    array[offset] = (value and 0xff).toByte()
    array[offset + 1] = ((value shr 8) and 0xff).toByte()
    array[offset + 2] = ((value shr 16) and 0xff).toByte()
    array[offset + 3] = ((value shr 24) and 0xff).toByte()
  }

  /**
   * Writes a 16-bit little-endian integer into a byte array.
   */
  private fun writeShortLE(
    array: ByteArray,
    offset: Int,
    value: Int
  ) {
    array[offset] = (value and 0xff).toByte()
    array[offset + 1] = ((value shr 8) and 0xff).toByte()
  }

  /**
   * Executes FFmpeg and fails immediately if the process exits with an
   * error status.
   */
  private fun runFfmpeg(vararg arguments: String) {
    val command = listOf(
      "ffmpeg",
      "-loglevel",
      "error"
    ) + arguments

    val process = ProcessBuilder(command)
      .inheritIO()
      .start()

    val exitCode = process.waitFor()

    check(exitCode == 0) {
      "FFmpeg audio extraction failed with exit code $exitCode."
    }
  }
}

// ============================================================================
// CONTROLLED RAM FRAME CACHE
// ============================================================================

/**
 * Thread-safe LRU cache for JPEG video frames.
 *
 * The cache is limited by total byte size rather than number of entries,
 * because individual JPEG frames may have different sizes.
 *
 * When the configured memory limit is reached, the least recently used
 * frames are evicted automatically.
 */
class RamFrameCache(
  maxBytes: Long
) {
  private val maxBytes = maxBytes.coerceAtLeast(1L)

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
    val key = CacheKey(source = source, frameIndex = frameIndex)
    val value = cache[key]
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

    if (valueSize > maxBytes) {
      return
    }

    val key = CacheKey(
      source = source,
      frameIndex = frameIndex
    )

    val previous = cache.remove(key)

    if (previous != null) {
      currentBytes -= previous.size.toLong()
    }

    cache[key] = value
    currentBytes += valueSize

    while (
      currentBytes > maxBytes &&
      cache.isNotEmpty()
    ) {
      val iterator = cache.entries.iterator()
      val eldest = iterator.next()

      currentBytes -= eldest.value.size.toLong()
      iterator.remove()

      evictions++
    }
  }

  /**
   * Returns human-readable cache statistics.
   */
  @Synchronized
  fun stats(): String {
    val currentMb = currentBytes / (1024.0 * 1024.0)
    val maxMb = maxBytes / (1024.0 * 1024.0)

    return String.format(
      Locale.US,
      "Frames=%d | RAM=%.2f/%.2f MB | " +
          "Hits=%d | Misses=%d | Evictions=%d",
      cache.size,
      currentMb,
      maxMb,
      hits,
      misses,
      evictions
    )
  }

  /**
   * Removes all cached frames and resets the current memory usage.
   */
  @Synchronized
  fun clear() {
    cache.clear()
    currentBytes = 0L
  }
}

// ============================================================================
// LAZY VIDEO FRAME RENDERER
// ============================================================================

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
    maxBytes = maxCacheMb.toLong() * 1024L * 1024L
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
    val sourceKey = videoFile.absoluteFile
      .normalize()
      .path

    val safeFrameIndex = frameIndex.coerceAtLeast(0)

    val cached = frameCache.get(
      source = sourceKey,
      frameIndex = safeFrameIndex
    )

    if (cached != null) {
      return cached
    }

    val frame = extractSingleFrame(
      videoFile = videoFile,
      frameIndex = safeFrameIndex
    )

    frameCache.put(
      source = sourceKey,
      frameIndex = safeFrameIndex,
      value = frame
    )

    return frame
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

    val timestampSeconds =
      frameIndex.toDouble() / fps.toDouble()

    val timestamp = String.format(
      Locale.US,
      "%.6f",
      timestampSeconds
    )

    val command = listOf(
      "ffmpeg",
      "-loglevel",
      "error",
      "-ss",
      timestamp,
      "-i",
      videoFile.absolutePath,
      "-vf",
      "scale=1280:720,fps=$fps",
      "-frames:v",
      "1",
      "-f",
      "mjpeg",
      "-q:v",
      "3",
      "pipe:1"
    )

    val process = ProcessBuilder(command)
      .redirectError(
        ProcessBuilder.Redirect.INHERIT
      )
      .start()

    val bytes = process.inputStream.use {
      it.readBytes()
    }

    val exitCode = process.waitFor()

    check(exitCode == 0) {
      "FFmpeg failed to extract frame " +
          "$frameIndex from ${videoFile.name}."
    }

    check(bytes.isNotEmpty()) {
      "FFmpeg returned an empty frame " +
          "$frameIndex from ${videoFile.name}."
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
      "Master audio file not found: " +
          masterAudioWav.absolutePath
    }

    val totalMs = timeline.maxOfOrNull {
      it.start + it.duration
    } ?: 0L

    val frameDurationMs = 1000.0 / fps

    val totalFrames = ceil(
      totalMs / frameDurationMs
    ).toInt()

    require(totalFrames > 0) {
      "Timeline does not contain any renderable frames."
    }

    val pauseSource = frameSources["__pause__"]

    val fallbackSource =
      pauseSource
        ?: frameSources.values.firstOrNull()
        ?: error(
          "No video sample is available for rendering."
        )

    /*
     * Keep one fallback frame available for missing samples or gaps.
     * This avoids repeatedly invoking FFmpeg when a source is missing.
     */
    val fallbackFrame = getFrame(
      videoFile = fallbackSource,
      frameIndex = 0
    )

    val ffmpegCommand = listOf(
      "ffmpeg",
      "-y",
      "-f",
      "image2pipe",
      "-vcodec",
      "mjpeg",
      "-r",
      fps.toString(),
      "-i",
      "pipe:0",
      "-i",
      masterAudioWav.absolutePath,
      "-c:v",
      "libx264",
      "-preset",
      "fast",
      "-pix_fmt",
      "yuv420p",
      "-c:a",
      "aac",
      "-b:a",
      "192k",
      "-shortest",
      outputMp4.absolutePath
    )

    val process = ProcessBuilder(ffmpegCommand)
      .redirectError(
        ProcessBuilder.Redirect.INHERIT
      )
      .start()

    val pipeOut = process.outputStream

    println("[VIDEO] Streaming $totalFrames frames...")
    println("[FRAME CACHE] Initial: ${frameCache.stats()}")

    var activeIndex = 0

    for (frameIdx in 0 until totalFrames) {
      val timeMs = (
          frameIdx *
              frameDurationMs
          ).toLong()

      /*
       * Advance through timeline events that have already ended.
       *
       * The timeline is ordered, so each event is visited at most once.
       */
      while (
        activeIndex < timeline.size &&
        timeMs >= (timeline[activeIndex].start + timeline[activeIndex].duration)
      ) {
        activeIndex++
      }

      val activeEvent = timeline
        .getOrNull(activeIndex)
        ?.takeIf {
          timeMs >= it.start && timeMs < (it.start + it.duration)
        }

      val frameBytes: ByteArray

      if (activeEvent == null) {
        frameBytes = fallbackFrame
      } else {
        val key = when (activeEvent) {
          is TimelineNote -> activeEvent.event.note
          is TimelinePause -> "__pause__"
        }

        val source = frameSources[key]

        if (source == null) {
          frameBytes = fallbackFrame
        } else {
          val offsetMs = timeMs - activeEvent.start

          val sampleFrameIdx = (offsetMs * fps / 1000.0).toInt()

          frameBytes = try {
            getFrame(
              videoFile = source,
              frameIndex = sampleFrameIdx
            )
          } catch (e: Exception) {
            println()
            println(
              "[WARNING] Failed to obtain " +
                  "frame $sampleFrameIdx " +
                  "from ${source.name}: " +
                  e.message
            )

            fallbackFrame
          }
        }
      }

      pipeOut.write(frameBytes)

      if (
        frameIdx > 0 &&
        frameIdx % 1000 == 0
      ) {
        println()
        println("[VIDEO] Frame $frameIdx / $totalFrames")

        Profiler.logMemory("Video rendering")

        println("[FRAME CACHE] " + frameCache.stats())
      }
    }

    pipeOut.flush()
    pipeOut.close()

    val exitCode = process.waitFor()
    check(exitCode == 0) { "FFmpeg video encoding failed with exit code $exitCode." }

    println()
    println("[FRAME CACHE] Final: " + frameCache.stats())
  }
}

// ============================================================================
// APPLICATION ENTRY POINT
// ============================================================================

/**
 * Main application entry point.
 *
 * Expected project structure:
 *
 * ```
 * input.mid
 * samples/
 *     C3.mp4
 *     C#3.mp4
 *     D3.mp4
 *     ...
 *     pause.mp4
 * ```
 *
 * The application performs the following pipeline:
 *
 * 1. Read the MIDI file.
 * 2. Convert MIDI events into a time-based timeline.
 * 3. Extract audio from the required video samples.
 * 4. Synthesize and normalize the master audio.
 * 5. Render video frames lazily using the configured RAM cache.
 * 6. Combine the streamed video and master audio into the final MP4.
 * 7. Remove temporary rendering files.
 */
fun main() {
  println("=== MIDI VIDEO SAMPLER ===")

  // ========================================================================
  // CONFIGURATION
  // ========================================================================

  /**
   * Maximum amount of RAM dedicated to cached JPEG video frames.
   *
   * The value controls the approximate payload size of cached JPEGs,
   * independently of the total duration of the final composition.
   *
   * Examples:
   *
   * 128  -> 128 MB
   * 256  -> 256 MB
   * 512  -> 512 MB
   * 1024 -> 1 GB
   */
  val FRAME_CACHE_MAX_MB = 512

  /**
   * Duration of each temporary audio synthesis chunk.
   *
   * Larger chunks reduce processing overhead but consume more RAM.
   *
   * With 48 kHz stereo Float32 audio:
   *
   * 10 seconds × 48,000 × 2 × 4 bytes
   * ≈ 3.84 MB per stereo buffer.
   */
  val AUDIO_CHUNK_SECONDS = 10

  /**
   * Audio sample rate used throughout the synthesis pipeline.
   */
  val AUDIO_SAMPLE_RATE = 48_000

  /**
   * Video frame rate of the generated output.
   */
  val VIDEO_FPS = 60

  val samplesDir = File("samples")
  val pauseFile = File(samplesDir, "pause.mp4")
  val midiFile = File("input.mid")
  val cacheDir = File("render_cache")
  val outputFile = File("output.mp4")

  cacheDir.mkdirs()

  // ========================================================================
  // 1. READ MIDI AND CREATE TIMELINE
  // ========================================================================

  println()
  println("[1/5] Reading MIDI...")

  val notes = MidiEventReader().read(midiFile)
  val timeline = MidiTimeline().create(notes)

  val uniqueNotes = timeline.filterIsInstance<TimelineNote>().map { it.event.note }.toSet()

  println("[MIDI] Found ${notes.size} notes " + "using ${uniqueNotes.size} unique pitches.")

  println("[MIDI] Timeline events: " + timeline.size)

  // ========================================================================
  // 2. INITIALIZE AUDIO AND VIDEO ENGINES
  // ========================================================================

  println()
  println("[2/5] Initializing rendering engines...")

  val audioSynth = AudioSynthesizer(
    sampleRate = AUDIO_SAMPLE_RATE,
    chunkDurationSeconds = AUDIO_CHUNK_SECONDS
  )

  val videoRenderer = MemoryVideoRenderer(
    fps = VIDEO_FPS,
    maxCacheMb = FRAME_CACHE_MAX_MB
  )

  val pcmSamples = mutableMapOf<String, AudioSample>()

  /*
   * Maps logical sample names to source video files.
   *
   * No video frame is loaded at this stage.
   */
  val frameSources = mutableMapOf<String, File>()

  // ========================================================================
  // 3. LOAD ONLY AUDIO FROM REQUIRED SAMPLES
  // ========================================================================

  println()
  println("[3/5] Loading audio samples...")

  Profiler.measure("Loading audio samples") {
    for (note in uniqueNotes) {
      val sampleVideo = File(
        samplesDir,
        "$note.mp4"
      )

      if (!sampleVideo.exists()) {
        println("[WARNING] Missing sample for note " + "$note: ${sampleVideo.absolutePath}")
        continue
      }

      val tempWav = File(cacheDir, "sample_$note.wav")

      println("[AUDIO] Loading: " + sampleVideo.name)

      pcmSamples[note] = audioSynth.extractSamplePcm(
        videoFile = sampleVideo,
        tempWav = tempWav
      )

      frameSources[note] = sampleVideo
    }

    if (pauseFile.exists()) {
      val tempWav = File(cacheDir, "sample_pause.wav")
      println("[AUDIO] Loading pause sample...")
      pcmSamples["__pause__"] = audioSynth.extractSamplePcm(videoFile = pauseFile, tempWav = tempWav)
      frameSources["__pause__"] = pauseFile
    } else {
      println("[WARNING] No pause sample found. " + "Timeline gaps will use the fallback frame.")
    }
  }

  require(frameSources.isNotEmpty()) { "No video samples were found in ${samplesDir.absolutePath}." }

  // ========================================================================
  // 4. SYNTHESIZE MASTER AUDIO
  // ========================================================================

  println()
  println("[4/5] Synthesizing master audio...")

  val masterWav = File(cacheDir, "master_audio.wav")

  Profiler.measure("Synthesizing master audio") {
    audioSynth.synthesize(
      timeline = timeline,
      samples = pcmSamples,
      outputFile = masterWav
    )
  }

  // ========================================================================
  // 5. RENDER FINAL VIDEO
  // ========================================================================

  println()
  println("[5/5] Rendering final MP4...")
  println("[CONFIG] Frame cache limit: $FRAME_CACHE_MAX_MB MB")
  println("[CONFIG] Audio chunk size: $AUDIO_CHUNK_SECONDS seconds")
  println("[CONFIG] Video FPS: $VIDEO_FPS")

  Profiler.measure("Rendering and encoding final MP4") {
    videoRenderer.renderVideo(
      timeline = timeline,
      frameSources = frameSources,
      masterAudioWav = masterWav,
      outputMp4 = outputFile
    )
  }

  // ========================================================================
  // CLEANUP
  // ========================================================================

  println()
  println("[CLEANUP] Removing temporary files...")

  cacheDir.deleteRecursively()

  println("[CLEANUP] Temporary files removed.")
  println()
  println("=== PROCESSING COMPLETED SUCCESSFULLY ===")
  println("Output: ${outputFile.absolutePath}")
}