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

object Profiler {
  fun logMemory(tag: String) {
    val runtime = Runtime.getRuntime()
    val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
    val maxMb = runtime.maxMemory() / (1024 * 1024)
    println("[PROFILER] Memory — $tag: $usedMb MB used / $maxMb MB JVM maximum")
  }

  inline fun <T> measure(tag: String, block: () -> T): T {
    println(); println("[PROFILER] Starting: $tag"); logMemory("Before $tag")
    val start = System.currentTimeMillis();
    val result = block();
    val elapsed = System.currentTimeMillis() - start
    logMemory("After $tag"); println("[PROFILER] Completed: $tag in $elapsed ms (${elapsed / 1000.0}s)")
    return result
  }
}

data class NoteEvent(val note: String, val velocity: Int, val start: Long, val duration: Long)

class MidiEventReader {
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

  private fun finishNote(
    channel: Int,
    note: Int,
    endTick: Long,
    activeNotes: MutableMap<Pair<Int, Int>, MutableList<StartedNote>>,
    tempos: List<TempoEvent>,
    division: Int,
    result: MutableList<NoteEvent>
  ) {
    val key = channel to note;
    val notes = activeNotes[key] ?: return
    if (notes.isEmpty()) return
    val started = notes.removeAt(0)
    val start = ticksToMillis(started.tick, tempos, division);
    val end = ticksToMillis(endTick, tempos, division)
    result += NoteEvent(
      note = midiNoteName(note),
      velocity = started.velocity,
      start = start,
      duration = (end - start).coerceAtLeast(0L)
    )
    if (notes.isEmpty()) activeNotes.remove(key)
  }

  private fun ticksToMillis(targetTick: Long, tempos: List<TempoEvent>, division: Int): Long {
    var currentTick = 0L;
    var tempo = DEFAULT_TEMPO.toLong();
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

  private data class TimedEvent(val tick: Long, val event: Event)
  private data class TempoEvent(val tick: Long, val tempo: Int)
  private data class StartedNote(val tick: Long, val velocity: Int)

  companion object {
    private const val DEFAULT_TEMPO = 500_000
    private val MIDI_NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
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
      if (note.start > position) timeline += TimelinePause(durationMs = note.start - position, start = position)
      timeline += TimelineNote(note); position = maxOf(position, note.start + note.duration)
    }
    return timeline
  }
}

class AudioSample(val left: FloatArray, val right: FloatArray)

class AudioSynthesizer(private val sampleRate: Int = 48_000, private val chunkDurationSeconds: Int = 10) {
  private val fadeDurationMs = 15.0

  init {
    require(sampleRate > 0) { "Sample rate must be greater than zero." }
    require(chunkDurationSeconds > 0) { "Audio chunk duration must be greater than zero." }
  }

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
    val left = FloatArray(totalSamples);
    val right = FloatArray(totalSamples)
    ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).also { buffer ->
      repeat(totalSamples) { i -> left[i] = buffer.short / 32768.0f; right[i] = buffer.short / 32768.0f }
    }
    return AudioSample(left, right)
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

    val masterL = FloatArray(chunkSamples);
    val masterR = FloatArray(chunkSamples)
    val totalChunks = (totalSamples + chunkSamples - 1) / chunkSamples

    println(); println("[AUDIO] Pass 1/2: calculating global peak...")
    var maxPeak = 0.0f;
    var chunkStart = 0;
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
      var processedSamples = 0;
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
      val key = event.sampleKey();
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

  private fun writeWavStreaming(file: File, totalSamples: Int, sampleRate: Int, block: (BufferedOutputStream) -> Unit) {
    val totalDataLen = totalSamples.toLong() * 4;
    val totalSize = totalDataLen + 36
    require(totalDataLen <= 0xFFFFFFFFL) { "WAV file is too large for classic RIFF PCM." }
    file.outputStream().buffered(64 * 1024)
      .use { out -> out.write(createWavHeader(totalDataLen, totalSize, sampleRate)); block(out) }
  }

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

  private fun createWavHeader(totalDataLen: Long, totalSize: Long, sampleRate: Int): ByteArray {
    val header = ByteArray(44);
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

  private fun writeIntLE(array: ByteArray, offset: Int, value: Long) {
    repeat(4) { array[offset + it] = (value shr (it * 8)).toByte() }
  }

  private fun writeShortLE(array: ByteArray, offset: Int, value: Int) {
    array[offset] = value.toByte()
    array[offset + 1] = (value shr 8).toByte()
  }

  private fun runFfmpeg(vararg arguments: String) {
    val exitCode = ProcessBuilder(listOf("ffmpeg", "-loglevel", "error") + arguments).inheritIO().start().waitFor()
    check(exitCode == 0) { "FFmpeg audio extraction failed with exit code $exitCode." }
  }

  private fun findDataChunk(bytes: ByteArray): Int {
    for (i in 0..bytes.size - 8) {
      if (bytes[i] == 'd'.code.toByte() && bytes[i + 1] == 'a'.code.toByte() && bytes[i + 2] == 't'.code.toByte() && bytes[i + 3] == 'a'.code.toByte()) return i + 8
    }
    return bytes.size
  }

  private fun emptyAudioSample() = AudioSample(FloatArray(0), FloatArray(0))

  private fun clearBuffers(left: FloatArray, right: FloatArray, size: Int) {
    Arrays.fill(left, 0, size, 0.0f); Arrays.fill(right, 0, size, 0.0f)
  }

  private fun toPcm16(value: Float): Short = (value * 32767.0f).toInt().coerceIn(-32768, 32767).toShort()

  private companion object {
    const val NORMALIZATION_PEAK = 0.95f
  }
}

class RamFrameCache(maxBytes: Long) {
  private val maxBytes = maxBytes.coerceAtLeast(1)

  private data class CacheKey(val source: String, val frameIndex: Int)

  private val cache = LinkedHashMap<CacheKey, ByteArray>(16, 0.75f, true)
  private var currentBytes = 0L;
  private var hits = 0L;
  private var misses = 0L;
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
      val iterator = cache.entries.iterator();
      val eldest = iterator.next()
      currentBytes -= eldest.value.size; iterator.remove(); evictions++
    }
  }

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

  @Synchronized
  fun clear() {
    cache.clear(); currentBytes = 0
  }

  private companion object {
    const val MB = 1024.0 * 1024.0
  }
}

class MemoryVideoRenderer(private val fps: Int = 60, maxCacheMb: Int = 512) {
  private val frameCache = RamFrameCache(maxBytes = maxCacheMb.toLong() * 1024 * 1024)

  init {
    require(fps > 0) { "Video FPS must be greater than zero." }
    require(maxCacheMb > 0) { "Frame cache size must be greater than zero." }
  }

  private fun getFrame(videoFile: File, frameIndex: Int): ByteArray {
    val sourceKey = videoFile.absoluteFile.normalize().path
    val safeFrameIndex = frameIndex.coerceAtLeast(0)
    frameCache.get(sourceKey, safeFrameIndex)?.let { return it }
    return extractSingleFrame(videoFile, safeFrameIndex).also { frameCache.put(sourceKey, safeFrameIndex, it) }
  }

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

  fun renderVideo(
    timeline: List<TimelineEvent>,
    frameSources: Map<String, File>,
    masterAudioWav: File,
    outputMp4: File
  ) {
    require(timeline.isNotEmpty()) { "Cannot render video from an empty timeline." }
    require(masterAudioWav.exists()) { "Master audio file not found: ${masterAudioWav.absolutePath}" }

    val totalMs = timeline.maxOf { it.start + it.duration }
    val frameDurationMs = 1000.0 / fps
    val totalFrames = ceil(totalMs / frameDurationMs).toInt()
    require(totalFrames > 0) { "Timeline does not contain any renderable frames." }

    val fallbackSource = frameSources[PAUSE_KEY] ?: frameSources.values.firstOrNull()
    ?: error("No video sample is available for rendering.")
    val fallbackFrame = getFrame(fallbackSource, 0)

    val process = ProcessBuilder(
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
    ).redirectError(ProcessBuilder.Redirect.INHERIT).start()

    println("[VIDEO] Streaming $totalFrames frames..."); println("[FRAME CACHE] Initial: ${frameCache.stats()}")

    var activeIndex = 0

    process.outputStream.use { pipeOut ->
      for (frameIdx in 0 until totalFrames) {
        val timeMs = (frameIdx * frameDurationMs).toLong()

        while (activeIndex < timeline.size && timeMs >= timeline[activeIndex].start + timeline[activeIndex].duration) activeIndex++

        val activeEvent =
          timeline.getOrNull(activeIndex)?.takeIf { timeMs >= it.start && timeMs < it.start + it.duration }

        val frameBytes = when {
          activeEvent == null -> fallbackFrame
          else -> {
            val source = frameSources[activeEvent.sampleKey()]
            if (source == null) fallbackFrame else {
              val sampleFrameIdx = ((timeMs - activeEvent.start) * fps / 1000.0).toInt()
              try {
                getFrame(source, sampleFrameIdx)
              } catch (e: Exception) {
                println(); println("[WARNING] Failed to obtain frame $sampleFrameIdx from ${source.name}: ${e.message}"); fallbackFrame
              }
            }
          }
        }

        pipeOut.write(frameBytes)

        if (frameIdx > 0 && frameIdx % 1000 == 0) {
          println(); println("[VIDEO] Frame $frameIdx / $totalFrames")
          Profiler.logMemory("Video rendering"); println("[FRAME CACHE] ${frameCache.stats()}")
        }
      }
      pipeOut.flush()
    }

    check(process.waitFor() == 0) { "FFmpeg video encoding failed." }
    println(); println("[FRAME CACHE] Final: ${frameCache.stats()}")
  }
}

private const val PAUSE_KEY = "__pause__"

private fun TimelineEvent.sampleKey(): String = when (this) {
  is TimelineNote -> event.note
  is TimelinePause -> PAUSE_KEY
}

private fun ByteArray.writeAscii(offset: Int, value: String) {
  value.forEachIndexed { index, char -> this[offset + index] = char.code.toByte() }
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

private const val FRAME_CACHE_MAX_MB = 512
private const val AUDIO_CHUNK_SECONDS = 10
private const val AUDIO_SAMPLE_RATE = 48_000
private const val VIDEO_FPS = 60

fun main() {
  println("=== MIDI VIDEO SAMPLER ===")

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

  val uniqueNotes = timeline
    .filterIsInstance<TimelineNote>()
    .map { it.event.note }
    .toSet()

  println(
    "[MIDI] Found ${notes.size} notes using " +
        "${uniqueNotes.size} unique pitches."
  )
  println("[MIDI] Timeline events: ${timeline.size}")

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
  val frameSources = mutableMapOf<String, File>()

  // ========================================================================
  // 3. LOAD ONLY AUDIO FROM REQUIRED SAMPLES
  // ========================================================================

  println()
  println("[3/5] Loading audio samples...")

  Profiler.measure("Loading audio samples") {
    for (note in uniqueNotes) {
      val sampleVideo = File(samplesDir, "$note.mp4")

      if (!sampleVideo.exists()) {
        println(
          "[WARNING] Missing sample for note $note: " +
              sampleVideo.absolutePath
        )
        continue
      }

      println("[AUDIO] Loading: ${sampleVideo.name}")

      pcmSamples[note] = audioSynth.extractSamplePcm(
        videoFile = sampleVideo,
        tempWav = File(cacheDir, "sample_$note.wav")
      )

      frameSources[note] = sampleVideo
    }

    if (pauseFile.exists()) {
      println("[AUDIO] Loading pause sample...")

      pcmSamples[PAUSE_KEY] = audioSynth.extractSamplePcm(
        videoFile = pauseFile,
        tempWav = File(cacheDir, "sample_pause.wav")
      )

      frameSources[PAUSE_KEY] = pauseFile
    } else {
      println(
        "[WARNING] No pause sample found. " +
            "Timeline gaps will use the fallback frame."
      )
    }
  }

  require(frameSources.isNotEmpty()) {
    "No video samples were found in ${samplesDir.absolutePath}."
  }

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