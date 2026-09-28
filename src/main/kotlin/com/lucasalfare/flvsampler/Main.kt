package com.lucasalfare.flvsampler

import com.lucasalfare.flmidi.*
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*
import java.util.concurrent.Executors
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos

/**
 * Lightweight memory profiler used to instrument the pipeline.
 *
 * Emits JVM heap usage (used vs. maximum) and wall-clock durations around
 * expensive operations.
 */
object Profiler {
  /**
   * Prints JVM heap usage (used MB / max MB) with the given [tag] prefix.
   */
  fun logMemory(tag: String) {
    val runtime = Runtime.getRuntime()
    val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
    val maxMb = runtime.maxMemory() / (1024 * 1024)
    println("[PROFILER] Memory — $tag: $usedMb MB used / $maxMb MB JVM maximum")
  }

  /**
   * Executes [block], measuring wall-clock time and logging memory before and
   * after execution.
   *
   * @return the value returned by [block].
   */
  inline fun <T> measure(tag: String, block: () -> T): T {
    println(); println("[PROFILER] Starting: $tag"); logMemory("Before $tag")
    val start = System.currentTimeMillis()
    val result = block()
    val elapsed = System.currentTimeMillis() - start
    logMemory("After $tag"); println("[PROFILER] Completed: $tag in $elapsed ms (${elapsed / 1000.0}s)")
    return result
  }
}

/**
 * A single note parsed from a MIDI file, with all times expressed as absolute
 * milliseconds from the beginning of the song.
 *
 * @property note     Pitch name in scientific notation (e.g. "C4", "F#5").
 * @property velocity MIDI note-on velocity (1..127).
 * @property start    Absolute start time in milliseconds.
 * @property duration Length in milliseconds (end - start, clamped to >= 0).
 */
data class NoteEvent(val note: String, val velocity: Int, val start: Long, val duration: Long)

/**
 * Parses a MIDI file into a flat list of [NoteEvent]s using the FLMidi library.
 *
 * Merges all tracks into a single absolute-tick timeline, builds a tempo map
 * from every [SetTempoMetaEvent], pairs note-on/note-off events per
 * (channel, pitch) with a FIFO queue, and converts tick positions to
 * milliseconds by integrating the tempo map. A note-on with velocity 0 is
 * treated as a note-off, as per the MIDI specification.
 */
class MidiEventReader {
  /**
   * Reads [file] and returns every note it contains, sorted by start time.
   *
   * @throws IllegalArgumentException if the file does not exist or declares a
   *         non-positive time division.
   */
  fun read(file: File): List<NoteEvent> {
    require(file.exists()) { "MIDI file not found: ${file.absolutePath}" }
    val midi = MidiReader.fromFile(file.path)
    require(midi.division > 0) { "Invalid MIDI time division: ${midi.division}" }
    val events = buildList {
      for (track in midi.tracks) {
        var tick = 0L
        for (event in track) {
          tick += event.deltaTime; add(TimedEvent(tick, event))
        }
      }
    }.sortedBy { it.tick }
    val tempos = buildList {
      for (event in events) if (event.event is SetTempoMetaEvent) add(
        TempoEvent(
          tick = event.tick,
          tempo = event.event.tempo
        )
      )
    }.toMutableList()
    if (tempos.none { it.tick == 0L }) tempos += TempoEvent(0L, DEFAULT_TEMPO)
    tempos.sortBy { it.tick }
    val activeNotes = mutableMapOf<Pair<Int, Int>, MutableList<StartedNote>>()
    val result = mutableListOf<NoteEvent>()
    for (timedEvent in events) {
      when (val event = timedEvent.event) {
        is NoteOnControlEvent -> if (event.velocity == 0) finishNote(
          event.channel,
          event.note,
          timedEvent.tick,
          activeNotes,
          tempos,
          midi.division,
          result
        )
        else activeNotes.getOrPut(event.channel to event.note, ::mutableListOf)
          .add(StartedNote(timedEvent.tick, event.velocity))

        is NoteOffControlEvent -> finishNote(
          event.channel,
          event.note,
          timedEvent.tick,
          activeNotes,
          tempos,
          midi.division,
          result
        )
      }
    }
    return result.sortedBy { it.start }
  }

  /**
   * Closes the earliest still-open note for [channel] + [note], converts its
   * tick range to milliseconds, and appends a [NoteEvent] to [result].
   *
   * Does nothing if no matching open note exists. Removes the queue entry once
   * it becomes empty.
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
    if (notes.isEmpty()) return
    val started = notes.removeAt(0)
    val start = ticksToMillis(started.tick, tempos, division)
    val end = ticksToMillis(endTick, tempos, division)
    result += NoteEvent(
      note = midiNoteName(note),
      velocity = started.velocity,
      start = start,
      duration = (end - start).coerceAtLeast(0L)
    )
    if (notes.isEmpty()) activeNotes.remove(key)
  }

  /**
   * Converts [targetTick] to milliseconds by walking the sorted [tempos] list
   * and accumulating `Δtick * µsPerQuarter / division` for each constant-tempo
   * segment that precedes the target.
   *
   * Accumulates in microseconds to avoid rounding drift; the final value is
   * divided by 1000 to yield milliseconds.
   */
  private fun ticksToMillis(targetTick: Long, tempos: List<TempoEvent>, division: Int): Long {
    var currentTick = 0L
    var tempo = DEFAULT_TEMPO.toLong()
    var microseconds = 0L
    for (change in tempos) {
      if (change.tick > targetTick) break
      microseconds += (change.tick - currentTick) * tempo / division
      currentTick = change.tick
      tempo = change.tempo.toLong()
    }
    microseconds += (targetTick - currentTick) * tempo / division
    return microseconds / 1_000L
  }

  /** Maps a raw MIDI note number to scientific pitch notation (e.g. 60 → "C4"). */
  private fun midiNoteName(note: Int): String = MIDI_NOTE_NAMES[note % 12] + (note / 12 - 1)

  /** A track event paired with its absolute tick position after track merging. */
  private data class TimedEvent(val tick: Long, val event: Event)

  /** A tempo change (microseconds per quarter note) effective from [tick] onward. */
  private data class TempoEvent(val tick: Long, val tempo: Int)

  /** State kept while a note is sounding, so it can be closed on note-off. */
  private data class StartedNote(val tick: Long, val velocity: Int)

  companion object {
    /** Default MIDI tempo (120 BPM) in microseconds per quarter note. */
    private const val DEFAULT_TEMPO = 500_000

    /** Pitch-class names indexed by `note % 12`. */
    private val MIDI_NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
  }
}

/**
 * Anything that occupies a time interval on the audio master timeline.
 *
 * @property start    Absolute start time in milliseconds.
 * @property duration Length in milliseconds.
 */
sealed interface TimelineEvent {
  val start: Long
  val duration: Long
}

/**
 * A note sounding on the timeline. Delegates timing to the wrapped [event].
 */
data class TimelineNote(val event: NoteEvent) : TimelineEvent {
  override val start = event.start
  override val duration = event.duration
}

/**
 * A silent gap between notes, filled by the pause sample when one is provided.
 *
 * @property durationMs How long the pause lasts, in milliseconds.
 * @property start      Absolute start time in milliseconds.
 */
data class TimelinePause(val durationMs: Long, override val start: Long) : TimelineEvent {
  override val duration = durationMs
}

/**
 * Builds the audio timeline: an ordered list of [TimelineEvent]s where every
 * note is placed at its absolute start time and gaps are filled with
 * [TimelinePause]s. Overlapping notes are kept as-is so the mixer can render
 * them polyphonically.
 */
class MidiTimeline {
  /**
   * @param notes Notes from [MidiEventReader.read]; may be empty.
   * @return events sorted by start time, or an empty list if [notes] is empty.
   */
  fun create(notes: List<NoteEvent>): List<TimelineEvent> {
    if (notes.isEmpty()) return emptyList()
    val timeline = mutableListOf<TimelineEvent>()
    var position = 0L
    for (note in notes.sortedBy { it.start }) {
      if (note.start > position) timeline += TimelinePause(durationMs = note.start - position, start = position)
      timeline += TimelineNote(note); position = maxOf(position, note.start + note.duration)
    }
    return timeline
  }
}

// --- VIDEO TIMELINE REPRESENTATION ---

/**
 * A maximal interval during which the set of active notes is constant.
 *
 * Because the active set does not change inside a segment, the on-screen grid
 * layout also does not change, so it can be computed once per segment.
 *
 * @property start    Absolute start time in milliseconds.
 * @property duration Length in milliseconds.
 * @property notes    Every note sounding throughout the whole segment.
 */
data class VideoNotesSegment(
  val start: Long,
  val duration: Long,
  val notes: List<NoteEvent>
)

/**
 * Builds the video timeline by slicing the song at every note boundary (each
 * note's start and end) and recording which notes are active in each resulting
 * interval.
 */
class VideoTimeline {
  /**
   * @param notes Notes from [MidiEventReader.read]; may be empty.
   * @return contiguous, non-overlapping segments covering the song, or an
   *         empty list if no note has a positive duration.
   */
  fun create(notes: List<NoteEvent>): List<VideoNotesSegment> {
    val validNotes = notes.filter { it.duration > 0 }
    if (validNotes.isEmpty()) return emptyList()

    val boundaries = mutableSetOf<Long>()
    boundaries.add(0L)
    for (note in validNotes) {
      boundaries.add(note.start)
      boundaries.add(note.start + note.duration)
    }

    val sortedBoundaries = boundaries.sorted()
    val segments = mutableListOf<VideoNotesSegment>()

    for (i in 0 until sortedBoundaries.size - 1) {
      val segStart = sortedBoundaries[i]
      val segEnd = sortedBoundaries[i + 1]
      val segDuration = segEnd - segStart
      if (segDuration <= 0) continue

      val activeNotes = validNotes.filter { note ->
        note.start <= segStart && (note.start + note.duration) >= segEnd
      }

      segments.add(VideoNotesSegment(segStart, segDuration, activeNotes))
    }

    return segments
  }
}

/**
 * A rectangular grid used to lay out simultaneously-playing note videos.
 *
 * @property cols Number of columns.
 * @property rows Number of rows.
 */
data class GridLayout(val cols: Int, val rows: Int) {
  companion object {
    /**
     * Returns the layout used for [count] simultaneous notes.
     *
     * @throws IllegalArgumentException if [count] is greater than 4.
     */
    fun forNoteCount(count: Int): GridLayout = when (count) {
      0, 1 -> GridLayout(1, 1)
      2 -> GridLayout(2, 1)
      3 -> GridLayout(3, 1)
      4 -> GridLayout(2, 2)
      else -> throw IllegalArgumentException(
        "Número de notas simultâneas não suportado para o layout visual: $count. O limite máximo é 4."
      )
    }
  }
}

/**
 * A stereo PCM buffer in the `[-1.0, 1.0]` range, one [FloatArray] per channel.
 * Both arrays always have the same length.
 */
class AudioSample(val left: FloatArray, val right: FloatArray)

/**
 * Mixes per-note audio samples into a single master WAV according to a
 * [TimelineEvent] list, applying a short cosine fade-out at the end of each
 * note to avoid clicks.
 *
 * The synthesis runs in two passes over fixed-size windows so the entire
 * master buffer never has to be held in RAM at once:
 *
 *  1. Pass 1 scans the whole song and measures the global peak.
 *  2. Pass 2 mixes again, scales by `0.95 / peak` when the peak exceeds 0.95,
 *     and streams the result to disk.
 *
 * @property sampleRate           Output sample rate in Hz (must be > 0).
 * @property chunkDurationSeconds Window size used by both passes (must be > 0).
 */
class AudioSynthesizer(private val sampleRate: Int = 48_000, private val chunkDurationSeconds: Int = 10) {
  /** Fade-out length applied to the tail of each note, in milliseconds. */
  private val fadeDurationMs = 15.0

  init {
    require(sampleRate > 0) { "Sample rate must be greater than zero." }
    require(chunkDurationSeconds > 0) { "Audio chunk duration must be greater than zero." }
  }

  /**
   * Extracts the audio track of [videoFile] as stereo 16-bit PCM at
   * [sampleRate] via ffmpeg, decodes it into two normalized [FloatArray]s, and
   * returns the result.
   *
   * Returns an empty sample if the WAV is malformed or has no `data` chunk.
   *
   * @param tempWav Scratch file used to hold the intermediate WAV.
   */
  fun extractSamplePcm(videoFile: File, tempWav: File): AudioSample {
    require(videoFile.exists()) { "Sample video not found: ${videoFile.absolutePath}" }
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
    if (bytes.size < 44) return emptyAudioSample()
    val dataOffset = findDataChunk(bytes)
    if (dataOffset >= bytes.size) return emptyAudioSample()
    val pcmBytes = bytes.copyOfRange(dataOffset, bytes.size)
    val totalSamples = pcmBytes.size / 4
    val left = FloatArray(totalSamples)
    val right = FloatArray(totalSamples)
    ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).also { buffer ->
      repeat(totalSamples) { i -> left[i] = buffer.short / 32768.0f; right[i] = buffer.short / 32768.0f }
    }
    return AudioSample(left, right)
  }

  /**
   * Renders [timeline] over [samples] into [outputFile] as a stereo 16-bit
   * PCM WAV.
   *
   * @param timeline Ordered audio events. Must not be empty.
   * @param samples  Map from note name (or [PAUSE_KEY]) to its PCM buffer.
   *                 Missing entries are skipped by the mixer.
   */
  fun synthesize(timeline: List<TimelineEvent>, samples: Map<String, AudioSample>, outputFile: File) {
    require(timeline.isNotEmpty()) { "Cannot synthesize audio from an empty timeline." }
    val totalMs = timeline.maxOf { it.start + it.duration }
    val totalSamplesLong = (totalMs + 1_000L) * sampleRate / 1_000L
    require(totalSamplesLong <= Int.MAX_VALUE) { "Audio is too long for the current buffer implementation: $totalSamplesLong samples." }
    val totalSamples = totalSamplesLong.toInt()
    val chunkSamples =
      (chunkDurationSeconds.toLong() * sampleRate).coerceAtMost(totalSamples.toLong()).coerceAtLeast(1L).toInt()

    println("[AUDIO] Master duration: ${totalMs / 1000.0}s"); println("[AUDIO] Total samples: $totalSamples")
    println("[AUDIO] Chunk duration: $chunkDurationSeconds s"); println("[AUDIO] Samples per chunk: $chunkSamples")

    val masterL = FloatArray(chunkSamples)
    val masterR = FloatArray(chunkSamples)
    val totalChunks = (totalSamples + chunkSamples - 1) / chunkSamples

    println(); println("[AUDIO] Pass 1/2: calculating global peak...")
    var maxPeak = 0.0f
    var chunkStart = 0
    var chunkNumber = 0

    while (chunkStart < totalSamples) {
      val count = minOf(chunkSamples, totalSamples - chunkStart)
      clearBuffers(masterL, masterR, count)
      mixChunk(timeline, samples, masterL, masterR, chunkStart, count)
      repeat(count) { i -> maxPeak = maxOf(maxPeak, abs(masterL[i]), abs(masterR[i])) }
      println("[AUDIO] Peak analysis — chunk ${++chunkNumber}/$totalChunks")
      chunkStart += count
    }

    val scale = if (maxPeak > NORMALIZATION_PEAK) NORMALIZATION_PEAK / maxPeak else 1.0f
    println("[AUDIO] Global peak: $maxPeak"); println("[AUDIO] Normalization scale: $scale")

    println(); println("[AUDIO] Pass 2/2: writing normalized WAV...")
    writeWavStreaming(outputFile, totalSamples, sampleRate) { out ->
      var processedSamples = 0
      var currentChunk = 0
      while (processedSamples < totalSamples) {
        val count = minOf(chunkSamples, totalSamples - processedSamples)
        clearBuffers(masterL, masterR, count)
        mixChunk(timeline, samples, masterL, masterR, processedSamples, count)
        writeChunkAsPcm16(out, masterL, masterR, count, scale)
        println("[AUDIO] WAV writing — chunk ${++currentChunk}/$totalChunks")
        processedSamples += count
      }
    }

    println("[AUDIO] Audio synthesis completed."); println("[AUDIO] Output: ${outputFile.absolutePath}")
  }

  /**
   * Mixes every event that intersects the current window into [masterL] /
   * [masterR]. Simultaneous notes are summed. A cosine fade-out of
   * [fadeDurationMs] is applied past each note's declared duration so the tail
   * of the sample decays smoothly to zero.
   *
   * @param chunkStartSample First absolute sample index of the current window.
   * @param chunkSampleCount Length of the current window in samples.
   */
  private fun mixChunk(
    timeline: List<TimelineEvent>,
    samples: Map<String, AudioSample>,
    masterL: FloatArray,
    masterR: FloatArray,
    chunkStartSample: Int,
    chunkSampleCount: Int
  ) {
    val fadeSamples = (fadeDurationMs * sampleRate / 1000.0).toInt()
    val chunkEnd = chunkStartSample + chunkSampleCount

    for (event in timeline) {
      val key = event.sampleKey()
      val sample = samples[key] ?: continue
      val startSample = (event.start * sampleRate / 1000.0).toInt()
      val durationSamples = (event.duration * sampleRate / 1000.0).toInt()
      val maxCopy = minOf(sample.left.size, durationSamples + fadeSamples)
      if (maxCopy <= 0) continue
      val eventEnd = startSample + maxCopy

      if (eventEnd <= chunkStartSample || startSample >= chunkEnd) continue

      val sourceStart = maxOf(0, chunkStartSample - startSample)
      val sourceEnd = minOf(maxCopy, chunkEnd - startSample)

      for (sourceIndex in sourceStart until sourceEnd) {
        val gain = if (fadeSamples > 0 && sourceIndex >= durationSamples) {
          val progress = (sourceIndex - durationSamples).toFloat() / fadeSamples
          ((1.0 + cos(Math.PI * progress)) * 0.5).toFloat()
        } else 1.0f

        val targetIndex = startSample + sourceIndex - chunkStartSample
        masterL[targetIndex] += sample.left[sourceIndex] * gain
        masterR[targetIndex] += sample.right[sourceIndex] * gain
      }
    }
  }

  /**
   * Writes a 44-byte canonical WAV header to [file], then passes the open
   * buffered stream to [block] for PCM payload writing. Closes the file when
   * [block] returns.
   */
  private fun writeWavStreaming(file: File, totalSamples: Int, sampleRate: Int, block: (BufferedOutputStream) -> Unit) {
    val totalDataLen = totalSamples.toLong() * 4
    val totalSize = totalDataLen + 36
    require(totalDataLen <= 0xFFFFFFFFL) { "WAV file is too large for classic RIFF PCM." }
    file.outputStream().buffered(64 * 1024)
      .use { out -> out.write(createWavHeader(totalDataLen, totalSize, sampleRate)); block(out) }
  }

  /**
   * Interleaves [masterL] / [masterR] into little-endian PCM16 and writes them
   * to [out], applying [scale] during conversion.
   */
  private fun writeChunkAsPcm16(
    out: BufferedOutputStream,
    masterL: FloatArray,
    masterR: FloatArray,
    sampleCount: Int,
    scale: Float
  ) {
    val buffer = ByteBuffer.allocate(sampleCount * 4).order(ByteOrder.LITTLE_ENDIAN)
    repeat(sampleCount) { i -> buffer.putShort(toPcm16(masterL[i] * scale)); buffer.putShort(toPcm16(masterR[i] * scale)) }
    out.write(buffer.array())
  }

  /**
   * Builds a canonical 44-byte RIFF/WAVE/fmt /data header for stereo 16-bit PCM.
   */
  private fun createWavHeader(totalDataLen: Long, totalSize: Long, sampleRate: Int): ByteArray {
    val header = ByteArray(44)
    val byteRate = sampleRate.toLong() * 4
    header.writeAscii(0, "RIFF"); writeIntLE(header, 4, totalSize); header.writeAscii(8, "WAVE"); header.writeAscii(
      12,
      "fmt "
    )
    writeIntLE(header, 16, 16); writeShortLE(header, 20, 1); writeShortLE(header, 22, 2)
    writeIntLE(header, 24, sampleRate.toLong()); writeIntLE(header, 28, byteRate)
    writeShortLE(header, 32, 4); writeShortLE(header, 34, 16)
    header.writeAscii(36, "data"); writeIntLE(header, 40, totalDataLen)
    return header
  }

  /** Writes a 32-bit little-endian integer into [array] at [offset]. */
  private fun writeIntLE(array: ByteArray, offset: Int, value: Long) {
    repeat(4) { array[offset + it] = (value shr (it * 8)).toByte() }
  }

  /** Writes a 16-bit little-endian integer into [array] at [offset]. */
  private fun writeShortLE(array: ByteArray, offset: Int, value: Int) {
    array[offset] = value.toByte()
    array[offset + 1] = (value shr 8).toByte()
  }

  /** Runs ffmpeg with `-loglevel error` plus [arguments]; fails on non-zero exit. */
  private fun runFfmpeg(vararg arguments: String) {
    val exitCode = ProcessBuilder(listOf("ffmpeg", "-loglevel", "error") + arguments).inheritIO().start().waitFor()
    check(exitCode == 0) { "FFmpeg audio extraction failed with exit code $exitCode." }
  }

  /**
   * Returns the byte offset immediately after the RIFF `data` chunk header, or
   * [bytes].size if not found.
   */
  private fun findDataChunk(bytes: ByteArray): Int {
    for (i in 0..bytes.size - 8) {
      if (bytes[i] == 'd'.code.toByte() && bytes[i + 1] == 'a'.code.toByte() && bytes[i + 2] == 't'.code.toByte() && bytes[i + 3] == 'a'.code.toByte()) return i + 8
    }
    return bytes.size
  }

  /** @return a zero-length stereo sample. */
  private fun emptyAudioSample() = AudioSample(FloatArray(0), FloatArray(0))

  /** Zeroes the first [size] entries of both channel buffers. */
  private fun clearBuffers(left: FloatArray, right: FloatArray, size: Int) {
    Arrays.fill(left, 0, size, 0.0f); Arrays.fill(right, 0, size, 0.0f)
  }

  /** Converts a normalized float in `[-1, 1]` to a clamped signed 16-bit integer. */
  private fun toPcm16(value: Float): Short = (value * 32767.0f).toInt().coerceIn(-32768, 32767).toShort()

  private companion object {
    /** Target ceiling used during normalization. */
    const val NORMALIZATION_PEAK = 0.95f
  }
}

/**
 * Thread-safe LRU cache for JPEG-encoded video frames, keyed by source path
 * and frame index. Values are the raw bytes read from ffmpeg, still encoded.
 *
 * Uses an access-ordered [LinkedHashMap] guarded by a single monitor. The map
 * holds at most a few thousand entries, so coarse synchronization is not a
 * bottleneck.
 *
 * @param maxBytes Total memory budget for cached frame bytes. Values larger
 *                 than the budget are rejected.
 */
class RamFrameCache(maxBytes: Long) {
  private val maxBytes = maxBytes.coerceAtLeast(1)

  /** Composite key: absolute source path + frame index. */
  private data class CacheKey(val source: String, val frameIndex: Int)

  private val cache = LinkedHashMap<CacheKey, ByteArray>(16, 0.75f, true)
  private var currentBytes = 0L
  private var hits = 0L
  private var misses = 0L
  private var evictions = 0L

  /**
   * Looks up a cached frame and increments hit/miss counters as a side effect.
   *
   * @return the cached bytes, or `null` on miss.
   */
  @Synchronized
  fun get(source: String, frameIndex: Int): ByteArray? {
    val value = cache[CacheKey(source, frameIndex)]
    if (value != null) hits++ else misses++
    return value
  }

  /**
   * Inserts or replaces an entry and evicts least-recently-used entries until
   * the byte budget is respected. Values larger than [maxBytes] are ignored.
   */
  @Synchronized
  fun put(source: String, frameIndex: Int, value: ByteArray) {
    val valueSize = value.size.toLong()
    if (valueSize > maxBytes) return
    val key = CacheKey(source, frameIndex)
    cache.remove(key)?.let { currentBytes -= it.size }
    cache[key] = value; currentBytes += valueSize
    while (currentBytes > maxBytes) {
      val iterator = cache.entries.iterator()
      val eldest = iterator.next()
      currentBytes -= eldest.value.size; iterator.remove(); evictions++
    }
  }

  /** @return a human-readable snapshot of occupancy and hit/miss counters. */
  @Synchronized
  fun stats(): String = String.format(
    Locale.US,
    "Frames=%d | RAM=%.2f/%.2f MB | Hits=%d | Misses=%d | Evictions=%d",
    cache.size,
    currentBytes / MB,
    maxBytes / MB,
    hits,
    misses,
    evictions
  )

  /** Drops every cached frame and resets the byte counter. Counters are kept. */
  @Synchronized
  fun clear() {
    cache.clear(); currentBytes = 0
  }

  private companion object {
    const val MB = 1024.0 * 1024.0
  }
}

/**
 * Renders the final MP4 by streaming raw RGB24 frames into ffmpeg and muxing
 * the master WAV alongside them.
 *
 * Every frame the renderer will need is extracted before the encoder starts:
 *
 *  1. [computeMaxFrameIndexPerSource] dry-runs the frame loop to determine,
 *     per sample file, the largest frame index that will be requested.
 *  2. [prefetchFramesParallel] runs one ffmpeg per sample, in parallel, using
 *     the `image2pipe` muxer to emit a continuous MJPEG stream. Each stream is
 *     split into individual JPEGs by [splitMjpegStream] and pushed into the
 *     [RamFrameCache].
 *  3. The main frame loop renders with a warm cache, so every `getFrame` call
 *     is a cache hit and no process is spawned inside the loop.
 *
 * @property fps        Output frame rate. Also the rate at which each sample
 *                      is resampled, so sample playback stays in sync.
 * @property maxCacheMb Byte budget for the [RamFrameCache], in megabytes.
 */
class MemoryVideoRenderer(private val fps: Int = 60, maxCacheMb: Int = 512) {
  private val frameCache = RamFrameCache(maxBytes = maxCacheMb.toLong() * 1024 * 1024)

  /**
   * LRU of already-decoded [BufferedImage]s, keyed by source path + frame
   * index. Decoding JPEG costs roughly an order of magnitude more than serving
   * cached bytes, so this tier absorbs most of the CPU cost on repeated frames.
   *
   * The 64-entry cap is conservative: at 1280×720 RGB that is roughly 236 MB
   * worst case.
   */
  private val decodedImageCache = object : LinkedHashMap<String, BufferedImage>(32, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BufferedImage>?): Boolean {
      return size > 64
    }
  }

  init {
    require(fps > 0) { "Video FPS must be greater than zero." }
    require(maxCacheMb > 0) { "Frame cache size must be greater than zero." }
  }

  /**
   * Returns the JPEG bytes for one frame of [videoFile], populating the cache
   * on miss. On a warm cache this is a pure map lookup.
   */
  private fun getFrame(videoFile: File, frameIndex: Int): ByteArray {
    val sourceKey = videoFile.absoluteFile.normalize().path
    val safeFrameIndex = frameIndex.coerceAtLeast(0)
    frameCache.get(sourceKey, safeFrameIndex)?.let { return it }
    return extractSingleFrame(videoFile, safeFrameIndex).also { frameCache.put(sourceKey, safeFrameIndex, it) }
  }

  /**
   * Returns a decoded [BufferedImage] for one frame, checking the
   * decoded-image LRU first and falling through to [getFrame] for the encoded
   * bytes.
   *
   * @param fallbackFrameBytes Bytes to decode if the frame cannot be obtained.
   */
  private fun getDecodedFrame(videoFile: File, frameIndex: Int, fallbackFrameBytes: ByteArray): BufferedImage {
    val key = "${videoFile.absolutePath}_$frameIndex"
    decodedImageCache[key]?.let { return it }

    val frameBytes = try {
      getFrame(videoFile, frameIndex)
    } catch (e: Exception) {
      fallbackFrameBytes
    }

    val img = ImageIO.read(ByteArrayInputStream(frameBytes))
      ?: ImageIO.read(ByteArrayInputStream(fallbackFrameBytes))

    if (img != null) {
      decodedImageCache[key] = img
    }
    return img
  }

  /**
   * Spawns a single ffmpeg to extract one frame at the exact timestamp for
   * [frameIndex], scaled to 1280×720 and re-timed to [fps]. Used for frames
   * the prefetch did not cover.
   */
  private fun extractSingleFrame(videoFile: File, frameIndex: Int): ByteArray {
    require(videoFile.exists()) { "Video sample not found: ${videoFile.absolutePath}" }
    val timestamp = String.format(Locale.US, "%.6f", frameIndex.toDouble() / fps)
    val process = ProcessBuilder(
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
    ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
    val bytes = process.inputStream.use { it.readBytes() }
    val exitCode = process.waitFor()
    check(exitCode == 0) { "FFmpeg failed to extract frame $frameIndex from ${videoFile.name}." }
    check(bytes.isNotEmpty()) { "FFmpeg returned an empty frame $frameIndex from ${videoFile.name}." }
    return bytes
  }

  // ---------------------------------------------------------------------------
  // PREFETCH: one ffmpeg process per sample, in parallel, warming the cache
  // ---------------------------------------------------------------------------

  /**
   * Dry-runs the frame loop to determine, per sample file, the highest frame
   * index that will be requested during rendering.
   *
   * @return map from normalized sample file to its max requested frame index.
   *         The fallback source is always present with at least index 0.
   */
  private fun computeMaxFrameIndexPerSource(
    videoTimeline: List<VideoNotesSegment>,
    frameSources: Map<String, File>,
    fallbackSource: File,
    totalFrames: Int
  ): Map<File, Int> {
    val fallbackNorm = fallbackSource.absoluteFile.normalize()
    val maxIdx = mutableMapOf<File, Int>()
    maxIdx[fallbackNorm] = 0

    val frameDurationMs = 1000.0 / fps
    var activeIndex = 0

    for (frameIdx in 0 until totalFrames) {
      val timeMs = (frameIdx * frameDurationMs).toLong()

      while (activeIndex < videoTimeline.size &&
        timeMs >= videoTimeline[activeIndex].start + videoTimeline[activeIndex].duration
      ) {
        activeIndex++
      }

      val activeSegment =
        videoTimeline.getOrNull(activeIndex)?.takeIf { timeMs >= it.start && timeMs < it.start + it.duration }
      val activeNotes = activeSegment?.notes ?: emptyList()

      if (activeNotes.isEmpty()) {
        maxIdx[fallbackNorm] = maxOf(maxIdx[fallbackNorm] ?: 0, 0)
      } else {
        for (note in activeNotes) {
          val source = (frameSources[note.note] ?: fallbackSource).absoluteFile.normalize()
          val sampleFrameIdx = ((timeMs - note.start) * fps / 1000.0).toInt().coerceAtLeast(0)
          maxIdx[source] = maxOf(maxIdx[source] ?: 0, sampleFrameIdx)
        }
      }
    }
    return maxIdx
  }

  /**
   * Extracts, in parallel, every frame the renderer will need.
   *
   * One ffmpeg process is launched per sample file. The thread pool is capped
   * at 8 because ffmpeg is CPU-bound and additional concurrency gives
   * diminishing returns on consumer hardware.
   */
  private fun prefetchFramesParallel(maxFrameIndexPerSource: Map<File, Int>) {
    val tasks = maxFrameIndexPerSource.entries
      .filter { (file, maxIdx) -> file.exists() && maxIdx >= 0 }
      .toList()

    if (tasks.isEmpty()) return

    val poolSize = minOf(8, tasks.size)
    val executor = Executors.newFixedThreadPool(poolSize)
    println("[PREFETCH] Extracting frames from ${tasks.size} sample(s) using $poolSize thread(s)...")

    try {
      val futures = tasks.map { (source, maxIdx) ->
        executor.submit {
          // +2 slack guards against minor off-by-one drift between the dry-run
          // and the real loop. Extra frames stay cached.
          val frameCount = maxIdx + 2
          val extracted = extractAllFramesForSample(source, frameCount)
          println("[PREFETCH] ${source.name}: $extracted frame(s) extracted")
        }
      }
      futures.forEach { it.get() }
    } finally {
      executor.shutdown()
    }

    println("[PREFETCH] Done. ${frameCache.stats()}")
  }

  /**
   * Runs a single ffmpeg for [videoFile] and writes up to [frameCount] frames
   * into the [RamFrameCache]. The process outputs a continuous MJPEG stream on
   * stdout, split into individual JPEGs by [splitMjpegStream].
   *
   * @return the number of frames decoded and cached.
   */
  private fun extractAllFramesForSample(videoFile: File, frameCount: Int): Int {
    val sourceKey = videoFile.absoluteFile.normalize().path
    val process = ProcessBuilder(
      "ffmpeg",
      "-loglevel", "error",
      "-i", videoFile.absolutePath,
      "-vf", "scale=1280:720,fps=$fps",
      "-frames:v", frameCount.toString(),
      "-f", "image2pipe",
      "-c:v", "mjpeg",
      "-q:v", "3",
      "pipe:1"
    ).redirectError(ProcessBuilder.Redirect.INHERIT).start()

    val bytes = process.inputStream.use { it.readBytes() }
    val exitCode = process.waitFor()
    check(exitCode == 0) { "FFmpeg failed to extract frames from ${videoFile.name}." }

    val frames = splitMjpegStream(bytes)
    frames.forEachIndexed { index, frameBytes ->
      frameCache.put(sourceKey, index, frameBytes)
    }
    return frames.size
  }

  /**
   * Splits a concatenated MJPEG stream into individual JPEG payloads.
   *
   * A JPEG begins with SOI (`0xFFD8`) and ends with EOI (`0xFFD9`). Neither
   * sequence can appear inside the entropy-coded data because `0xFF` bytes
   * there are byte-stuffed as `0xFF00`, nor inside other marker segments
   * (which carry a length prefix). Linear scanning is therefore safe for the
   * stream produced by ffmpeg's `mjpeg` encoder.
   *
   * @return the list of complete JPEGs; a truncated trailing frame is dropped.
   */
  private fun splitMjpegStream(bytes: ByteArray): List<ByteArray> {
    val frames = mutableListOf<ByteArray>()
    var i = 0
    while (i < bytes.size - 1) {
      if (bytes[i] == 0xFF.toByte() && bytes[i + 1] == 0xD8.toByte()) {
        val start = i
        i += 2
        var closed = false
        while (i < bytes.size - 1) {
          if (bytes[i] == 0xFF.toByte() && bytes[i + 1] == 0xD9.toByte()) {
            frames += bytes.copyOfRange(start, i + 2)
            i += 2
            closed = true
            break
          }
          i++
        }
        if (!closed) break
      } else {
        i++
      }
    }
    return frames
  }

  /**
   * Renders the whole video and muxes it with [masterAudioWav] into [outputMp4].
   *
   * Steps:
   *
   *  1. Compute the frame demand per sample and prefetch everything in
   *     parallel.
   *  2. Spawn the ffmpeg encoder reading raw RGB24 from `pipe:0` and the WAV
   *     from disk, producing H.264 + AAC MP4.
   *  3. For each output frame: pick the active segment, compose the grid on a
   *     reused [BufferedImage], convert INT_RGB → RGB24 in place, and write
   *     into the ffmpeg stdin.
   *
   * The composition buffers (canvas, [Graphics2D], [ByteArray], pixel array)
   * are allocated once and reused for the whole render.
   *
   * @param videoTimeline Non-empty list of active-note segments.
   * @param frameSources  Map from note name (or [PAUSE_KEY]) to its sample file.
   * @param masterAudioWav Master audio track already written to disk.
   * @param outputMp4      Destination MP4 file (overwritten).
   */
  fun renderVideo(
    videoTimeline: List<VideoNotesSegment>,
    frameSources: Map<String, File>,
    masterAudioWav: File,
    outputMp4: File
  ) {
    require(videoTimeline.isNotEmpty()) { "Cannot render video from an empty timeline." }
    require(masterAudioWav.exists()) { "Master audio file not found: ${masterAudioWav.absolutePath}" }

    val totalMs = videoTimeline.maxOf { it.start + it.duration }
    val frameDurationMs = 1000.0 / fps
    val totalFrames = ceil(totalMs / frameDurationMs).toInt()
    require(totalFrames > 0) { "Timeline does not contain any renderable frames." }

    val fallbackSource = frameSources[PAUSE_KEY] ?: frameSources.values.firstOrNull()
    ?: error("No video sample is available for rendering.")

    println("[VIDEO] Analyzing frame demand per sample...")
    val maxFrameIndexPerSource = computeMaxFrameIndexPerSource(
      videoTimeline = videoTimeline,
      frameSources = frameSources,
      fallbackSource = fallbackSource,
      totalFrames = totalFrames
    )
    prefetchFramesParallel(maxFrameIndexPerSource)

    val fallbackFrameBytes = getFrame(fallbackSource, 0)

    // Encoder: raw RGB24 in, H.264 + AAC out.
    val process = ProcessBuilder(
      "ffmpeg",
      "-y",
      "-f", "rawvideo",
      "-pixel_format", "rgb24",
      "-video_size", "1280x720",
      "-framerate", fps.toString(),
      "-i", "pipe:0",
      "-i", masterAudioWav.absolutePath,
      "-c:v", "libx264",
      "-preset", "fast",
      "-pix_fmt", "yuv420p",
      "-c:a", "aac",
      "-b:a", "192k",
      "-shortest",
      outputMp4.absolutePath
    ).redirectError(ProcessBuilder.Redirect.INHERIT).start()

    println("[VIDEO] Streaming $totalFrames frames via raw RGB24 pipeline..."); println("[FRAME CACHE] Initial: ${frameCache.stats()}")

    var activeIndex = 0
    var lastLoggedLayout: GridLayout? = null

    // Allocated once and reused for every frame.
    val compositeCanvas = BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB)
    val g2d = compositeCanvas.createGraphics()
    g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)

    val rgbBuffer = ByteArray(1280 * 720 * 3)
    val pixelData = (compositeCanvas.raster.dataBuffer as DataBufferInt).data

    process.outputStream.use { pipeOut ->
      for (frameIdx in 0 until totalFrames) {
        val timeMs = (frameIdx * frameDurationMs).toLong()

        // Advance to the segment containing timeMs. activeIndex is monotonic
        // because VideoTimeline guarantees contiguous segments.
        while (activeIndex < videoTimeline.size && timeMs >= videoTimeline[activeIndex].start + videoTimeline[activeIndex].duration) {
          activeIndex++
        }

        val activeSegment =
          videoTimeline.getOrNull(activeIndex)?.takeIf { timeMs >= it.start && timeMs < it.start + it.duration }
        val activeNotes = activeSegment?.notes ?: emptyList()
        val layout = GridLayout.forNoteCount(activeNotes.size)

        if (layout != lastLoggedLayout) {
          println("[VIDEO] Grid layout changed to ${layout.cols}x${layout.rows} (${activeNotes.size} active notes)")
          lastLoggedLayout = layout
        }

        renderToCompositeCanvas(
          g2d = g2d,
          activeNotes = activeNotes,
          layout = layout,
          timeMs = timeMs,
          frameSources = frameSources,
          fallbackSource = fallbackSource,
          fallbackFrameBytes = fallbackFrameBytes
        )

        convertIntRgbToRgb24Buffer(pixelData, rgbBuffer)

        pipeOut.write(rgbBuffer)

        if (frameIdx > 0 && frameIdx % 1000 == 0) {
          println(); println("[VIDEO] Frame $frameIdx / $totalFrames")
          Profiler.logMemory("Video rendering"); println("[FRAME CACHE] ${frameCache.stats()}")
        }
      }
      pipeOut.flush()
    }

    g2d.dispose()
    check(process.waitFor() == 0) { "FFmpeg video encoding failed." }
    println(); println("[FRAME CACHE] Final: ${frameCache.stats()}")
  }

  /**
   * Composes one output frame into the shared [g2d] canvas.
   *
   * With no active notes, draws the fallback sample's first frame full-screen.
   * Otherwise, splits the canvas into a [layout]-shaped grid and, for each
   * note, draws its sample's current frame letterboxed inside its cell to
   * preserve aspect ratio.
   *
   * The per-note frame index is derived from how long the note has been
   * sounding (`timeMs - note.start`), so sample playback starts exactly when
   * the note starts on the timeline.
   */
  private fun renderToCompositeCanvas(
    g2d: java.awt.Graphics2D,
    activeNotes: List<NoteEvent>,
    layout: GridLayout,
    timeMs: Long,
    frameSources: Map<String, File>,
    fallbackSource: File,
    fallbackFrameBytes: ByteArray
  ) {
    g2d.color = Color.BLACK
    g2d.fillRect(0, 0, 1280, 720)

    if (activeNotes.isEmpty()) {
      val img = getDecodedFrame(fallbackSource, 0, fallbackFrameBytes)
      g2d.drawImage(img, 0, 0, 1280, 720, null)
      return
    }

    val cellWidth = 1280 / layout.cols
    val cellHeight = 720 / layout.rows

    for (i in activeNotes.indices) {
      val note = activeNotes[i]
      val col = i % layout.cols
      val row = i / layout.cols

      val cellX = col * cellWidth
      val cellY = row * cellHeight

      val source = frameSources[note.note] ?: fallbackSource
      val sampleFrameIdx = ((timeMs - note.start) * fps / 1000.0).toInt().coerceAtLeast(0)

      val img = getDecodedFrame(source, sampleFrameIdx, fallbackFrameBytes)

      val imgWidth = img.width
      val imgHeight = img.height
      val imgAspect = imgWidth.toDouble() / imgHeight
      val cellAspect = cellWidth.toDouble() / cellHeight

      var drawWidth = cellWidth
      var drawHeight = cellHeight

      if (imgAspect > cellAspect) {
        drawHeight = (cellWidth / imgAspect).toInt()
      } else {
        drawWidth = (cellHeight * imgAspect).toInt()
      }

      val drawX = cellX + (cellWidth - drawWidth) / 2
      val drawY = cellY + (cellHeight - drawHeight) / 2

      g2d.drawImage(img, drawX, drawY, drawWidth, drawHeight, null)
    }
  }

  /**
   * Converts the canvas' packed `INT_RGB` pixel array into a tightly packed
   * RGB24 byte buffer (3 bytes per pixel, no alpha).
   *
   * Reads directly from the raster's backing array and writes into a
   * pre-allocated buffer.
   */
  private fun convertIntRgbToRgb24Buffer(srcPixels: IntArray, dstBuffer: ByteArray) {
    var srcIdx = 0
    var dstIdx = 0
    val totalPixels = srcPixels.size
    while (srcIdx < totalPixels) {
      val pixel = srcPixels[srcIdx]
      dstBuffer[dstIdx] = (pixel shr 16 and 0xFF).toByte()     // R
      dstBuffer[dstIdx + 1] = (pixel shr 8 and 0xFF).toByte()  // G
      dstBuffer[dstIdx + 2] = (pixel and 0xFF).toByte()        // B
      srcIdx++
      dstIdx += 3
    }
  }
}

/** Reserved key under which the pause sample is registered in sample maps. */
private const val PAUSE_KEY = "__pause__"

/**
 * Maps an audio-timeline event to the sample map key it should use.
 * Notes map to their pitch name; pauses map to [PAUSE_KEY].
 */
private fun TimelineEvent.sampleKey(): String = when (this) {
  is TimelineNote -> event.note
  is TimelinePause -> PAUSE_KEY
}

/** Writes [value]'s ASCII bytes into this array starting at [offset]. */
private fun ByteArray.writeAscii(offset: Int, value: String) {
  value.forEachIndexed { index, char -> this[offset + index] = char.code.toByte() }
}

/**
 * User-tunable settings for the pipeline.
 */
data class SamplerConfig(
  /** Byte budget for the encoded-frame LRU cache, in megabytes. */
  val frameCacheMaxMb: Int = 512,
  /** Window size used by both audio synthesis passes, in seconds. */
  val audioChunkSeconds: Int = 10,
  /** Output audio sample rate in Hz. */
  val audioSampleRate: Int = 48_000,
  /** Output video frame rate; also the rate samples are resampled to. */
  val videoFps: Int = 60,
  /** Directory containing `<note>.mp4` samples and optional `pause.mp4`. */
  val samplesDir: File = File("samples"),
  /** Source MIDI file to render. */
  val midiFile: File = File("input.mid"),
  /** Scratch directory for intermediate WAVs; deleted on success. */
  val cacheDir: File = File("render_cache"),
  /** Destination MP4 file. */
  val outputFile: File = File("output.mp4")
)

/**
 * Mutable state shared by the pipeline steps. Holds the parsed MIDI data,
 * derived timelines, loaded samples, and the long-lived engines.
 */
class PipelineContext(val config: SamplerConfig) {
  /** Optional pause sample; when missing, video gaps use the fallback frame. */
  val pauseFile = File(config.samplesDir, "pause.mp4")

  /** Intermediate WAV written by [AudioSynthesizer] and read by the encoder. */
  val masterWav = File(config.cacheDir, "master_audio.wav")

  val audioSynth =
    AudioSynthesizer(sampleRate = config.audioSampleRate, chunkDurationSeconds = config.audioChunkSeconds)

  val videoRenderer = MemoryVideoRenderer(fps = config.videoFps, maxCacheMb = config.frameCacheMaxMb)

  /** Decoded PCM per note (and [PAUSE_KEY]), ready for the mixer. */
  val pcmSamples = mutableMapOf<String, AudioSample>()

  /** Sample file per note (and [PAUSE_KEY]), used by the renderer. */
  val frameSources = mutableMapOf<String, File>()

  var notes: List<NoteEvent> = emptyList()
  var timeline: List<TimelineEvent> = emptyList()
  var videoTimeline: List<VideoNotesSegment> = emptyList()
  var uniqueNotes: Set<String> = emptySet()
}

/**
 * Top-level orchestrator. Runs five pipeline stages in sequence:
 *
 *  1. Read MIDI → notes + audio timeline + video timeline.
 *  2. Init engines (placeholder for warm-up work).
 *  3. Load audio samples and register video sources.
 *  4. Synthesize the master audio WAV.
 *  5. Render and encode the final MP4.
 *  6. Delete the scratch directory.
 *
 * @property config User-tunable settings; see [SamplerConfig].
 */
class SamplerPipeline(private val config: SamplerConfig = SamplerConfig()) {
  /** Runs every stage; throws on unrecoverable errors. */
  fun execute() {
    println("=== MIDI VIDEO SAMPLER ===")
    config.cacheDir.mkdirs()
    val context = PipelineContext(config)

    readMidiStep(context)
    initEnginesStep(context)
    loadAudioStep(context)
    synthesizeAudioStep(context)
    renderVideoStep(context)
    cleanupStep(context)
  }

  /**
   * Stage 1: parses the MIDI file and derives both timelines. Logs the number
   * of notes, unique pitches, and the maximum simultaneous note count.
   */
  private fun readMidiStep(context: PipelineContext) {
    println()
    println("[1/5] Reading MIDI...")
    context.notes = MidiEventReader().read(context.config.midiFile)
    context.timeline = MidiTimeline().create(context.notes)
    context.videoTimeline = VideoTimeline().create(context.notes)
    context.uniqueNotes = context.notes.map { it.note }.toSet()

    val maxSimultaneous = context.videoTimeline.maxOfOrNull { it.notes.size } ?: 0
    println("[MIDI] Found ${context.notes.size} notes using ${context.uniqueNotes.size} unique pitches.")
    println("[MIDI] Audio timeline events: ${context.timeline.size}")
    println("[VIDEO] Video timeline created with ${context.videoTimeline.size} segments (max simultaneous notes: $maxSimultaneous).")
  }

  /**
   * Stage 2: reserved for engine warm-up tasks. No-op because the engines are
   * constructed eagerly by [PipelineContext].
   */
  private fun initEnginesStep(context: PipelineContext) {
    println()
    println("[2/5] Initializing rendering engines...")
  }

  /**
   * Stage 3: for each unique pitch, loads the corresponding `<note>.mp4` —
   * extracts its PCM for the mixer and registers the file for the renderer.
   * Missing samples are logged and skipped.
   *
   * The optional `pause.mp4` is loaded the same way under [PAUSE_KEY].
   */
  private fun loadAudioStep(context: PipelineContext) {
    println()
    println("[3/5] Loading audio samples...")
    Profiler.measure("Loading audio samples") {
      for (note in context.uniqueNotes) {
        val sampleVideo = File(context.config.samplesDir, "$note.mp4")
        if (!sampleVideo.exists()) {
          println("[WARNING] Missing sample for note $note: ${sampleVideo.absolutePath}")
          continue
        }
        println("[AUDIO] Loading: ${sampleVideo.name}")
        context.pcmSamples[note] = context.audioSynth.extractSamplePcm(
          videoFile = sampleVideo,
          tempWav = File(context.config.cacheDir, "sample_$note.wav")
        )
        context.frameSources[note] = sampleVideo
      }
      if (context.pauseFile.exists()) {
        println("[AUDIO] Loading pause sample...")
        context.pcmSamples[PAUSE_KEY] = context.audioSynth.extractSamplePcm(
          videoFile = context.pauseFile,
          tempWav = File(context.config.cacheDir, "sample_pause.wav")
        )
        context.frameSources[PAUSE_KEY] = context.pauseFile
      } else {
        println("[WARNING] No pause sample found. Timeline gaps will use the fallback frame.")
      }
    }
    require(context.frameSources.isNotEmpty()) { "No video samples were found in ${context.config.samplesDir.absolutePath}." }
  }

  /**
   * Stage 4: mixes every loaded sample according to the audio timeline and
   * writes the normalized master WAV to disk.
   */
  private fun synthesizeAudioStep(context: PipelineContext) {
    println()
    println("[4/5] Synthesizing master audio...")
    Profiler.measure("Synthesizing master audio") {
      context.audioSynth.synthesize(
        timeline = context.timeline,
        samples = context.pcmSamples,
        outputFile = context.masterWav
      )
    }
  }

  /**
   * Stage 5: renders the video timeline to MP4, muxing the master WAV.
   */
  private fun renderVideoStep(context: PipelineContext) {
    println()
    println("[5/5] Rendering final MP4...")
    println("[CONFIG] Frame cache limit: ${context.config.frameCacheMaxMb} MB")
    println("[CONFIG] Audio chunk size: ${context.config.audioChunkSeconds} seconds")
    println("[CONFIG] Video FPS: ${context.config.videoFps}")
    Profiler.measure("Rendering and encoding final MP4") {
      context.videoRenderer.renderVideo(
        videoTimeline = context.videoTimeline,
        frameSources = context.frameSources,
        masterAudioWav = context.masterWav,
        outputMp4 = context.config.outputFile
      )
    }
  }

  /**
   * Deletes the scratch directory and prints the final output path.
   */
  private fun cleanupStep(context: PipelineContext) {
    println()
    println("[CLEANUP] Removing temporary files...")
    context.config.cacheDir.deleteRecursively()
    println("[CLEANUP] Temporary files removed.")
    println()
    println("=== PROCESSING COMPLETED SUCCESSFULLY ===")
    println("Output: ${context.config.outputFile.absolutePath}")
  }
}

/** CLI entry point. Runs the sampler with default [SamplerConfig]. */
fun main() {
  SamplerPipeline().execute()
}