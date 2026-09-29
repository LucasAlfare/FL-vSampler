package com.lucasalfare.flvsampler

import com.lucasalfare.flmidi.*
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Arrays
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sqrt

private fun logMemory(tag: String) {
  val runtime = Runtime.getRuntime()
  val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
  val maxMb = runtime.maxMemory() / (1024 * 1024)
  println("[PROFILER] Memory — $tag: $usedMb MB used / $maxMb JVM maximum")
}

private inline fun <T> profileTime(tag: String, block: () -> T): T {
  println()
  println("[PROFILER] Starting: $tag")
  logMemory("Before $tag")
  val start = System.currentTimeMillis()
  val result = block()
  val elapsed = System.currentTimeMillis() - start
  logMemory("After $tag")
  println("[PROFILER] Completed: $tag in ${SimpleDateFormat("mm:ss.SSS").format(elapsed)}")
  return result
}

private object SampleKeys {
  const val PAUSE = "__pause__"
}

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
    val candidates = listOf(
      EncoderConfig(
        "h264_amf",
        listOf("-quality", "balanced", "-rc", "cqp", "-qp_i", "23", "-qp_p", "23", "-qp_b", "23")
      ),
      EncoderConfig("h264_nvenc", listOf("-preset", "p5", "-rc", "vbr", "-cq", "23")),
      EncoderConfig("h264_qsv", listOf("-preset", "medium", "-global_quality", "23"))
    )
    for (cfg in candidates) {
      print("[ENCODER] Probing ${cfg.name}... ")
      if (testEncode(cfg)) {
        println("OK")
        println("[ENCODER] Using hardware encoder: ${cfg.name}")
        return cfg
      }
      println("failed")
    }
    println("[ENCODER] No hardware encoder available, using libx264")
    return EncoderConfig("libx264", listOf("-preset", "fast", "-crf", "23"))
  }

  private fun testEncode(cfg: EncoderConfig): Boolean = try {
    val cmd = buildList {
      addAll(
        listOf(
          "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
          "-f", "lavfi", "-i", "testsrc=size=1280x720:rate=30:duration=0.3",
          "-c:v", cfg.name
        )
      )
      addAll(cfg.params)
      addAll(listOf("-f", "null", "-"))
    }
    ProcessBuilder(cmd)
      .redirectError(ProcessBuilder.Redirect.DISCARD)
      .redirectOutput(ProcessBuilder.Redirect.DISCARD)
      .start()
      .waitFor() == 0
  } catch (_: Exception) {
    false
  }
}

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
      for (event in events) {
        if (event.event is SetTempoMetaEvent) add(TempoEvent(event.tick, event.event.tempo))
      }
    }.toMutableList()
    if (tempos.none { it.tick == 0L }) tempos += TempoEvent(0L, DEFAULT_TEMPO)
    tempos.sortBy { it.tick }

    val active = mutableMapOf<Triple<Int, Int, Int>, MutableList<StartedNote>>()
    val result = mutableListOf<NoteEvent>()

    for (timedEvent in events) {
      val trackIndex = timedEvent.track
      when (val event = timedEvent.event) {
        is NoteOnControlEvent -> if (event.velocity == 0) {
          finish(trackIndex, event.channel, event.note, timedEvent.tick, active, tempos, midi.division, result)
        } else {
          active.getOrPut(Triple(trackIndex, event.channel, event.note), ::mutableListOf)
            .add(StartedNote(timedEvent.tick, event.velocity))
        }

        is NoteOffControlEvent ->
          finish(trackIndex, event.channel, event.note, timedEvent.tick, active, tempos, midi.division, result)
      }
    }
    return result.sortedWith(compareBy({ it.start }, { it.track }))
  }

  private fun finish(
    track: Int,
    channel: Int,
    note: Int,
    endTick: Long,
    active: MutableMap<Triple<Int, Int, Int>, MutableList<StartedNote>>,
    tempos: List<TempoEvent>,
    division: Int,
    result: MutableList<NoteEvent>
  ) {
    val key = Triple(track, channel, note)
    val notes = active[key] ?: return
    if (notes.isEmpty()) return
    val started = notes.removeAt(0)
    val start = ticksToMillis(started.tick, tempos, division)
    val end = ticksToMillis(endTick, tempos, division)
    result += NoteEvent(
      NOTE_NAMES[note % 12] + (note / 12 - 1),
      started.velocity,
      start,
      (end - start).coerceAtLeast(0L),
      track
    )
    if (notes.isEmpty()) active.remove(key)
  }

  private fun ticksToMillis(targetTick: Long, tempos: List<TempoEvent>, division: Int): Long {
    var currentTick = 0L
    var tempo = DEFAULT_TEMPO.toLong()
    var micros = 0L
    for (change in tempos) {
      if (change.tick > targetTick) break
      micros += (change.tick - currentTick) * tempo / division
      currentTick = change.tick
      tempo = change.tempo.toLong()
    }
    micros += (targetTick - currentTick) * tempo / division
    return micros / 1_000L
  }

  private data class TimedEvent(val tick: Long, val track: Int, val event: Event)
  private data class TempoEvent(val tick: Long, val tempo: Int)
  private data class StartedNote(val tick: Long, val velocity: Int)

  private companion object {
    const val DEFAULT_TEMPO = 500_000
    val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
  }
}

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
      if (note.start > position) timeline += TimelinePause(note.start - position, position)
      timeline += TimelineNote(note)
      position = maxOf(position, note.start + note.duration)
    }
    return timeline
  }
}

data class VideoNotesSegment(val start: Long, val duration: Long, val notes: List<NoteEvent>)

class VideoTimeline {
  fun create(notes: List<NoteEvent>): Map<Int, List<VideoNotesSegment>> {
    if (notes.isEmpty()) return emptyMap()
    return notes.groupBy { it.track }.mapValues { (_, trackNotes) -> createForTrack(trackNotes) }
  }

  private fun createForTrack(notes: List<NoteEvent>): List<VideoNotesSegment> {
    val valid = notes.filter { it.duration > 0 }
    if (valid.isEmpty()) return emptyList()
    val boundaries = sortedSetOf(0L)
    for (note in valid) {
      boundaries.add(note.start)
      boundaries.add(note.start + note.duration)
    }
    val sorted = boundaries.toList()
    val segments = mutableListOf<VideoNotesSegment>()
    for (i in 0 until sorted.size - 1) {
      val start = sorted[i]
      val end = sorted[i + 1]
      if (end - start <= 0) continue
      val active = valid.filter { it.start <= start && (it.start + it.duration) >= end }
      segments += VideoNotesSegment(start, end - start, active)
    }
    return segments
  }
}

private class VideoTimelineCursor(private val segmentsByTrack: Map<Int, List<VideoNotesSegment>>) {
  private val indices = mutableMapOf<Int, Int>()

  fun activeAt(track: Int, timeMs: Long): VideoNotesSegment? {
    val segments = segmentsByTrack[track] ?: return null
    var index = indices[track] ?: 0
    while (index < segments.size && timeMs >= segments[index].start + segments[index].duration) index++
    indices[track] = index
    return segments.getOrNull(index)?.takeIf { timeMs >= it.start && timeMs < it.start + it.duration }
  }
}

class AudioSample(val left: FloatArray, val right: FloatArray)

private class WavWriter {
  fun writeStreaming(file: File, totalSamples: Int, sampleRate: Int, block: (BufferedOutputStream) -> Unit) {
    val totalDataLen = totalSamples.toLong() * 4
    require(totalDataLen <= 0xFFFFFFFFL) { "WAV file is too large for classic RIFF PCM." }
    file.outputStream().buffered(64 * 1024).use { out ->
      out.write(header(totalDataLen, totalDataLen + 36, sampleRate))
      block(out)
    }
  }

  fun writeChunk(out: BufferedOutputStream, l: FloatArray, r: FloatArray, count: Int, scale: Float) {
    val buffer = ByteBuffer.allocate(count * 4).order(ByteOrder.LITTLE_ENDIAN)
    repeat(count) { i ->
      buffer.putShort(pcm16(l[i] * scale))
      buffer.putShort(pcm16(r[i] * scale))
    }
    out.write(buffer.array())
  }

  private fun header(dataLen: Long, riffSize: Long, sampleRate: Int): ByteArray {
    val h = ByteArray(44)
    fun ascii(offset: Int, s: String) = s.forEachIndexed { i, c -> h[offset + i] = c.code.toByte() }
    fun intLE(offset: Int, v: Long) = repeat(4) { h[offset + it] = (v shr (it * 8)).toByte() }
    fun shortLE(offset: Int, v: Int) {
      h[offset] = v.toByte()
      h[offset + 1] = (v shr 8).toByte()
    }
    ascii(0, "RIFF"); intLE(4, riffSize); ascii(8, "WAVE")
    ascii(12, "fmt "); intLE(16, 16); shortLE(20, 1); shortLE(22, 2)
    intLE(24, sampleRate.toLong()); intLE(28, sampleRate.toLong() * 4)
    shortLE(32, 4); shortLE(34, 16)
    ascii(36, "data"); intLE(40, dataLen)
    return h
  }

  private fun pcm16(value: Float): Short =
    (value * 32767.0f).toInt().coerceIn(-32768, 32767).toShort()
}

private class AudioSampleLoader(private val sampleRate: Int) {
  fun extract(videoFile: File, tempWav: File): AudioSample {
    require(videoFile.exists()) { "Sample video not found: ${videoFile.absolutePath}" }
    Ffmpeg.runInherit(
      "-y", "-i", videoFile.absolutePath,
      "-vn", "-ar", sampleRate.toString(), "-ac", "2",
      "-c:a", "pcm_s16le", tempWav.absolutePath
    )
    return decode(tempWav.readBytes())
  }

  private fun decode(bytes: ByteArray): AudioSample {
    if (bytes.size < 44) return AudioSample(FloatArray(0), FloatArray(0))
    val offset = findDataChunk(bytes)
    if (offset >= bytes.size) return AudioSample(FloatArray(0), FloatArray(0))
    val pcm = bytes.copyOfRange(offset, bytes.size)
    val total = pcm.size / 4
    val left = FloatArray(total)
    val right = FloatArray(total)
    ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).also { buf ->
      repeat(total) { i ->
        left[i] = buf.short / 32768.0f
        right[i] = buf.short / 32768.0f
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
}

class AudioSynthesizer(private val sampleRate: Int = 48_000, private val chunkSeconds: Int = 10) {
  private val fadeMs = 15.0
  private val writer = WavWriter()

  init {
    require(sampleRate > 0)
    require(chunkSeconds > 0)
  }

  fun synthesize(timeline: List<TimelineEvent>, samples: Map<String, AudioSample>, output: File) {
    require(timeline.isNotEmpty()) { "Cannot synthesize audio from an empty timeline." }
    val totalMs = timeline.maxOf { it.start + it.duration }
    val totalSamplesLong = (totalMs + 1_000L) * sampleRate / 1_000L
    require(totalSamplesLong <= Int.MAX_VALUE) { "Audio too long: $totalSamplesLong samples." }
    val totalSamples = totalSamplesLong.toInt()
    val chunkSamples = (chunkSeconds.toLong() * sampleRate)
      .coerceAtMost(totalSamples.toLong())
      .coerceAtLeast(1L)
      .toInt()

    println("[AUDIO] Master duration: ${totalMs / 1000.0}s")
    println("[AUDIO] Total samples: $totalSamples")
    println("[AUDIO] Chunk duration: $chunkSeconds s")
    println("[AUDIO] Samples per chunk: $chunkSamples")

    val l = FloatArray(chunkSamples)
    val r = FloatArray(chunkSamples)
    val totalChunks = (totalSamples + chunkSamples - 1) / chunkSamples

    println()
    println("[AUDIO] Pass 1/2: calculating global peak...")
    var peak = 0.0f
    var start = 0
    var n = 0
    while (start < totalSamples) {
      val count = minOf(chunkSamples, totalSamples - start)
      clear(l, r, count)
      mix(timeline, samples, l, r, start, count)
      repeat(count) { i -> peak = maxOf(peak, abs(l[i]), abs(r[i])) }
      println("[AUDIO] Peak analysis — chunk ${++n}/$totalChunks")
      start += count
    }
    val scale = if (peak > NORMALIZATION_PEAK) NORMALIZATION_PEAK / peak else 1.0f
    println("[AUDIO] Global peak: $peak")
    println("[AUDIO] Normalization scale: $scale")

    println()
    println("[AUDIO] Pass 2/2: writing normalized WAV...")
    writer.writeStreaming(output, totalSamples, sampleRate) { out ->
      var processed = 0
      var current = 0
      while (processed < totalSamples) {
        val count = minOf(chunkSamples, totalSamples - processed)
        clear(l, r, count)
        mix(timeline, samples, l, r, processed, count)
        writer.writeChunk(out, l, r, count, scale)
        println("[AUDIO] WAV writing — chunk ${++current}/$totalChunks")
        processed += count
      }
    }
    println("[AUDIO] Audio synthesis completed.")
    println("[AUDIO] Output: ${output.absolutePath}")
  }

  private fun mix(
    timeline: List<TimelineEvent>,
    samples: Map<String, AudioSample>,
    masterL: FloatArray,
    masterR: FloatArray,
    chunkStart: Int,
    chunkCount: Int
  ) {
    val fadeSamples = (fadeMs * sampleRate / 1000.0).toInt()
    val chunkEnd = chunkStart + chunkCount
    for (event in timeline) {
      val sample = samples[event.sampleKey()] ?: continue
      val velocityGain = event.velocityGain()
      val startSample = (event.start * sampleRate / 1000.0).toInt()
      val durationSamples = (event.duration * sampleRate / 1000.0).toInt()
      val maxCopy = minOf(sample.left.size, durationSamples + fadeSamples)
      if (maxCopy <= 0) continue
      if (startSample + maxCopy <= chunkStart || startSample >= chunkEnd) continue
      val sourceStart = maxOf(0, chunkStart - startSample)
      val sourceEnd = minOf(maxCopy, chunkEnd - startSample)
      for (i in sourceStart until sourceEnd) {
        val fade = if (fadeSamples > 0 && i >= durationSamples) {
          ((1.0 + cos(Math.PI * ((i - durationSamples).toFloat() / fadeSamples))) * 0.5).toFloat()
        } else 1.0f
        val gain = fade * velocityGain
        val target = startSample + i - chunkStart
        masterL[target] += sample.left[i] * gain
        masterR[target] += sample.right[i] * gain
      }
    }
  }

  private fun clear(l: FloatArray, r: FloatArray, size: Int) {
    Arrays.fill(l, 0, size, 0.0f)
    Arrays.fill(r, 0, size, 0.0f)
  }

  private fun TimelineEvent.sampleKey(): String = when (this) {
    is TimelineNote -> event.note
    is TimelinePause -> SampleKeys.PAUSE
  }

  private fun TimelineEvent.velocityGain(): Float = when (this) {
    is TimelineNote -> event.velocity.coerceIn(1, 127) / 127.0f
    is TimelinePause -> 1.0f
  }

  private companion object {
    const val NORMALIZATION_PEAK = 0.95f
  }
}

class RamFrameCache(maxBytes: Long) {
  private val limit = maxBytes.coerceAtLeast(1)

  private data class Key(val source: String, val frame: Int)

  private val cache = LinkedHashMap<Key, ByteArray>(16, 0.75f, true)
  private var bytes = 0L
  private var hits = 0L
  private var misses = 0L
  private var evictions = 0L

  @Synchronized
  fun get(source: String, frame: Int): ByteArray? {
    val v = cache[Key(source, frame)]
    if (v != null) hits++ else misses++
    return v
  }

  @Synchronized
  fun put(source: String, frame: Int, value: ByteArray) {
    val size = value.size.toLong()
    if (size > limit) return
    val key = Key(source, frame)
    cache.remove(key)?.let { bytes -= it.size }
    cache[key] = value
    bytes += size
    while (bytes > limit) {
      val it = cache.entries.iterator()
      val eldest = it.next()
      bytes -= eldest.value.size
      it.remove()
      evictions++
    }
  }

  @Synchronized
  override fun toString(): String = String.format(
    Locale.US,
    "Frames=%d | RAM=%.2f/%.2f MB | Hits=%d | Misses=%d | Evictions=%d",
    cache.size, bytes / MB, limit / MB, hits, misses, evictions
  )

  private companion object {
    const val MB = 1024.0 * 1024.0
  }
}

private class FallbackFrameCache {
  private val map = ConcurrentHashMap<Long, BufferedImage>()
  private fun key(w: Int, h: Int): Long = (w.toLong() shl 32) or (h.toLong() and 0xFFFFFFFFL)
  fun get(w: Int, h: Int): BufferedImage? = map[key(w, h)]
  fun put(w: Int, h: Int, img: BufferedImage) {
    map[key(w, h)] = img
  }
}

private class FrameSource(private val fps: Int, private val cache: RamFrameCache) {
  fun decode(file: File, frame: Int): BufferedImage {
    val bytes = bytes(file, frame)
    return ImageIO.read(ByteArrayInputStream(bytes))
      ?: error("Failed to decode frame $frame from ${file.name}.")
  }

  fun decodeOrFallback(file: File, frame: Int, fallback: BufferedImage): BufferedImage =
    try {
      decode(file, frame)
    } catch (e: Exception) {
      println("[VIDEO] WARNING: frame $frame from ${file.name} unavailable (${e.message}). Using fallback.")
      fallback
    }

  fun extractAll(file: File, count: Int): List<ByteArray> {
    val process = Ffmpeg.start(
      "-i", file.absolutePath,
      "-vf", "fps=$fps",
      "-frames:v", count.toString(),
      "-f", "image2pipe",
      "-c:v", "mjpeg", "-q:v", "3", "pipe:1"
    )
    val bytes = process.inputStream.use { it.readBytes() }
    val exitCode = process.waitFor()
    check(exitCode == 0) { "FFmpeg failed to extract frames from ${file.name}." }
    return splitMjpeg(bytes)
  }

  private fun bytes(file: File, frame: Int): ByteArray {
    require(file.exists()) { "Video sample not found: ${file.absolutePath}" }
    val key = file.absoluteFile.normalize().path
    val safe = frame.coerceAtLeast(0)
    cache.get(key, safe)?.let { return it }
    val timestamp = String.format(Locale.US, "%.6f", safe.toDouble() / fps)
    val process = Ffmpeg.start(
      "-ss", timestamp,
      "-i", file.absolutePath,
      "-vf", "fps=$fps",
      "-frames:v", "1",
      "-f", "mjpeg", "-q:v", "3", "pipe:1"
    )
    val data = process.inputStream.use { it.readBytes() }
    val exitCode = process.waitFor()
    check(exitCode == 0) { "FFmpeg failed to extract frame $safe from ${file.name}." }
    check(data.isNotEmpty()) { "FFmpeg returned an empty frame $safe from ${file.name}." }
    cache.put(key, safe, data)
    return data
  }

  private fun splitMjpeg(bytes: ByteArray): List<ByteArray> {
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
      } else i++
    }
    return frames
  }
}

private class ChunkBufferPool(bufferSize: Int, count: Int) {
  private val pool = ArrayBlockingQueue<ByteArray>(count)

  init {
    require(bufferSize > 0)
    require(count > 0)
    repeat(count) { pool.add(ByteArray(bufferSize)) }
  }

  fun acquire(): ByteArray = pool.take()
  fun release(buffer: ByteArray) {
    pool.offer(buffer)
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
        val cols = ceil(sqrt(count.toDouble())).toInt()
        GridLayout(cols, ceil(count.toDouble() / cols).toInt())
      }
    }
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

private class VideoCompositor(
  private val fps: Int,
  private val frameSource: FrameSource,
  private val fallbackCache: FallbackFrameCache
) {
  fun render(g2d: Graphics2D, activeByTrack: List<List<NoteEvent>>, ctx: ChunkContext, timeMs: Long) {
    g2d.color = Color.BLACK
    g2d.fillRect(0, 0, ctx.canvasW, ctx.canvasH)

    for (trackIdx in activeByTrack.indices) {
      val active = activeByTrack[trackIdx]
      val col = trackIdx % ctx.outerLayout.cols
      val row = trackIdx / ctx.outerLayout.cols
      val cellX = col * ctx.canvasW / ctx.outerLayout.cols
      val cellY = row * ctx.canvasH / ctx.outerLayout.rows
      val cellW = (col + 1) * ctx.canvasW / ctx.outerLayout.cols - cellX
      val cellH = (row + 1) * ctx.canvasH / ctx.outerLayout.rows - cellY

      if (active.isEmpty()) {
        draw(g2d, ctx.fallbackImage, cellX, cellY, cellW, cellH, cacheable = true)
        continue
      }

      val inner = GridLayout.forNoteCount(active.size)
      for (i in active.indices) {
        val note = active[i]
        val subX = cellX + (i % inner.cols) * cellW / inner.cols
        val subY = cellY + (i / inner.cols) * cellH / inner.rows
        val subW = (i % inner.cols + 1) * cellW / inner.cols + cellX - subX
        val subH = (i / inner.cols + 1) * cellH / inner.rows + cellY - subY

        val source = ctx.frameSources[note.note]
        if (source == null) {
          draw(g2d, ctx.fallbackImage, subX, subY, subW, subH, cacheable = true)
          continue
        }
        val frameIdx = ((timeMs - note.start) * fps / 1000.0).toInt().coerceAtLeast(0)
        val img = frameSource.decodeOrFallback(source, frameIdx, ctx.fallbackImage)
        draw(g2d, img, subX, subY, subW, subH, cacheable = false)
      }
    }
  }

  private fun draw(g2d: Graphics2D, img: BufferedImage, x: Int, y: Int, w: Int, h: Int, cacheable: Boolean) {
    val imgAspect = img.width.toDouble() / img.height
    val cellAspect = w.toDouble() / h
    val drawW = if (imgAspect > cellAspect) w else (h * imgAspect).toInt().coerceAtLeast(1)
    val drawH = if (imgAspect > cellAspect) (w / imgAspect).toInt().coerceAtLeast(1) else h

    val target = if (drawW == img.width && drawH == img.height) img
    else if (cacheable) fallbackCache.get(drawW, drawH) ?: resize(img, drawW, drawH).also {
      fallbackCache.put(
        drawW,
        drawH,
        it
      )
    }
    else resize(img, drawW, drawH)

    g2d.drawImage(target, x + (w - drawW) / 2, y + (h - drawH) / 2, null)
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

class MemoryVideoRenderer(
  private val fps: Int = 60,
  maxCacheMb: Int = 512,
  private val workerCount: Int = 0,
  private val chunkFrameCount: Int = 12
) {
  private val cache = RamFrameCache(maxCacheMb.toLong() * 1024 * 1024)
  private val fallbackCache = FallbackFrameCache()
  private val frameSource = FrameSource(fps, cache)
  private val compositor = VideoCompositor(fps, frameSource, fallbackCache)

  init {
    require(fps > 0)
    require(maxCacheMb > 0)
    require(chunkFrameCount > 0)
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
    prefetch(analyzeDemand(videoTimeline, frameSources, fallbackSource, totalFrames))

    val referenceSource = frameSources.entries.firstOrNull { it.key != SampleKeys.PAUSE }?.value ?: fallbackSource
    val referenceImg = frameSource.decode(referenceSource, 0)
    val canvasW = referenceImg.width
    val canvasH = referenceImg.height
    println("[VIDEO] Reference sample: ${referenceSource.name} — output resolution set to ${canvasW}x${canvasH}.")

    val fallbackImage = if (fallbackSource.absoluteFile.normalize() == referenceSource.absoluteFile.normalize())
      referenceImg
    else
      frameSource.decode(fallbackSource, 0)

    val encoder = EncoderSelector.select()
    val args = mutableListOf(
      "ffmpeg", "-y",
      "-f", "rawvideo", "-pixel_format", "bgr24",
      "-video_size", "${canvasW}x${canvasH}",
      "-framerate", fps.toString(),
      "-i", "pipe:0",
      "-i", masterAudioWav.absolutePath,
      "-c:v", encoder.name
    )
    args += encoder.params
    args += listOf(
      "-pix_fmt", "yuv420p",
      "-c:a", "aac", "-b:a", "192k",
      "-shortest",
      outputMp4.absolutePath
    )

    val process = ProcessBuilder(args).redirectError(ProcessBuilder.Redirect.INHERIT).start()

    val workers = if (workerCount > 0) workerCount
    else maxOf(1, minOf(Runtime.getRuntime().availableProcessors() - 1, 8))

    val numChunks = (totalFrames + chunkFrameCount - 1) / chunkFrameCount
    println("[VIDEO] Streaming $totalFrames frames at ${canvasW}x${canvasH}.")
    println("[VIDEO] Parallel mode: $workers worker(s), $chunkFrameCount frame(s)/chunk, $numChunks chunk(s).")
    println("[FRAME CACHE] Initial: $cache")

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
    val pool = ChunkBufferPool(chunkFrameCount * frameBytes, workers)

    val executor = Executors.newFixedThreadPool(workers) { runnable ->
      Thread(runnable, "video-chunk-worker").apply { isDaemon = true }
    }
    val pending = ArrayDeque<Future<ChunkResult>>()
    var nextChunk = 0
    var writtenFrames = 0

    try {
      process.outputStream.use { pipe ->
        while (nextChunk < numChunks || pending.isNotEmpty()) {
          while (nextChunk < numChunks && pending.size < workers) {
            val ci = nextChunk
            val startFrame = ci * chunkFrameCount
            val endFrame = minOf(startFrame + chunkFrameCount, totalFrames)
            pending.addLast(executor.submit(Callable { renderChunk(ci, startFrame, endFrame, ctx, pool) }))
            nextChunk++
          }
          val result = pending.removeFirst().get()
          pipe.write(result.buffer, 0, result.frameCount * frameBytes)
          pool.release(result.buffer)
          val before = writtenFrames
          writtenFrames += result.frameCount
          if (writtenFrames / 1000 > before / 1000) {
            println()
            println("[VIDEO] Frame $writtenFrames / $totalFrames")
            logMemory("Video rendering")
            println("[FRAME CACHE] $cache")
          }
        }
        pipe.flush()
      }
      check(process.waitFor() == 0) { "FFmpeg video encoding failed." }
    } finally {
      executor.shutdownNow()
    }
    println()
    println("[FRAME CACHE] Final: $cache")
  }

  private fun analyzeDemand(
    videoTimeline: Map<Int, List<VideoNotesSegment>>,
    frameSources: Map<String, File>,
    fallbackSource: File,
    totalFrames: Int
  ): Map<File, Int> {
    val maxIdx = mutableMapOf(fallbackSource.absoluteFile.normalize() to 0)
    val frameDurationMs = 1000.0 / fps
    val cursor = VideoTimelineCursor(videoTimeline)
    val tracks = videoTimeline.keys.sorted()
    for (frameIdx in 0 until totalFrames) {
      val timeMs = (frameIdx * frameDurationMs).toLong()
      for (track in tracks) {
        for (note in cursor.activeAt(track, timeMs)?.notes ?: emptyList()) {
          val source = frameSources[note.note]?.absoluteFile?.normalize() ?: continue
          val sampleFrame = ((timeMs - note.start) * fps / 1000.0).toInt().coerceAtLeast(0)
          maxIdx[source] = maxOf(maxIdx[source] ?: 0, sampleFrame)
        }
      }
    }
    return maxIdx
  }

  private fun prefetch(maxFrameIndexPerSource: Map<File, Int>) {
    val tasks = maxFrameIndexPerSource.entries.filter { (file, maxIdx) -> file.exists() && maxIdx >= 0 }
    if (tasks.isEmpty()) return
    val threads = minOf(8, tasks.size)
    val executor = Executors.newFixedThreadPool(threads)
    println("[PREFETCH] Extracting frames from ${tasks.size} sample(s) using $threads thread(s)...")
    try {
      tasks.map { (source, maxIdx) ->
        executor.submit {
          val frames = frameSource.extractAll(source, maxIdx + 8)
          val key = source.absoluteFile.normalize().path
          frames.forEachIndexed { i, b -> cache.put(key, i, b) }
          println("[PREFETCH] ${source.name}: ${frames.size} frame(s) extracted")
        }
      }.forEach { it.get() }
    } finally {
      executor.shutdown()
    }
    println("[PREFETCH] Done. $cache")
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
    val canvas = BufferedImage(ctx.canvasW, ctx.canvasH, BufferedImage.TYPE_3BYTE_BGR)
    val g2d = canvas.createGraphics()
    g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    val cursor = VideoTimelineCursor(ctx.videoTimeline)

    try {
      for (frameIdx in startFrame until endFrame) {
        val timeMs = (frameIdx * ctx.frameDurationMs).toLong()
        val active = ctx.tracks.map { cursor.activeAt(it, timeMs)?.notes ?: emptyList() }
        compositor.render(g2d, active, ctx, timeMs)
        val data = (canvas.raster.dataBuffer as DataBufferByte).data
        System.arraycopy(data, 0, output, (frameIdx - startFrame) * frameBytes, frameBytes)
      }
    } finally {
      g2d.dispose()
    }
    return ChunkResult(chunkIndex, frameCount, output)
  }
}

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

class SamplerPipeline(private val config: SamplerConfig = SamplerConfig()) {
  fun execute() {
    println("=== MIDI VIDEO SAMPLER ===")
    config.cacheDir.mkdirs()

    println()
    println("[1/4] Reading MIDI...")
    val notes = MidiEventReader().read(config.midiFile)
    val timeline = MidiTimeline().create(notes)
    val videoTimeline = VideoTimeline().create(notes)
    val uniqueNotes = notes.map { it.note }.toSet()
    val trackCount = videoTimeline.size
    val segmentCount = videoTimeline.values.sumOf { it.size }
    val maxSimultaneous = videoTimeline.values.flatten().maxOfOrNull { it.notes.size } ?: 0
    println("[MIDI] Found ${notes.size} notes using ${uniqueNotes.size} unique pitches across $trackCount MIDI track(s).")
    println("[MIDI] Audio timeline events: ${timeline.size}")
    println("[VIDEO] Video timeline created with $segmentCount segment(s) across $trackCount track(s) (max simultaneous notes within a single track: $maxSimultaneous).")

    println()
    println("[2/4] Loading audio samples...")
    val loader = AudioSampleLoader(config.audioSampleRate)
    val pcmSamples = mutableMapOf<String, AudioSample>()
    val frameSources = mutableMapOf<String, File>()
    profileTime("Loading audio samples") {
      for (note in uniqueNotes) {
        val sampleVideo = File(config.samplesDir, "$note.mp4")
        if (!sampleVideo.exists()) {
          println("[WARNING] Missing sample for note $note: ${sampleVideo.absolutePath}")
          continue
        }
        println("[AUDIO] Loading: ${sampleVideo.name}")
        pcmSamples[note] = loader.extract(sampleVideo, File(config.cacheDir, "sample_$note.wav"))
        frameSources[note] = sampleVideo
      }
      val pauseFile = File(config.samplesDir, "pause.mp4")
      if (pauseFile.exists()) {
        println("[AUDIO] Loading pause sample...")
        pcmSamples[SampleKeys.PAUSE] = loader.extract(pauseFile, File(config.cacheDir, "sample_pause.wav"))
        frameSources[SampleKeys.PAUSE] = pauseFile
      } else {
        println("[WARNING] No pause sample found. Timeline gaps will use the fallback frame.")
      }
    }
    require(frameSources.isNotEmpty()) { "No video samples were found in ${config.samplesDir.absolutePath}." }

    println()
    println("[3/4] Synthesizing master audio...")
    val masterWav = File(config.cacheDir, "master_audio.wav")
    profileTime("Synthesizing master audio") {
      AudioSynthesizer(config.audioSampleRate, config.audioChunkSeconds)
        .synthesize(timeline, pcmSamples, masterWav)
    }

    println()
    println("[4/4] Rendering final MP4...")
    println("[CONFIG] Frame cache limit: ${config.frameCacheMaxMb} MB")
    println("[CONFIG] Audio chunk size: ${config.audioChunkSeconds} seconds")
    println("[CONFIG] Video FPS: ${config.videoFps}")
    println("[CONFIG] Video workers: ${if (config.videoWorkers <= 0) "auto" else config.videoWorkers.toString()}")
    println("[CONFIG] Video chunk frames: ${config.videoChunkFrames}")
    profileTime("Rendering and encoding final MP4") {
      MemoryVideoRenderer(
        fps = config.videoFps,
        maxCacheMb = config.frameCacheMaxMb,
        workerCount = config.videoWorkers,
        chunkFrameCount = config.videoChunkFrames
      ).renderVideo(videoTimeline, frameSources, masterWav, config.outputFile)
    }

    println()
    println("[CLEANUP] Removing temporary files...")
    config.cacheDir.deleteRecursively()
    println("[CLEANUP] Temporary files removed.")
    println()
    println("=== PROCESSING COMPLETED SUCCESSFULLY ===")
    println("Output: ${config.outputFile.absolutePath}")
  }
}

fun main() {
  SamplerPipeline().execute()
}