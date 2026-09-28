package com.lucasalfare.flvsampler

import com.lucasalfare.flmidi.*
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos

// ============================================================
// 0. Diagnostics
// ============================================================

object Profiler {
  fun logMemory(tag: String) {
    val runtime = Runtime.getRuntime()
    val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
    val maxMb = runtime.maxMemory() / (1024 * 1024)
    println("[PROFILER] Memory — $tag: $usedMb MB used / $maxMb MB JVM maximum")
  }

  inline fun <T> measure(tag: String, block: () -> T): T {
    println(); println("[PROFILER] Starting: $tag"); logMemory("Before $tag")
    val start = System.currentTimeMillis()
    val result = block()
    val elapsed = System.currentTimeMillis() - start
    logMemory("After $tag"); println("[PROFILER] Completed: $tag in ${SimpleDateFormat("mm:ss.SSS").format(elapsed)}")
    return result
  }
}

// ============================================================
// 1. Shared keys / sentinels
// ============================================================

private object SampleKeys {
  const val PAUSE = "__pause__"
}

// ============================================================
// 2. FFmpeg adapter
// ============================================================

private object Ffmpeg {
  fun runInherit(vararg args: String) {
    val exitCode = ProcessBuilder(listOf("ffmpeg", "-loglevel", "error") + args)
      .inheritIO()
      .start()
      .waitFor()
    check(exitCode == 0) { "FFmpeg failed with exit code $exitCode." }
  }

  fun start(vararg args: String): Process =
    ProcessBuilder(listOf("ffmpeg", "-loglevel", "error") + args)
      .redirectError(ProcessBuilder.Redirect.INHERIT)
      .start()
}

private object EncoderSelector {
  data class EncoderConfig(val name: String, val params: List<String>)

  fun select(): EncoderConfig {
    val available = probeAvailableEncoders()
    println("[ENCODER] Hardware encoders present in FFmpeg: ${available.ifEmpty { setOf("(none)") }}")

    val candidates = buildList {
      if ("h264_amf" in available) add(
        EncoderConfig(
          "h264_amf",
          listOf(
            "-quality", "balanced", "-rc", "cqp",
            "-qp_i", "23", "-qp_p", "23", "-qp_b", "23"
          )
        )
      )
      if ("h264_nvenc" in available) add(
        EncoderConfig("h264_nvenc", listOf("-preset", "p5", "-rc", "vbr", "-cq", "23"))
      )
      if ("h264_qsv" in available) add(
        EncoderConfig("h264_qsv", listOf("-preset", "medium", "-global_quality", "23"))
      )
      add(EncoderConfig("libx264", listOf("-preset", "fast", "-crf", "23")))
    }

    for (cfg in candidates) {
      if (cfg.name == "libx264") {
        println("[ENCODER] Using software fallback: libx264")
        return cfg
      }
      print("[ENCODER] Probing ${cfg.name}... ")
      if (testEncode(cfg)) {
        println("OK")
        println("[ENCODER] Using hardware encoder: ${cfg.name}")
        return cfg
      }
      println("failed, falling back.")
    }
    return candidates.last()
  }

  private fun probeAvailableEncoders(): Set<String> {
    return try {
      val process = ProcessBuilder("ffmpeg", "-hide_banner", "-encoders")
        .redirectErrorStream(true)
        .start()
      val text = process.inputStream.bufferedReader().use { it.readText() }
      process.waitFor()
      val found = mutableSetOf<String>()
      for (line in text.lines()) {
        for (enc in listOf("h264_amf", "h264_nvenc", "h264_qsv")) {
          if (line.contains(" $enc ")) found.add(enc)
        }
      }
      found
    } catch (_: Exception) {
      emptySet()
    }
  }

  private fun testEncode(cfg: EncoderConfig): Boolean {
    return try {
      val cmd = mutableListOf(
        "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
        "-f", "lavfi", "-i", "testsrc=size=1280x720:rate=30:duration=0.3",
        "-c:v", cfg.name
      )
      cmd += cfg.params
      cmd += listOf("-f", "null", "-")
      val process = ProcessBuilder(cmd)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .start()
      process.waitFor() == 0
    } catch (_: Exception) {
      false
    }
  }
}

// ============================================================
// 3. MIDI domain
// ============================================================

data class NoteEvent(
  val note: String,
  val velocity: Int,
  val start: Long,
  val duration: Long,
  val track: Int = 0
)

class MidiEventReader {
  fun read(file: File): List<NoteEvent> {
    require(file.exists()) { "MIDI file not found: ${file.absolutePath}" }
    val midi = MidiReader.fromFile(file.path)
    require(midi.division > 0) { "Invalid MIDI time division: ${midi.division}" }
    val events = buildList {
      midi.tracks.forEachIndexed { trackIndex, track ->
        var tick = 0L
        for (event in track) {
          tick += event.deltaTime
          add(TimedEvent(tick, trackIndex, event))
        }
      }
    }.sortedBy { it.tick }
    val tempos = buildList {
      for (event in events) if (event.event is SetTempoMetaEvent) add(TempoEvent(event.tick, event.event.tempo))
    }.toMutableList()
    if (tempos.none { it.tick == 0L }) tempos += TempoEvent(0L, DEFAULT_TEMPO)
    tempos.sortBy { it.tick }
    val activeNotes = mutableMapOf<Triple<Int, Int, Int>, MutableList<StartedNote>>()
    val result = mutableListOf<NoteEvent>()
    for (timedEvent in events) {
      val trackIndex = timedEvent.track
      when (val event = timedEvent.event) {
        is NoteOnControlEvent -> if (event.velocity == 0) finishNote(
          trackIndex, event.channel, event.note, timedEvent.tick,
          activeNotes, tempos, midi.division, result
        )
        else activeNotes.getOrPut(Triple(trackIndex, event.channel, event.note), ::mutableListOf)
          .add(StartedNote(timedEvent.tick, event.velocity))

        is NoteOffControlEvent -> finishNote(
          trackIndex, event.channel, event.note, timedEvent.tick,
          activeNotes, tempos, midi.division, result
        )
      }
    }
    return result.sortedWith(compareBy({ it.start }, { it.track }))
  }

  private fun finishNote(
    track: Int,
    channel: Int,
    note: Int,
    endTick: Long,
    activeNotes: MutableMap<Triple<Int, Int, Int>, MutableList<StartedNote>>,
    tempos: List<TempoEvent>,
    division: Int,
    result: MutableList<NoteEvent>
  ) {
    val key = Triple(track, channel, note)
    val notes = activeNotes[key] ?: return
    if (notes.isEmpty()) return
    val started = notes.removeAt(0)
    val start = ticksToMillis(started.tick, tempos, division)
    val end = ticksToMillis(endTick, tempos, division)
    result += NoteEvent(midiNoteName(note), started.velocity, start, (end - start).coerceAtLeast(0L), track)
    if (notes.isEmpty()) activeNotes.remove(key)
  }

  private fun ticksToMillis(targetTick: Long, tempos: List<TempoEvent>, division: Int): Long {
    var currentTick = 0L
    var tempo = DEFAULT_TEMPO.toLong()
    var microseconds = 0L
    for (change in tempos) {
      if (change.tick > targetTick) break
      microseconds += (change.tick - currentTick) * tempo / division
      currentTick = change.tick; tempo = change.tempo.toLong()
    }
    microseconds += (targetTick - currentTick) * tempo / division
    return microseconds / 1_000L
  }

  private fun midiNoteName(note: Int): String = MIDI_NOTE_NAMES[note % 12] + (note / 12 - 1)
  private data class TimedEvent(val tick: Long, val track: Int, val event: Event)
  private data class TempoEvent(val tick: Long, val tempo: Int)
  private data class StartedNote(val tick: Long, val velocity: Int)
  companion object {
    private const val DEFAULT_TEMPO = 500_000
    private val MIDI_NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
  }
}

// ============================================================
// 4. Timeline domain
// ============================================================

sealed interface TimelineEvent {
  val start: Long
  val duration: Long
}

data class TimelineNote(val event: NoteEvent) : TimelineEvent {
  override val start = event.start
  override val duration = event.duration
}

data class TimelinePause(val durationMs: Long, override val start: Long) : TimelineEvent {
  override val duration = durationMs
}

class MidiTimeline {
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

data class VideoNotesSegment(val start: Long, val duration: Long, val notes: List<NoteEvent>)

class VideoTimeline {
  fun create(notes: List<NoteEvent>): Map<Int, List<VideoNotesSegment>> {
    if (notes.isEmpty()) return emptyMap()
    return notes.groupBy { it.track }
      .mapValues { (_, trackNotes) -> createForTrack(trackNotes) }
  }

  private fun createForTrack(notes: List<NoteEvent>): List<VideoNotesSegment> {
    val validNotes = notes.filter { it.duration > 0 }
    if (validNotes.isEmpty()) return emptyList()
    val boundaries = mutableSetOf<Long>(); boundaries.add(0L)
    for (note in validNotes) {
      boundaries.add(note.start); boundaries.add(note.start + note.duration)
    }
    val sortedBoundaries = boundaries.sorted()
    val segments = mutableListOf<VideoNotesSegment>()
    for (i in 0 until sortedBoundaries.size - 1) {
      val segStart = sortedBoundaries[i]
      val segEnd = sortedBoundaries[i + 1]
      val segDuration = segEnd - segStart
      if (segDuration <= 0) continue
      val activeNotes = validNotes.filter { note -> note.start <= segStart && (note.start + note.duration) >= segEnd }
      segments.add(VideoNotesSegment(segStart, segDuration, activeNotes))
    }
    return segments
  }
}

private class VideoTimelineCursor(private val segmentsByTrack: Map<Int, List<VideoNotesSegment>>) {
  private val indices = mutableMapOf<Int, Int>()

  fun activeAt(track: Int, timeMs: Long): VideoNotesSegment? {
    val segments = segmentsByTrack[track] ?: return null
    var index = indices[track] ?: 0
    while (index < segments.size && timeMs >= segments[index].start + segments[index].duration) {
      index++
    }
    indices[track] = index
    return segments.getOrNull(index)?.takeIf { timeMs >= it.start && timeMs < it.start + it.duration }
  }
}

// ============================================================
// 5. Audio domain
// ============================================================

class AudioSample(val left: FloatArray, val right: FloatArray)

private class WavReader {
  fun readPcm16Stereo(bytes: ByteArray): AudioSample {
    if (bytes.size < 44) return emptySample()
    val dataOffset = findDataChunk(bytes)
    if (dataOffset >= bytes.size) return emptySample()
    val pcmBytes = bytes.copyOfRange(dataOffset, bytes.size)
    val totalSamples = pcmBytes.size / 4
    val left = FloatArray(totalSamples)
    val right = FloatArray(totalSamples)
    ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).also { buffer ->
      repeat(totalSamples) { i ->
        left[i] = buffer.short / 32768.0f; right[i] = buffer.short / 32768.0f
      }
    }
    return AudioSample(left, right)
  }

  private fun findDataChunk(bytes: ByteArray): Int {
    for (i in 0..bytes.size - 8) {
      if (bytes[i] == 'd'.code.toByte() && bytes[i + 1] == 'a'.code.toByte() &&
        bytes[i + 2] == 't'.code.toByte() && bytes[i + 3] == 'a'.code.toByte()
      ) return i + 8
    }
    return bytes.size
  }

  private fun emptySample() = AudioSample(FloatArray(0), FloatArray(0))
}

private class WavWriter {
  fun writeStreaming(file: File, totalSamples: Int, sampleRate: Int, block: (BufferedOutputStream) -> Unit) {
    val totalDataLen = totalSamples.toLong() * 4
    val totalSize = totalDataLen + 36
    require(totalDataLen <= 0xFFFFFFFFL) { "WAV file is too large for classic RIFF PCM." }
    file.outputStream().buffered(64 * 1024)
      .use { out ->
        out.write(createHeader(totalDataLen, totalSize, sampleRate))
        block(out)
      }
  }

  fun writeChunkAsPcm16(
    out: BufferedOutputStream,
    masterL: FloatArray,
    masterR: FloatArray,
    sampleCount: Int,
    scale: Float
  ) {
    val buffer = ByteBuffer.allocate(sampleCount * 4).order(ByteOrder.LITTLE_ENDIAN)
    repeat(sampleCount) { i ->
      buffer.putShort(toPcm16(masterL[i] * scale))
      buffer.putShort(toPcm16(masterR[i] * scale))
    }
    out.write(buffer.array())
  }

  private fun createHeader(totalDataLen: Long, totalSize: Long, sampleRate: Int): ByteArray {
    val header = ByteArray(44)
    val byteRate = sampleRate.toLong() * 4
    header.writeAscii(0, "RIFF"); writeIntLE(header, 4, totalSize); header.writeAscii(8, "WAVE")
    header.writeAscii(12, "fmt ")
    writeIntLE(header, 16, 16); writeShortLE(header, 20, 1); writeShortLE(header, 22, 2)
    writeIntLE(header, 24, sampleRate.toLong()); writeIntLE(header, 28, byteRate)
    writeShortLE(header, 32, 4); writeShortLE(header, 34, 16)
    header.writeAscii(36, "data"); writeIntLE(header, 40, totalDataLen)
    return header
  }

  private fun writeIntLE(array: ByteArray, offset: Int, value: Long) {
    repeat(4) { array[offset + it] = (value shr (it * 8)).toByte() }
  }

  private fun writeShortLE(array: ByteArray, offset: Int, value: Int) {
    array[offset] = value.toByte(); array[offset + 1] = (value shr 8).toByte()
  }

  private fun ByteArray.writeAscii(offset: Int, value: String) {
    value.forEachIndexed { index, char -> this[offset + index] = char.code.toByte() }
  }

  private fun toPcm16(value: Float): Short = (value * 32767.0f).toInt().coerceIn(-32768, 32767).toShort()
}

private class AudioSampleLoader(
  private val sampleRate: Int,
  private val wavReader: WavReader
) {
  fun extractSamplePcm(videoFile: File, tempWav: File): AudioSample {
    require(videoFile.exists()) { "Sample video not found: ${videoFile.absolutePath}" }
    Ffmpeg.runInherit(
      "-y",
      "-i", videoFile.absolutePath,
      "-vn",
      "-ar", sampleRate.toString(),
      "-ac", "2",
      "-c:a", "pcm_s16le",
      tempWav.absolutePath
    )
    val bytes = tempWav.readBytes()
    return wavReader.readPcm16Stereo(bytes)
  }
}

class AudioSynthesizer(private val sampleRate: Int = 48_000, private val chunkDurationSeconds: Int = 10) {
  private val fadeDurationMs = 15.0
  private val wavWriter = WavWriter()

  init {
    require(sampleRate > 0) { "Sample rate must be greater than zero." }
    require(chunkDurationSeconds > 0) { "Audio chunk duration must be greater than zero." }
  }

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
    wavWriter.writeStreaming(outputFile, totalSamples, sampleRate) { out ->
      var processedSamples = 0
      var currentChunk = 0
      while (processedSamples < totalSamples) {
        val count = minOf(chunkSamples, totalSamples - processedSamples)
        clearBuffers(masterL, masterR, count)
        mixChunk(timeline, samples, masterL, masterR, processedSamples, count)
        wavWriter.writeChunkAsPcm16(out, masterL, masterR, count, scale)
        println("[AUDIO] WAV writing — chunk ${++currentChunk}/$totalChunks")
        processedSamples += count
      }
    }
    println("[AUDIO] Audio synthesis completed."); println("[AUDIO] Output: ${outputFile.absolutePath}")
  }

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
      val velocityGain = event.velocityGain()
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
        val finalGain = gain * velocityGain
        val targetIndex = startSample + sourceIndex - chunkStartSample
        masterL[targetIndex] += sample.left[sourceIndex] * finalGain
        masterR[targetIndex] += sample.right[sourceIndex] * finalGain
      }
    }
  }

  private fun clearBuffers(left: FloatArray, right: FloatArray, size: Int) {
    Arrays.fill(left, 0, size, 0.0f); Arrays.fill(right, 0, size, 0.0f)
  }

  private companion object {
    const val NORMALIZATION_PEAK = 0.95f
  }
}

private fun TimelineEvent.sampleKey(): String = when (this) {
  is TimelineNote -> event.note
  is TimelinePause -> SampleKeys.PAUSE
}

private fun TimelineEvent.velocityGain(): Float = when (this) {
  is TimelineNote -> velocityToGain(event.velocity)
  is TimelinePause -> 1.0f
}

private fun velocityToGain(velocity: Int): Float {
  val v = velocity.coerceIn(1, 127) / 127.0f
  return v
}

// ============================================================
// 6. Video domain
// ============================================================

class RamFrameCache(maxBytes: Long) {
  private val maxBytes = maxBytes.coerceAtLeast(1)

  private data class CacheKey(val source: String, val frameIndex: Int)

  private val cache = LinkedHashMap<CacheKey, ByteArray>(16, 0.75f, true)
  private var currentBytes = 0L
  private var hits = 0L
  private var misses = 0L
  private var evictions = 0L

  @Synchronized
  fun get(source: String, frameIndex: Int): ByteArray? {
    val value = cache[CacheKey(source, frameIndex)]
    if (value != null) hits++ else misses++
    return value
  }

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

  @Synchronized
  fun stats(): String = String.format(
    Locale.US,
    "Frames=%d | RAM=%.2f/%.2f MB | Hits=%d | Misses=%d | Evictions=%d",
    cache.size, currentBytes / MB, maxBytes / MB, hits, misses, evictions
  )

  private companion object {
    const val MB = 1024.0 * 1024.0
  }
}

// [M1] Fallback cache: só o frame de pausa redimensionado por (w,h), sem eviction.
private class FallbackFrameCache {
  private val map = ConcurrentHashMap<Long, BufferedImage>()
  private fun key(w: Int, h: Int): Long = (w.toLong() shl 32) or (h.toLong() and 0xFFFFFFFFL)
  fun get(w: Int, h: Int): BufferedImage? = map[key(w, h)]
  fun put(w: Int, h: Int, img: BufferedImage) {
    map[key(w, h)] = img
  }
}

// [M3] Pool de buffers de saída por chunk para eliminar alocação por chunk.
private class ChunkBufferPool(bufferSize: Int, count: Int) {
  private val pool = ArrayBlockingQueue<ByteArray>(count)

  init {
    require(bufferSize > 0) { "Chunk buffer size must be greater than zero." }
    require(count > 0) { "Chunk buffer pool must have at least one buffer." }
    repeat(count) { pool.add(ByteArray(bufferSize)) }
  }

  fun acquire(): ByteArray = pool.take()
  fun release(buffer: ByteArray) {
    pool.offer(buffer)
  }
}

private class FrameExtractor(private val fps: Int) {
  fun extractSingle(videoFile: File, frameIndex: Int): ByteArray {
    require(videoFile.exists()) { "Video sample not found: ${videoFile.absolutePath}" }
    val timestamp = String.format(Locale.US, "%.6f", frameIndex.toDouble() / fps)
    val process = Ffmpeg.start(
      "-ss", timestamp,
      "-i", videoFile.absolutePath,
      "-vf", "fps=$fps",
      "-frames:v", "1",
      "-f", "mjpeg", "-q:v", "3", "pipe:1"
    )
    val bytes = process.inputStream.use { it.readBytes() }
    val exitCode = process.waitFor()
    check(exitCode == 0) { "FFmpeg failed to extract frame $frameIndex from ${videoFile.name}." }
    check(bytes.isNotEmpty()) { "FFmpeg returned an empty frame $frameIndex from ${videoFile.name}." }
    return bytes
  }

  fun extractAll(videoFile: File, frameCount: Int): List<ByteArray> {
    val process = Ffmpeg.start(
      "-i", videoFile.absolutePath,
      "-vf", "fps=$fps",
      "-frames:v", frameCount.toString(),
      "-f", "image2pipe",
      "-c:v", "mjpeg", "-q:v", "3", "pipe:1"
    )
    val bytes = process.inputStream.use { it.readBytes() }
    val exitCode = process.waitFor()
    check(exitCode == 0) { "FFmpeg failed to extract frames from ${videoFile.name}." }
    return splitMjpegStream(bytes)
  }

  private fun splitMjpegStream(bytes: ByteArray): List<ByteArray> {
    val frames = mutableListOf<ByteArray>()
    var i = 0
    while (i < bytes.size - 1) {
      if (bytes[i] == 0xFF.toByte() && bytes[i + 1] == 0xD8.toByte()) {
        val start = i; i += 2
        var closed = false
        while (i < bytes.size - 1) {
          if (bytes[i] == 0xFF.toByte() && bytes[i + 1] == 0xD9.toByte()) {
            frames += bytes.copyOfRange(start, i + 2); i += 2; closed = true; break
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
}

// [L1] FrameProvider: sem decoded cache global; decode on-demand é paralelo nos workers.
private class FrameProvider(
  private val extractor: FrameExtractor,
  private val rawCache: RamFrameCache
) {
  fun getFrame(videoFile: File, frameIndex: Int): ByteArray {
    val sourceKey = videoFile.absoluteFile.normalize().path
    val safeFrameIndex = frameIndex.coerceAtLeast(0)
    rawCache.get(sourceKey, safeFrameIndex)?.let { return it }
    return extractor.extractSingle(videoFile, safeFrameIndex).also { rawCache.put(sourceKey, safeFrameIndex, it) }
  }

  fun decodeFrame(videoFile: File, frameIndex: Int): BufferedImage {
    val bytes = getFrame(videoFile, frameIndex)
    return ImageIO.read(ByteArrayInputStream(bytes))
      ?: error("Failed to decode frame $frameIndex from ${videoFile.name}.")
  }

  fun decodeFrameOrFallback(videoFile: File, frameIndex: Int, fallback: BufferedImage): BufferedImage {
    return try {
      decodeFrame(videoFile, frameIndex)
    } catch (e: Exception) {
      println("[VIDEO] WARNING: frame $frameIndex from ${videoFile.name} unavailable (${e.message}). Using fallback.")
      fallback
    }
  }
}

private class FrameDemandAnalyzer(private val fps: Int) {
  fun analyze(
    videoTimeline: Map<Int, List<VideoNotesSegment>>,
    frameSources: Map<String, File>,
    fallbackSource: File,
    totalFrames: Int
  ): Map<File, Int> {
    val fallbackNorm = fallbackSource.absoluteFile.normalize()
    val maxIdx = mutableMapOf<File, Int>()
    maxIdx[fallbackNorm] = 0
    val frameDurationMs = 1000.0 / fps
    val cursor = VideoTimelineCursor(videoTimeline)
    val tracks = videoTimeline.keys.sorted()
    for (frameIdx in 0 until totalFrames) {
      val timeMs = (frameIdx * frameDurationMs).toLong()
      for (track in tracks) {
        val activeSegment = cursor.activeAt(track, timeMs)
        val activeNotes = activeSegment?.notes ?: emptyList()
        for (note in activeNotes) {
          val source = frameSources[note.note] ?: continue
          val normalized = source.absoluteFile.normalize()
          val sampleFrameIdx = ((timeMs - note.start) * fps / 1000.0).toInt().coerceAtLeast(0)
          maxIdx[normalized] = maxOf(maxIdx[normalized] ?: 0, sampleFrameIdx)
        }
      }
    }
    return maxIdx
  }
}

private class FramePrefetcher(
  private val extractor: FrameExtractor,
  private val rawCache: RamFrameCache
) {
  fun prefetch(maxFrameIndexPerSource: Map<File, Int>) {
    val tasks = maxFrameIndexPerSource.entries.filter { (file, maxIdx) -> file.exists() && maxIdx >= 0 }.toList()
    if (tasks.isEmpty()) return
    val poolSize = minOf(8, tasks.size)
    val executor = Executors.newFixedThreadPool(poolSize)
    println("[PREFETCH] Extracting frames from ${tasks.size} sample(s) using $poolSize thread(s)...")
    try {
      val futures = tasks.map { (source, maxIdx) ->
        executor.submit {
          val frameCount = maxIdx + 8
          val sourceKey = source.absoluteFile.normalize().path
          val frames = extractor.extractAll(source, frameCount)
          frames.forEachIndexed { index, frameBytes -> rawCache.put(sourceKey, index, frameBytes) }
          println("[PREFETCH] ${source.name}: ${frames.size} frame(s) extracted")
        }
      }
      futures.forEach { it.get() }
    } finally {
      executor.shutdown()
    }
    println("[PREFETCH] Done. ${rawCache.stats()}")
  }
}

data class GridLayout(val cols: Int, val rows: Int) {
  companion object {
    fun forNoteCount(count: Int): GridLayout = when (count) {
      0, 1 -> GridLayout(1, 1)
      2 -> GridLayout(2, 1)
      3 -> GridLayout(3, 1)
      4 -> GridLayout(2, 2)
      5, 6 -> GridLayout(3, 2)
      7, 8 -> GridLayout(4, 2)
      9 -> GridLayout(3, 3)
      else -> {
        val cols = ceil(kotlin.math.sqrt(count.toDouble())).toInt()
        val rows = ceil(count.toDouble() / cols).toInt()
        GridLayout(cols, rows)
      }
    }
  }
}

private class VideoCompositor(
  private val fps: Int,
  private val frameProvider: FrameProvider,
  private val fallbackCache: FallbackFrameCache
) {
  fun renderToCompositeCanvas(
    g2d: java.awt.Graphics2D,
    tracksWithActiveNotes: List<List<NoteEvent>>,
    outerLayout: GridLayout,
    timeMs: Long,
    frameSources: Map<String, File>,
    fallbackImage: BufferedImage,
    canvasW: Int,
    canvasH: Int
  ) {
    g2d.color = Color.BLACK
    g2d.fillRect(0, 0, canvasW, canvasH)

    for (trackIdx in tracksWithActiveNotes.indices) {
      val activeNotes = tracksWithActiveNotes[trackIdx]

      val col = trackIdx % outerLayout.cols
      val row = trackIdx / outerLayout.cols
      val cellX0 = col * canvasW / outerLayout.cols
      val cellX1 = (col + 1) * canvasW / outerLayout.cols
      val cellY0 = row * canvasH / outerLayout.rows
      val cellY1 = (row + 1) * canvasH / outerLayout.rows
      val cellW = cellX1 - cellX0
      val cellH = cellY1 - cellY0

      if (activeNotes.isEmpty()) {
        drawFallbackInCell(g2d, fallbackImage, cellX0, cellY0, cellW, cellH)
        continue
      }

      val innerLayout = GridLayout.forNoteCount(activeNotes.size)
      for (noteIdx in activeNotes.indices) {
        val note = activeNotes[noteIdx]
        val innerCol = noteIdx % innerLayout.cols
        val innerRow = noteIdx / innerLayout.cols
        val subX0 = cellX0 + innerCol * cellW / innerLayout.cols
        val subX1 = cellX0 + (innerCol + 1) * cellW / innerLayout.cols
        val subY0 = cellY0 + innerRow * cellH / innerLayout.rows
        val subY1 = cellY0 + (innerRow + 1) * cellH / innerLayout.rows
        val subW = subX1 - subX0
        val subH = subY1 - subY0

        val source = frameSources[note.note]
        if (source == null) {
          drawFallbackInCell(g2d, fallbackImage, subX0, subY0, subW, subH)
          continue
        }
        val sampleFrameIdx = ((timeMs - note.start) * fps / 1000.0).toInt().coerceAtLeast(0)
        val img = frameProvider.decodeFrameOrFallback(source, sampleFrameIdx, fallbackImage)
        drawNoteInCell(g2d, img, subX0, subY0, subW, subH)
      }
    }
  }

  private fun drawFallbackInCell(
    g2d: java.awt.Graphics2D,
    img: BufferedImage,
    cellX0: Int, cellY0: Int, cellW: Int, cellH: Int
  ) {
    val (drawW, drawH) = fitDimensions(img, cellW, cellH)
    if (drawW == img.width && drawH == img.height) {
      drawCentered(g2d, img, cellX0, cellY0, cellW, cellH, drawW, drawH)
      return
    }
    val cached = fallbackCache.get(drawW, drawH)
    if (cached != null) {
      drawCentered(g2d, cached, cellX0, cellY0, cellW, cellH, drawW, drawH)
      return
    }
    val resized = resize(img, drawW, drawH)
    fallbackCache.put(drawW, drawH, resized)
    drawCentered(g2d, resized, cellX0, cellY0, cellW, cellH, drawW, drawH)
  }

  private fun drawNoteInCell(
    g2d: java.awt.Graphics2D,
    img: BufferedImage,
    cellX0: Int, cellY0: Int, cellW: Int, cellH: Int
  ) {
    val (drawW, drawH) = fitDimensions(img, cellW, cellH)
    if (drawW == img.width && drawH == img.height) {
      drawCentered(g2d, img, cellX0, cellY0, cellW, cellH, drawW, drawH)
    } else {
      val resized = resize(img, drawW, drawH)
      drawCentered(g2d, resized, cellX0, cellY0, cellW, cellH, drawW, drawH)
    }
  }

  private fun fitDimensions(img: BufferedImage, cellW: Int, cellH: Int): Pair<Int, Int> {
    val imgAspect = img.width.toDouble() / img.height
    val cellAspect = cellW.toDouble() / cellH
    return if (imgAspect > cellAspect) {
      cellW to (cellW / imgAspect).toInt().coerceAtLeast(1)
    } else {
      (cellH * imgAspect).toInt().coerceAtLeast(1) to cellH
    }
  }

  private fun drawCentered(
    g2d: java.awt.Graphics2D,
    img: BufferedImage,
    cellX0: Int, cellY0: Int, cellW: Int, cellH: Int,
    drawW: Int, drawH: Int
  ) {
    val drawX = cellX0 + (cellW - drawW) / 2
    val drawY = cellY0 + (cellH - drawH) / 2
    g2d.drawImage(img, drawX, drawY, null)
  }

  private fun resize(img: BufferedImage, w: Int, h: Int): BufferedImage {
    val resized = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    val g = resized.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    g.drawImage(img, 0, 0, w, h, null)
    g.dispose()
    return resized
  }
}

private data class ChunkContext(
  val videoTimeline: Map<Int, List<VideoNotesSegment>>,
  val tracks: List<Int>,
  val outerLayout: GridLayout,
  val frameSources: Map<String, File>,
  val fallbackImage: BufferedImage,
  val canvasW: Int,
  val canvasH: Int,
  val frameDurationMs: Double
)

private data class ChunkResult(val chunkIndex: Int, val frameCount: Int, val buffer: ByteArray)

@Suppress("DuplicatedCode")
class MemoryVideoRenderer(
  private val fps: Int = 60,
  maxCacheMb: Int = 512,
  private val workerCount: Int = 0,
  private val chunkFrameCount: Int = 12
) {
  private val rawCache = RamFrameCache(maxBytes = maxCacheMb.toLong() * 1024 * 1024)
  private val fallbackCache = FallbackFrameCache()
  private val extractor = FrameExtractor(fps)
  private val frameProvider = FrameProvider(extractor, rawCache)
  private val compositor = VideoCompositor(fps, frameProvider, fallbackCache)
  private val demandAnalyzer = FrameDemandAnalyzer(fps)
  private val prefetcher = FramePrefetcher(extractor, rawCache)

  init {
    require(fps > 0) { "Video FPS must be greater than zero." }
    require(maxCacheMb > 0) { "Frame cache size must be greater than zero." }
    require(chunkFrameCount > 0) { "Chunk frame count must be greater than zero." }
  }

  fun renderVideo(
    videoTimeline: Map<Int, List<VideoNotesSegment>>,
    frameSources: Map<String, File>,
    masterAudioWav: File,
    outputMp4: File
  ) {
    require(videoTimeline.isNotEmpty()) { "Cannot render video from an empty timeline." }
    require(masterAudioWav.exists()) { "Master audio file not found: ${masterAudioWav.absolutePath}" }

    val totalMs = videoTimeline.values.asSequence().flatten().maxOfOrNull { it.start + it.duration } ?: 0L
    val frameDurationMs = 1000.0 / fps
    val totalFrames = ceil(totalMs / frameDurationMs).toInt()
    require(totalFrames > 0) { "Timeline does not contain any renderable frames." }

    val fallbackSource = frameSources[SampleKeys.PAUSE]
      ?: frameSources.entries.firstOrNull { it.key != SampleKeys.PAUSE }?.value
      ?: error("No samples available; cannot determine fallback source.")

    val tracks = videoTimeline.keys.sorted()
    val outerLayout = GridLayout.forNoteCount(tracks.size)
    println("[VIDEO] Tracks detected: ${tracks.size} -> outer layout ${outerLayout.cols}x${outerLayout.rows}")

    println("[VIDEO] Analyzing frame demand per sample...")
    val maxFrameIndexPerSource = demandAnalyzer.analyze(videoTimeline, frameSources, fallbackSource, totalFrames)
    prefetcher.prefetch(maxFrameIndexPerSource)

    val referenceSource = frameSources.entries.firstOrNull { it.key != SampleKeys.PAUSE }?.value
      ?: fallbackSource
    val referenceImg = frameProvider.decodeFrame(referenceSource, 0)
    val canvasW = referenceImg.width
    val canvasH = referenceImg.height
    println("[VIDEO] Reference sample: ${referenceSource.name} — output resolution set to ${canvasW}x${canvasH}.")

    // [L1] Decodifica o frame de fallback/pausa UMA vez e reaproveita em todos os frames.
    val fallbackImage = if (fallbackSource.absoluteFile.normalize() == referenceSource.absoluteFile.normalize())
      referenceImg
    else
      frameProvider.decodeFrame(fallbackSource, 0)

    val encoderConfig = EncoderSelector.select()

    val ffmpegArgs = mutableListOf(
      "ffmpeg", "-y",
      "-f", "rawvideo", "-pixel_format", "bgr24",
      "-video_size", "${canvasW}x${canvasH}",
      "-framerate", fps.toString(),
      "-i", "pipe:0",
      "-i", masterAudioWav.absolutePath,
      "-c:v", encoderConfig.name
    )
    ffmpegArgs += encoderConfig.params
    ffmpegArgs += listOf(
      "-pix_fmt", "yuv420p",
      "-c:a", "aac", "-b:a", "192k",
      "-shortest",
      outputMp4.absolutePath
    )

    val process = ProcessBuilder(ffmpegArgs)
      .redirectError(ProcessBuilder.Redirect.INHERIT)
      .start()

    val effectiveWorkers = if (workerCount > 0) workerCount
    else maxOf(1, minOf(Runtime.getRuntime().availableProcessors() - 1, 8))

    // [M2] maxInFlight = workers (antes era workers*2), reduzindo buffers em voo.
    val maxInFlight = effectiveWorkers

    val numChunks = (totalFrames + chunkFrameCount - 1) / chunkFrameCount
    println("[VIDEO] Streaming $totalFrames frames at ${canvasW}x${canvasH}.")
    println("[VIDEO] Parallel mode: $effectiveWorkers worker(s), $chunkFrameCount frame(s)/chunk, $numChunks chunk(s).")
    println("[FRAME CACHE] Initial: ${rawCache.stats()}")

    val ctx = ChunkContext(
      videoTimeline = videoTimeline,
      tracks = tracks,
      outerLayout = outerLayout,
      frameSources = frameSources,
      fallbackImage = fallbackImage,
      canvasW = canvasW,
      canvasH = canvasH,
      frameDurationMs = frameDurationMs
    )

    val frameBytes = canvasW * canvasH * 3
    // [M3] Pool com 1 buffer por worker; o writer devolve após escrever no pipe.
    val bufferPool = ChunkBufferPool(chunkFrameCount * frameBytes, effectiveWorkers)

    val executor = Executors.newFixedThreadPool(effectiveWorkers) { runnable ->
      Thread(runnable, "video-chunk-worker").apply { isDaemon = true }
    }
    val pending = ArrayDeque<Future<ChunkResult>>()
    var nextChunk = 0
    var writtenFrames = 0

    try {
      process.outputStream.use { pipeOut ->
        while (nextChunk < numChunks || pending.isNotEmpty()) {
          while (nextChunk < numChunks && pending.size < maxInFlight) {
            val ci = nextChunk
            val startFrame = ci * chunkFrameCount
            val endFrame = minOf(startFrame + chunkFrameCount, totalFrames)
            pending.addLast(executor.submit(Callable { renderChunk(ci, startFrame, endFrame, ctx, bufferPool) }))
            nextChunk++
          }
          val result = pending.removeFirst().get()
          pipeOut.write(result.buffer, 0, result.frameCount * frameBytes)
          bufferPool.release(result.buffer)
          val previous = writtenFrames
          writtenFrames += result.frameCount
          if (writtenFrames / 1000 > previous / 1000) {
            println(); println("[VIDEO] Frame $writtenFrames / $totalFrames")
            Profiler.logMemory("Video rendering")
            println("[FRAME CACHE] ${rawCache.stats()}")
          }
        }
        pipeOut.flush()
      }
      check(process.waitFor() == 0) { "FFmpeg video encoding failed." }
    } finally {
      executor.shutdownNow()
    }
    println(); println("[FRAME CACHE] Final: ${rawCache.stats()}")
  }

  private fun renderChunk(
    chunkIndex: Int,
    startFrame: Int,
    endFrame: Int,
    ctx: ChunkContext,
    pool: ChunkBufferPool
  ): ChunkResult {
    val frameCount = endFrame - startFrame
    val frameBytes = ctx.canvasW * ctx.canvasH * 3
    val output = pool.acquire()
    // [L2] Canvas 3-byte BGR: cópia direta via System.arraycopy, sem loop por pixel.
    val canvas = BufferedImage(ctx.canvasW, ctx.canvasH, BufferedImage.TYPE_3BYTE_BGR)
    val g2d = canvas.createGraphics()
    g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    val cursor = VideoTimelineCursor(ctx.videoTimeline)

    try {
      for (frameIdx in startFrame until endFrame) {
        val timeMs = (frameIdx * ctx.frameDurationMs).toLong()
        val tracksWithActiveNotes = ctx.tracks.map { track ->
          cursor.activeAt(track, timeMs)?.notes ?: emptyList()
        }
        compositor.renderToCompositeCanvas(
          g2d,
          tracksWithActiveNotes,
          ctx.outerLayout,
          timeMs,
          ctx.frameSources,
          ctx.fallbackImage,
          ctx.canvasW,
          ctx.canvasH
        )
        copyCanvasToOutput(canvas, output, (frameIdx - startFrame) * frameBytes)
      }
    } finally {
      g2d.dispose()
    }
    return ChunkResult(chunkIndex, frameCount, output)
  }

  // [L2] Cópia em bloco dos bytes do raster para o buffer de saída.
// TYPE_3BYTE_BGR criado via new BufferedImage() é sempre contíguo (w*h*3, sem padding).
  private fun copyCanvasToOutput(canvas: BufferedImage, output: ByteArray, outputOffset: Int) {
    val data = (canvas.raster.dataBuffer as DataBufferByte).data
    System.arraycopy(data, 0, output, outputOffset, canvas.width * canvas.height * 3)
  }
}

// ============================================================
// 7. Application / pipeline
// ============================================================

data class SamplerConfig(
  val frameCacheMaxMb: Int = 512,
  val audioChunkSeconds: Int = 10,
  val audioSampleRate: Int = 48_000,
  val videoFps: Int = 60,
  val videoWorkers: Int = 0,
  val videoChunkFrames: Int = 12,
  val samplesDir: File = File("samples"),
  val midiFile: File = File("input.mid"),
  val cacheDir: File = File("render_cache"),
  val outputFile: File = File("output.mp4")
)

class PipelineContext(val config: SamplerConfig) {
  val pauseFile = File(config.samplesDir, "pause.mp4")
  val masterWav = File(config.cacheDir, "master_audio.wav")
  val audioSynth =
    AudioSynthesizer(sampleRate = config.audioSampleRate, chunkDurationSeconds = config.audioChunkSeconds)
  val videoRenderer = MemoryVideoRenderer(
    fps = config.videoFps,
    maxCacheMb = config.frameCacheMaxMb,
    workerCount = config.videoWorkers,
    chunkFrameCount = config.videoChunkFrames
  )
  val pcmSamples = mutableMapOf<String, AudioSample>()
  val frameSources = mutableMapOf<String, File>()
  var notes: List<NoteEvent> = emptyList()
  var timeline: List<TimelineEvent> = emptyList()
  var videoTimeline: Map<Int, List<VideoNotesSegment>> = emptyMap()
  var uniqueNotes: Set<String> = emptySet()
}

class SamplerPipeline(private val config: SamplerConfig = SamplerConfig()) {
  fun execute() {
    println("=== MIDI VIDEO SAMPLER ===")
    config.cacheDir.mkdirs()
    val context = PipelineContext(config)
    readMidiStep(context); initEnginesStep(context); loadAudioStep(context)
    synthesizeAudioStep(context); renderVideoStep(context); cleanupStep(context)
  }

  private fun readMidiStep(context: PipelineContext) {
    println(); println("[1/5] Reading MIDI...")
    context.notes = MidiEventReader().read(context.config.midiFile)
    context.timeline = MidiTimeline().create(context.notes)
    context.videoTimeline = VideoTimeline().create(context.notes)
    context.uniqueNotes = context.notes.map { it.note }.toSet()
    val trackCount = context.videoTimeline.size
    val segmentCount = context.videoTimeline.values.sumOf { it.size }
    val maxSimultaneous = context.videoTimeline.values.flatten().maxOfOrNull { it.notes.size } ?: 0
    println("[MIDI] Found ${context.notes.size} notes using ${context.uniqueNotes.size} unique pitches across $trackCount MIDI track(s).")
    println("[MIDI] Audio timeline events: ${context.timeline.size}")
    println("[VIDEO] Video timeline created with $segmentCount segment(s) across $trackCount track(s) (max simultaneous notes within a single track: $maxSimultaneous).")
  }

  @Suppress("unused")
  private fun initEnginesStep(context: PipelineContext) {
    println(); println("[2/5] Initializing rendering engines...")
  }

  private fun loadAudioStep(context: PipelineContext) {
    println(); println("[3/5] Loading audio samples...")
    val loader = AudioSampleLoader(context.config.audioSampleRate, WavReader())
    Profiler.measure("Loading audio samples") {
      for (note in context.uniqueNotes) {
        val sampleVideo = File(context.config.samplesDir, "$note.mp4")
        if (!sampleVideo.exists()) {
          println("[WARNING] Missing sample for note $note: ${sampleVideo.absolutePath}"); continue
        }
        println("[AUDIO] Loading: ${sampleVideo.name}")
        context.pcmSamples[note] = loader.extractSamplePcm(
          videoFile = sampleVideo,
          tempWav = File(context.config.cacheDir, "sample_$note.wav")
        )
        context.frameSources[note] = sampleVideo
      }
      if (context.pauseFile.exists()) {
        println("[AUDIO] Loading pause sample...")
        context.pcmSamples[SampleKeys.PAUSE] = loader.extractSamplePcm(
          videoFile = context.pauseFile,
          tempWav = File(context.config.cacheDir, "sample_pause.wav")
        )
        context.frameSources[SampleKeys.PAUSE] = context.pauseFile
      } else {
        println("[WARNING] No pause sample found. Timeline gaps will use the fallback frame.")
      }
    }
    require(context.frameSources.isNotEmpty()) { "No video samples were found in ${context.config.samplesDir.absolutePath}." }
  }

  private fun synthesizeAudioStep(context: PipelineContext) {
    println(); println("[4/5] Synthesizing master audio...")
    Profiler.measure("Synthesizing master audio") {
      context.audioSynth.synthesize(
        timeline = context.timeline,
        samples = context.pcmSamples,
        outputFile = context.masterWav
      )
    }
  }

  private fun renderVideoStep(context: PipelineContext) {
    println(); println("[5/5] Rendering final MP4...")
    println("[CONFIG] Frame cache limit: ${context.config.frameCacheMaxMb} MB")
    println("[CONFIG] Audio chunk size: ${context.config.audioChunkSeconds} seconds")
    println("[CONFIG] Video FPS: ${context.config.videoFps}")
    println("[CONFIG] Video workers: ${if (context.config.videoWorkers <= 0) "auto" else context.config.videoWorkers.toString()}")
    println("[CONFIG] Video chunk frames: ${context.config.videoChunkFrames}")
    Profiler.measure("Rendering and encoding final MP4") {
      context.videoRenderer.renderVideo(
        videoTimeline = context.videoTimeline,
        frameSources = context.frameSources,
        masterAudioWav = context.masterWav,
        outputMp4 = context.config.outputFile
      )
    }
  }

  private fun cleanupStep(context: PipelineContext) {
    println(); println("[CLEANUP] Removing temporary files...")
    context.config.cacheDir.deleteRecursively()
    println("[CLEANUP] Temporary files removed."); println()
    println("=== PROCESSING COMPLETED SUCCESSFULLY ===")
    println("Output: ${context.config.outputFile.absolutePath}")
  }
}

fun main() {
  SamplerPipeline().execute()
}

/*
=== MIDI VIDEO SAMPLER ===

[1/5] Reading MIDI...
[MIDI] Found 588 notes using 34 unique pitches across 4 MIDI track(s).
[MIDI] Audio timeline events: 706
[VIDEO] Video timeline created with 1174 segment(s) across 4 track(s) (max simultaneous notes within a single track: 1).

[2/5] Initializing rendering engines...

[3/5] Loading audio samples...

[PROFILER] Starting: Loading audio samples
[PROFILER] Memory � Before Loading audio samples: 11 MB used / 3916 MB JVM maximum
[AUDIO] Loading: F4.mp4
[AUDIO] Loading: D4.mp4
[AUDIO] Loading: C#5.mp4
[AUDIO] Loading: D5.mp4
[AUDIO] Loading: A4.mp4
[AUDIO] Loading: A5.mp4
[AUDIO] Loading: A#5.mp4
[AUDIO] Loading: G4.mp4
[AUDIO] Loading: E4.mp4
[AUDIO] Loading: C6.mp4
[AUDIO] Loading: D6.mp4
[AUDIO] Loading: G5.mp4
[AUDIO] Loading: E5.mp4
[AUDIO] Loading: F5.mp4
[WARNING] Missing sample for note F6: C:\Users\Usuario\Documents\projects\FL-vSampler\samples\F6.mp4
[AUDIO] Loading: C#6.mp4
[AUDIO] Loading: C4.mp4
[AUDIO] Loading: C5.mp4
[AUDIO] Loading: B5.mp4
[AUDIO] Loading: G#5.mp4
[AUDIO] Loading: D#6.mp4
[AUDIO] Loading: D#5.mp4
[WARNING] Missing sample for note E6: C:\Users\Usuario\Documents\projects\FL-vSampler\samples\E6.mp4
[AUDIO] Loading: A#4.mp4
[WARNING] Missing sample for note F#6: C:\Users\Usuario\Documents\projects\FL-vSampler\samples\F#6.mp4
[AUDIO] Loading: F#5.mp4
[WARNING] Missing sample for note G6: C:\Users\Usuario\Documents\projects\FL-vSampler\samples\G6.mp4
[AUDIO] Loading: B4.mp4
[WARNING] Missing sample for note G#6: C:\Users\Usuario\Documents\projects\FL-vSampler\samples\G#6.mp4
[WARNING] Missing sample for note A6: C:\Users\Usuario\Documents\projects\FL-vSampler\samples\A6.mp4
[AUDIO] Loading: C#4.mp4
[AUDIO] Loading: D#4.mp4
[AUDIO] Loading: G#4.mp4
[AUDIO] Loading: B3.mp4
[AUDIO] Loading pause sample...
[PROFILER] Memory � After Loading audio samples: 130 MB used / 3916 MB JVM maximum
[PROFILER] Completed: Loading audio samples in 00:01.822

[4/5] Synthesizing master audio...

[PROFILER] Starting: Synthesizing master audio
[PROFILER] Memory � Before Synthesizing master audio: 131 MB used / 3916 MB JVM maximum
[AUDIO] Master duration: 205.711s
[AUDIO] Total samples: 9922128
[AUDIO] Chunk duration: 10 s
[AUDIO] Samples per chunk: 480000

[AUDIO] Pass 1/2: calculating global peak...
[AUDIO] Peak analysis � chunk 1/21
[AUDIO] Peak analysis � chunk 2/21
[AUDIO] Peak analysis � chunk 3/21
[AUDIO] Peak analysis � chunk 4/21
[AUDIO] Peak analysis � chunk 5/21
[AUDIO] Peak analysis � chunk 6/21
[AUDIO] Peak analysis � chunk 7/21
[AUDIO] Peak analysis � chunk 8/21
[AUDIO] Peak analysis � chunk 9/21
[AUDIO] Peak analysis � chunk 10/21
[AUDIO] Peak analysis � chunk 11/21
[AUDIO] Peak analysis � chunk 12/21
[AUDIO] Peak analysis � chunk 13/21
[AUDIO] Peak analysis � chunk 14/21
[AUDIO] Peak analysis � chunk 15/21
[AUDIO] Peak analysis � chunk 16/21
[AUDIO] Peak analysis � chunk 17/21
[AUDIO] Peak analysis � chunk 18/21
[AUDIO] Peak analysis � chunk 19/21
[AUDIO] Peak analysis � chunk 20/21
[AUDIO] Peak analysis � chunk 21/21
[AUDIO] Global peak: 0.39120173
[AUDIO] Normalization scale: 1.0

[AUDIO] Pass 2/2: writing normalized WAV...
[AUDIO] WAV writing � chunk 1/21
[AUDIO] WAV writing � chunk 2/21
[AUDIO] WAV writing � chunk 3/21
[AUDIO] WAV writing � chunk 4/21
[AUDIO] WAV writing � chunk 5/21
[AUDIO] WAV writing � chunk 6/21
[AUDIO] WAV writing � chunk 7/21
[AUDIO] WAV writing � chunk 8/21
[AUDIO] WAV writing � chunk 9/21
[AUDIO] WAV writing � chunk 10/21
[AUDIO] WAV writing � chunk 11/21
[AUDIO] WAV writing � chunk 12/21
[AUDIO] WAV writing � chunk 13/21
[AUDIO] WAV writing � chunk 14/21
[AUDIO] WAV writing � chunk 15/21
[AUDIO] WAV writing � chunk 16/21
[AUDIO] WAV writing � chunk 17/21
[AUDIO] WAV writing � chunk 18/21
[AUDIO] WAV writing � chunk 19/21
[AUDIO] WAV writing � chunk 20/21
[AUDIO] WAV writing � chunk 21/21
[AUDIO] Audio synthesis completed.
[AUDIO] Output: C:\Users\Usuario\Documents\projects\FL-vSampler\render_cache\master_audio.wav
[PROFILER] Memory � After Synthesizing master audio: 178 MB used / 3916 MB JVM maximum
[PROFILER] Completed: Synthesizing master audio in 00:00.281

[5/5] Rendering final MP4...
[CONFIG] Frame cache limit: 512 MB
[CONFIG] Resized cache limit: 256 MB
[CONFIG] Audio chunk size: 10 seconds
[CONFIG] Video FPS: 60
[CONFIG] Video workers: auto
[CONFIG] Video chunk frames: 12

[PROFILER] Starting: Rendering and encoding final MP4
[PROFILER] Memory � Before Rendering and encoding final MP4: 178 MB used / 3916 MB JVM maximum
[VIDEO] Reference sample: F4.mp4 � output resolution set to 720x1280.
[VIDEO] Tracks detected: 4 -> outer layout 2x2
[VIDEO] Analyzing frame demand per sample...
[PREFETCH] Extracting frames from 29 sample(s) using 8 thread(s)...
[PREFETCH] F4.mp4: 104 frame(s) extracted
[PREFETCH] C#5.mp4: 104 frame(s) extracted
[PREFETCH] A5.mp4: 138 frame(s) extracted
[PREFETCH] A#5.mp4: 138 frame(s) extracted
[PREFETCH] pause.mp4: 172 frame(s) extracted
[PREFETCH] D4.mp4: 412 frame(s) extracted
[PREFETCH] D5.mp4: 412 frame(s) extracted
[PREFETCH] E4.mp4: 104 frame(s) extracted
[PREFETCH] A4.mp4: 482 frame(s) extracted
[PREFETCH] C6.mp4: 138 frame(s) extracted
[PREFETCH] G5.mp4: 173 frame(s) extracted
[PREFETCH] G4.mp4: 413 frame(s) extracted
[PREFETCH] E5.mp4: 138 frame(s) extracted
[PREFETCH] C#6.mp4: 138 frame(s) extracted
[PREFETCH] D6.mp4: 413 frame(s) extracted
[PREFETCH] C4.mp4: 104 frame(s) extracted
[PREFETCH] B5.mp4: 104 frame(s) extracted
[PREFETCH] C5.mp4: 173 frame(s) extracted
[PREFETCH] F5.mp4: 310 frame(s) extracted
[PREFETCH] G#5.mp4: 69 frame(s) extracted
[PREFETCH] D#5.mp4: 104 frame(s) extracted
[PREFETCH] D#6.mp4: 138 frame(s) extracted
[PREFETCH] D#4.mp4: 35 frame(s) extracted
[PREFETCH] C#4.mp4: 104 frame(s) extracted
[PREFETCH] B4.mp4: 104 frame(s) extracted
[PREFETCH] A#4.mp4: 413 frame(s) extracted
[PREFETCH] G#4.mp4: 104 frame(s) extracted
[PREFETCH] B3.mp4: 104 frame(s) extracted
[PREFETCH] F#5.mp4: 412 frame(s) extracted
[PREFETCH] Done. Frames=5757 | RAM=347.21/512.00 MB | Hits=0 | Misses=2 | Evictions=0
[ENCODER] Hardware encoders present in FFmpeg: [h264_amf, h264_nvenc, h264_qsv]
[ENCODER] Probing h264_amf... OK
[ENCODER] Using hardware encoder: h264_amf
[VIDEO] Streaming 12343 frames at 720x1280.
[VIDEO] Parallel mode: 8 worker(s), 12 frame(s)/chunk, 1029 chunk(s).
[FRAME CACHE] Initial: Frames=5757 | RAM=347.21/512.00 MB | Hits=0 | Misses=2 | Evictions=0
ffmpeg version 8.1-full_build-www.gyan.dev Copyright (c) 2000-2026 the FFmpeg developers
  built with gcc 15.2.0 (Rev11, Built by MSYS2 project)
  configuration: --enable-gpl --enable-version3 --enable-shared --disable-w32threads --disable-autodetect --enable-cairo --enable-fontconfig --enable-iconv --enable-gnutls --enable-lcms2 --enable-libxml2 --enable-gmp --enable-bzlib --enable-lzma --enable-libsnappy --enable-zlib --enable-librist --enable-libsrt --enable-libssh --enable-libzmq --enable-avisynth --enable-libbluray --enable-libcaca --enable-libdvdnav --enable-libdvdread --enable-sdl2 --enable-libaribb24 --enable-libaribcaption --enable-libdav1d --enable-libdavs2 --enable-libopenjpeg --enable-libquirc --enable-libuavs3d --enable-libxevd --enable-libzvbi --enable-liboapv --enable-libqrencode --enable-librav1e --enable-libsvtav1 --enable-libvvenc --enable-libwebp --enable-libx264 --enable-libx265 --enable-libxavs2 --enable-libxeve --enable-libxvid --enable-libaom --enable-libjxl --enable-libsvtjpegxs --enable-libvpx --enable-mediafoundation --enable-libass --enable-frei0r --enable-libfreetype --enable-libfribidi --enable-libharfbuzz --enable-liblensfun --enable-libvidstab --enable-libvmaf --enable-libzimg --enable-amf --enable-cuda-llvm --enable-cuvid --enable-dxva2 --enable-d3d11va --enable-d3d12va --enable-ffnvcodec --enable-libvpl --enable-nvdec --enable-nvenc --enable-vaapi --enable-libshaderc --enable-vulkan --enable-libplacebo --enable-opencl --enable-libcdio --enable-openal --enable-libgme --enable-libmodplug --enable-libopenmpt --enable-libopencore-amrwb --enable-libmp3lame --enable-libshine --enable-libtheora --enable-libtwolame --enable-libvo-amrwbenc --enable-libcodec2 --enable-libilbc --enable-libgsm --enable-liblc3 --enable-libopencore-amrnb --enable-libopus --enable-libspeex --enable-libvorbis --enable-ladspa --enable-libbs2b --enable-libflite --enable-libmysofa --enable-librubberband --enable-libsoxr --enable-chromaprint --enable-whisper
  libavutil      60. 26.100 / 60. 26.100
  libavcodec     62. 28.100 / 62. 28.100
  libavformat    62. 12.100 / 62. 12.100
  libavdevice    62.  3.100 / 62.  3.100
  libavfilter    11. 14.100 / 11. 14.100
  libswscale      9.  5.100 /  9.  5.100
  libswresample   6.  3.100 /  6.  3.100
Input #0, rawvideo, from 'pipe:0':
  Duration: N/A, start: 0.000000, bitrate: 1327104 kb/s
  Stream #0:0: Video: rawvideo (RGB[24] / 0x18424752), rgb24, 720x1280, 1327104 kb/s, 60 tbr, 60 tbn
[aist#1:0/pcm_s16le @ 0000020793169540] Guessed Channel Layout: stereo
Input #1, wav, from 'C:\Users\Usuario\Documents\projects\FL-vSampler\render_cache\master_audio.wav':
  Duration: 00:03:26.71, bitrate: 1536 kb/s
  Stream #1:0: Audio: pcm_s16le ([1][0][0][0] / 0x0001), 48000 Hz, stereo, s16, 1536 kb/s
Stream mapping:
  Stream #0:0 -> #0:0 (rawvideo (native) -> h264 (h264_amf))
  Stream #1:0 -> #0:1 (pcm_s16le (native) -> aac (native))
[h264_amf @ 0000020794ecb380] VBAQ is not supported by cqp Rate Control Method, automatically disabled
Output #0, mp4, to 'C:\Users\Usuario\Documents\projects\FL-vSampler\output.mp4':
  Metadata:
    encoder         : Lavf62.12.100
  Stream #0:0: Video: h264 (avc1 / 0x31637661), yuv420p(tv, progressive), 720x1280, q=2-31, 60 fps, 15360 tbn
    Metadata:
      encoder         : Lavc62.28.100 h264_amf
  Stream #0:1: Audio: aac (LC) (mp4a / 0x6134706D), 48000 Hz, stereo, fltp, 192 kb/s
    Metadata:
      encoder         : Lavc62.28.100 aac
frame=  959 fps=143 q=-0.0 size=    4608KiB time=00:00:15.96 bitrate=2364.2kbits/s speed=2.38x elapsed=0:00:06.72
[VIDEO] Frame 1008 / 12343
[PROFILER] Memory � Video rendering: 2420 MB used / 3916 MB JVM maximum
[FRAME CACHE] Frames=5757 | RAM=347.21/512.00 MB | Hits=2112 | Misses=2 | Evictions=0
[RESIZED CACHE] Resized entries=309 | RAM=255.34/256.00 MB | Hits=2514 | Misses=1724 | Evictions=1409
frame= 1976 fps=127 q=-0.0 size=   11520KiB time=00:00:32.91 bitrate=2867.0kbits/s speed=2.12x elapsed=0:00:15.53
[VIDEO] Frame 2004 / 12343
[PROFILER] Memory � Video rendering: 3501 MB used / 3916 MB JVM maximum
[FRAME CACHE] Frames=5757 | RAM=347.21/512.00 MB | Hits=4808 | Misses=2 | Evictions=0
[RESIZED CACHE] Resized entries=291 | RAM=255.16/256.00 MB | Hits=4425 | Misses=4040 | Evictions=3729
frame= 2942 fps=126 q=-0.0 size=   17408KiB time=00:00:49.01 bitrate=2909.4kbits/s speed=2.11x elapsed=0:00:23.25
[VIDEO] Frame 3000 / 12343
[PROFILER] Memory � Video rendering: 2726 MB used / 3916 MB JVM maximum
[FRAME CACHE] Frames=5757 | RAM=347.21/512.00 MB | Hits=7566 | Misses=2 | Evictions=0
[RESIZED CACHE] Resized entries=339 | RAM=255.24/256.00 MB | Hits=5937 | Misses=6282 | Evictions=5921
frame= 3962 fps=122 q=-0.0 size=   24832KiB time=00:01:06.01 bitrate=3081.4kbits/s speed=2.03x elapsed=0:00:32.57
[VIDEO] Frame 4008 / 12343
[PROFILER] Memory � Video rendering: 3415 MB used / 3916 MB JVM maximum
[FRAME CACHE] Frames=5757 | RAM=347.21/512.00 MB | Hits=10833 | Misses=2 | Evictions=0
[RESIZED CACHE] Resized entries=325 | RAM=255.57/256.00 MB | Hits=7238 | Misses=9036 | Evictions=8656
frame= 4991 fps=119 q=-0.0 size=   32256KiB time=00:01:23.16 bitrate=3177.3kbits/s speed=1.98x elapsed=0:00:41.90
[VIDEO] Frame 5004 / 12343
[PROFILER] Memory � Video rendering: 1919 MB used / 3916 MB JVM maximum
[FRAME CACHE] Frames=5757 | RAM=347.21/512.00 MB | Hits=14117 | Misses=2 | Evictions=0
[RESIZED CACHE] Resized entries=292 | RAM=255.44/256.00 MB | Hits=8489 | Misses=11736 | Evictions=11389
frame= 5987 fps=117 q=-0.0 size=   39168KiB time=00:01:39.76 bitrate=3216.2kbits/s speed=1.95x elapsed=0:00:51.19
[VIDEO] Frame 6000 / 12343
[PROFILER] Memory � Video rendering: 3180 MB used / 3916 MB JVM maximum
[FRAME CACHE] Frames=5757 | RAM=347.21/512.00 MB | Hits=17479 | Misses=2 | Evictions=0
[RESIZED CACHE] Resized entries=291 | RAM=255.16/256.00 MB | Hits=9912 | Misses=14313 | Evictions=13957
frame= 6952 fps=114 q=-0.0 size=   46592KiB time=00:01:55.85 bitrate=3294.6kbits/s speed= 1.9x elapsed=0:01:00.97
[VIDEO] Frame 7008 / 12343
[PROFILER] Memory � Video rendering: 3599 MB used / 3916 MB JVM maximum
[FRAME CACHE] Frames=5757 | RAM=347.21/512.00 MB | Hits=21133 | Misses=2 | Evictions=0
[RESIZED CACHE] Resized entries=334 | RAM=255.66/256.00 MB | Hits=11198 | Misses=17044 | Evictions=16647
frame= 7955 fps=112 q=-0.0 size=   53760KiB time=00:02:12.56 bitrate=3322.1kbits/s speed=1.87x elapsed=0:01:10.77
[VIDEO] Frame 8004 / 12343
[PROFILER] Memory � Video rendering: 3765 MB used / 3916 MB JVM maximum
[FRAME CACHE] Frames=5757 | RAM=347.21/512.00 MB | Hits=24589 | Misses=2 | Evictions=0
[RESIZED CACHE] Resized entries=294 | RAM=255.99/256.00 MB | Hits=12166 | Misses=20071 | Evictions=19700
frame= 8989 fps=111 q=-0.0 size=   61696KiB time=00:02:29.80 bitrate=3373.9kbits/s speed=1.85x elapsed=0:01:21.11
[VIDEO] Frame 9000 / 12343
[PROFILER] Memory � Video rendering: 2064 MB used / 3916 MB JVM maximum
[FRAME CACHE] Frames=5757 | RAM=347.21/512.00 MB | Hits=27937 | Misses=2 | Evictions=0
[RESIZED CACHE] Resized entries=291 | RAM=255.16/256.00 MB | Hits=13364 | Misses=23081 | Evictions=22685
frame= 9995 fps=110 q=-0.0 size=   68352KiB time=00:02:46.56 bitrate=3361.7kbits/s speed=1.84x elapsed=0:01:30.45
[VIDEO] Frame 10008 / 12343
[PROFILER] Memory � Video rendering: 1569 MB used / 3916 MB JVM maximum
[FRAME CACHE] Frames=5757 | RAM=347.21/512.00 MB | Hits=30775 | Misses=2 | Evictions=0
[RESIZED CACHE] Resized entries=291 | RAM=255.16/256.00 MB | Hits=14641 | Misses=25585 | Evictions=25180
[VIDEO] Frame 11004 / 12343
[PROFILER] Memory � Video rendering: 3789 MB used / 3916 MB JVM maximum
[FRAME CACHE] Frames=5757 | RAM=347.21/512.00 MB | Hits=34753 | Misses=34 | Evictions=0
[RESIZED CACHE] Resized entries=302 | RAM=255.20/256.00 MB | Hits=15395 | Misses=29298 | Evictions=28883
frame=11975 fps=103 q=-0.0 size=   82688KiB time=00:03:19.56 bitrate=3394.3kbits/s speed=1.71x elapsed=0:01:56.66
[VIDEO] Frame 12000 / 12343
[PROFILER] Memory � Video rendering: 2313 MB used / 3916 MB JVM maximum
[FRAME CACHE] Frames=5757 | RAM=347.21/512.00 MB | Hits=37598 | Misses=34 | Evictions=0
[RESIZED CACHE] Resized entries=291 | RAM=255.76/256.00 MB | Hits=16394 | Misses=31838 | Evictions=31415
[out#0/mp4 @ 0000020793168340] video:80610KiB audio:4820KiB subtitle:0KiB other streams:0KiB global headers:0KiB muxing overhead: 0.266229%
frame=12343 fps=102 q=-0.0 Lsize=   85658KiB time=00:03:25.69 bitrate=3411.4kbits/s speed= 1.7x elapsed=0:02:01.34
[aac @ 0000020794f03280] Qavg: 1329.799

[FRAME CACHE] Final: Frames=5757 | RAM=347.21/512.00 MB | Hits=38731 | Misses=34 | Evictions=0
[RESIZED CACHE] Final: Resized entries=291 | RAM=255.76/256.00 MB | Hits=16394 | Misses=32978 | Evictions=32556
[PROFILER] Memory � After Rendering and encoding final MP4: 1495 MB used / 3916 MB JVM maximum
[PROFILER] Completed: Rendering and encoding final MP4 in 02:08.287

[CLEANUP] Removing temporary files...
[CLEANUP] Temporary files removed.

=== PROCESSING COMPLETED SUCCESSFULLY ===
 */