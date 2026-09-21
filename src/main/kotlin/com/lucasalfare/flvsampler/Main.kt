package com.lucasalfare.flvsampler

import com.lucasalfare.flmidi.*
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Represents a video sample associated with a MIDI note.
 */
data class Sample(
  val file: File
)

/**
 * Loads and indexes all MP4 samples from a directory.
 *
 * The file name without the extension is used as the MIDI note name.
 */
class SampleLibrary(
  private val directory: File
) {
  private val samples = mutableMapOf<String, Sample>()

  init {
    require(directory.exists()) {
      "Sample directory does not exist: ${directory.path}"
    }

    require(directory.isDirectory) {
      "Sample path is not a directory: ${directory.path}"
    }

    val files = directory.listFiles()
      ?: error("Could not read sample directory: ${directory.path}")

    for (file in files) {
      if (!file.isFile || file.extension.lowercase() != "mp4") continue

      val note = file.nameWithoutExtension

      require(note.isNotBlank()) {
        "Invalid sample file: ${file.name}"
      }

      require(note !in samples) {
        "Duplicate sample for note $note"
      }

      samples[note] = Sample(file)
    }
  }

  fun get(note: String): Sample? = samples[note]

  fun all(): List<Sample> = samples.values.toList()
}

/**
 * Represents a MIDI note with its absolute start time and duration in milliseconds.
 */
data class NoteEvent(
  val note: String,
  val velocity: Int,
  val start: Long,
  val duration: Long
)

/**
 * Reads note events from a MIDI file using FLMidi.
 */
class MidiEventReader {

  fun read(file: File): List<NoteEvent> {
    val midi = MidiReader.fromFile(file.path)

    require(midi.division > 0) {
      "SMPTE MIDI timing is not supported yet"
    }

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

    if (tempos.none { it.tick == 0L }) {
      tempos += TempoEvent(0, 500_000)
    }

    tempos.sortBy { it.tick }

    val activeNotes = mutableMapOf<Pair<Int, Int>, MutableList<StartedNote>>()
    val result = mutableListOf<NoteEvent>()

    for (timedEvent in events) {
      when (val event = timedEvent.event) {

        is NoteOnControlEvent -> {
          if (event.velocity == 0) {
            finishNote(
              event.channel,
              event.note,
              timedEvent.tick,
              activeNotes,
              tempos,
              midi.division,
              result
            )
          } else {
            activeNotes
              .getOrPut(event.channel to event.note) { mutableListOf() }
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
      duration = end - start
    )

    if (notes.isEmpty()) {
      activeNotes.remove(key)
    }
  }

  /**
   * Converts an absolute MIDI tick position to milliseconds,
   * taking tempo changes into account.
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
      if (change.tick > targetTick) break

      microseconds +=
        (change.tick - currentTick) * tempo / division

      currentTick = change.tick
      tempo = change.tempo.toLong()
    }

    microseconds +=
      (targetTick - currentTick) * tempo / division

    return microseconds / 1_000
  }

  /**
   * Converts a MIDI note number to its conventional note name.
   */
  private fun midiNoteName(note: Int): String {
    val names = listOf(
      "C", "C#", "D", "D#", "E", "F",
      "F#", "G", "G#", "A", "A#", "B"
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

/**
 * Represents a period in the MIDI timeline where no note is active.
 */
data class PauseEvent(
  val start: Long,
  val duration: Long
)

/**
 * Represents an event in the complete MIDI timeline.
 */
sealed interface TimelineEvent {
  val start: Long
  val duration: Long
}

/**
 * A note inside the MIDI timeline.
 */
data class TimelineNote(
  val event: NoteEvent
) : TimelineEvent {
  override val start = event.start
  override val duration = event.duration
}

/**
 * A pause inside the MIDI timeline.
 */
data class TimelinePause(
  val event: PauseEvent
) : TimelineEvent {
  override val start = event.start
  override val duration = event.duration
}

/**
 * Converts MIDI note events into a complete timeline containing notes and pauses.
 */
class MidiTimeline {

  fun create(notes: List<NoteEvent>): List<TimelineEvent> {
    if (notes.isEmpty()) return emptyList()

    val timeline = mutableListOf<TimelineEvent>()
    var position = 0L

    for (note in notes.sortedBy { it.start }) {
      if (note.start > position) {
        timeline += TimelinePause(
          PauseEvent(
            start = position,
            duration = note.start - position
          )
        )
      }

      timeline += TimelineNote(note)
      position = maxOf(position, note.start + note.duration)
    }

    return timeline
  }
}

/**
 * Defines the visual source used during MIDI pauses.
 */
sealed interface PauseSource {

  /**
   * Uses a video that can be looped when necessary.
   */
  data class Video(
    val file: File
  ) : PauseSource

  /**
   * Uses a generated black background.
   */
  data object Black : PauseSource
}

/**
 * Loads the pause video or falls back to a black background.
 */
fun pauseSource(file: File): PauseSource {
  return if (file.isFile) {
    PauseSource.Video(file)
  } else {
    PauseSource.Black
  }
}

/**
 * Represents a planned audiovisual segment.
 */
sealed interface VideoSegment {
  val start: Long
  val duration: Long
}

/**
 * A segment generated from a note sample.
 */
data class NoteSegment(
  val source: File,
  override val start: Long,
  override val duration: Long
) : VideoSegment

/**
 * A segment generated from a pause source.
 */
data class PauseSegment(
  val source: PauseSource,
  override val start: Long,
  override val duration: Long
) : VideoSegment

/**
 * Converts the MIDI timeline into a video rendering plan.
 */
class VideoPlanner(
  private val samples: SampleLibrary,
  private val pause: PauseSource
) {

  fun create(timeline: List<TimelineEvent>): List<VideoSegment> {
    return timeline.map { event ->
      when (event) {

        is TimelineNote -> {
          val sample = samples.get(event.event.note)
            ?: error("No sample found for note ${event.event.note}")

          require(event.duration > 0) {
            "Invalid note duration: ${event.duration} ms"
          }

          NoteSegment(
            source = sample.file,
            start = event.start,
            duration = event.duration
          )
        }

        is TimelinePause -> {
          require(event.duration > 0) {
            "Invalid pause duration: ${event.duration} ms"
          }

          PauseSegment(
            source = pause,
            start = event.start,
            duration = event.duration
          )
        }
      }
    }
  }
}

/**
 * Generates individual video files for all planned segments.
 *
 * Segments with the same source and duration are rendered only once
 * and then reused from the cache.
 */
class VideoSegmentGenerator(
  private val outputDirectory: File = File("render"),
  private val workers: Int = minOf(
    4,
    Runtime.getRuntime().availableProcessors()
  )
) {

  fun generate(segments: List<VideoSegment>): List<File> {
    outputDirectory.mkdirs()
    if (segments.isEmpty()) return emptyList()

    val completed = AtomicInteger()
    val cache = ConcurrentHashMap<String, File>()
    val executor = Executors.newFixedThreadPool(workers.coerceAtLeast(1))
    val startTime = System.currentTimeMillis()

    val progressThread = Thread {
      while (completed.get() < segments.size) {
        val done = completed.get()
        val elapsed = System.currentTimeMillis() - startTime

        val percentage = done * 100.0 / segments.size

        val remaining = if (done == 0) {
          null
        } else {
          elapsed * (segments.size - done) / done
        }

        print(
          "\rSegments: $done/${segments.size} " +
              "(${String.format("%.1f", percentage)}%) " +
              "| Workers: $workers " +
              "| Elapsed: ${formatTime(elapsed)} " +
              "| Remaining: ${remaining?.let(::formatTime) ?: "--:--"}"
        )

        Thread.sleep(500)
      }
    }

    progressThread.start()

    try {
      val futures = segments.mapIndexed { index, segment ->
        executor.submit<File> {
          val output = File(
            outputDirectory,
            "segment_%04d.mp4".format(index + 1)
          )

          val key = cacheKey(segment)
          val cached = cache[key]

          if (cached != null && cached.exists()) {
            Files.copy(cached.toPath(), output.toPath())
          } else {
            generateSegment(segment, output)
            cache[key] = output
          }

          completed.incrementAndGet()
          output
        }
      }

      val result = futures.map { it.get() }

      progressThread.join()

      val elapsed = System.currentTimeMillis() - startTime

      println(
        "\rSegments: ${segments.size}/${segments.size} (100.0%) " +
            "| Workers: $workers " +
            "| Elapsed: ${formatTime(elapsed)} " +
            "| Remaining: 00:00"
      )

      val reused = segments.size - cache.size

      println(
        "FFmpeg renders: ${cache.size} | Total segments: ${segments.size}"
      )

      if (reused > 0) {
        println("Cache reused: $reused segments")
      }

      return result

    } finally {
      executor.shutdownNow()
    }
  }

  /**
   * Creates a stable key for reusable rendered segments.
   */
  private fun cacheKey(segment: VideoSegment): String {
    return when (segment) {

      is NoteSegment ->
        "note|${segment.source.absolutePath}|${segment.duration}"

      is PauseSegment ->
        when (val source = segment.source) {
          is PauseSource.Video ->
            "pause|${source.file.absolutePath}|${segment.duration}"

          PauseSource.Black ->
            "black|${segment.duration}"
        }
    }
  }

  /**
   * Renders one video segment with FFmpeg.
   */
  private fun generateSegment(
    segment: VideoSegment,
    output: File
  ) {
    output.delete()

    val duration = segment.duration / 1000.0

    when (segment) {

      is NoteSegment -> {
        runFfmpeg(
          "-y",
          "-i", segment.source.absolutePath,
          "-t", duration.toString(),
          "-c:v", "libx264",
          "-preset", "veryfast",
          "-pix_fmt", "yuv420p",
          output.absolutePath
        )
      }

      is PauseSegment -> {
        when (val source = segment.source) {

          is PauseSource.Video -> {
            runFfmpeg(
              "-y",
              "-stream_loop", "-1",
              "-i", source.file.absolutePath,
              "-t", duration.toString(),
              "-c:v", "libx264",
              "-preset", "veryfast",
              "-pix_fmt", "yuv420p",
              output.absolutePath
            )
          }

          PauseSource.Black -> {
            runFfmpeg(
              "-y",
              "-f", "lavfi",
              "-i", "color=c=black:s=1280x720:r=30",
              "-t", duration.toString(),
              "-c:v", "libx264",
              "-preset", "veryfast",
              "-pix_fmt", "yuv420p",
              output.absolutePath
            )
          }
        }
      }
    }
  }

  /**
   * Runs FFmpeg and fails if the process exits unsuccessfully.
   */
  private fun runFfmpeg(vararg arguments: String) {
    val process = ProcessBuilder(
      listOf("ffmpeg", "-loglevel", "error") + arguments
    )
      .inheritIO()
      .start()

    check(process.waitFor() == 0) {
      "FFmpeg failed"
    }
  }

  /**
   * Formats milliseconds as MM:SS or HH:MM:SS.
   */
  private fun formatTime(milliseconds: Long): String {
    val seconds = milliseconds / 1000
    val hours = seconds / 3600
    val minutes = seconds % 3600 / 60
    val remainingSeconds = seconds % 60

    return if (hours > 0) {
      "%02d:%02d:%02d".format(
        hours,
        minutes,
        remainingSeconds
      )
    } else {
      "%02d:%02d".format(
        minutes,
        remainingSeconds
      )
    }
  }
}

/**
 * Concatenates generated video segments in timeline order.
 */
class VideoConcatenator {

  fun concatenate(
    segments: List<File>,
    output: File = File("output.mp4")
  ) {
    require(segments.isNotEmpty()) {
      "No video segments to concatenate"
    }

    segments.forEach {
      require(it.isFile) {
        "Segment does not exist: ${it.path}"
      }
    }

    val listFile = File.createTempFile(
      "ffmpeg_concat_",
      ".txt"
    )

    try {
      listFile.writeText(
        segments.joinToString("\n") {
          "file '${it.absoluteFile.path.replace("'", "'\\''")}'"
        }
      )

      output.delete()

      val process = ProcessBuilder(
        "ffmpeg",
        "-y",
        "-f", "concat",
        "-safe", "0",
        "-i", listFile.absolutePath,
        "-c", "copy",
        output.absolutePath
      )
        .inheritIO()
        .start()

      check(process.waitFor() == 0) {
        "FFmpeg concatenation failed"
      }

      check(output.isFile) {
        "Output video was not created: ${output.path}"
      }

    } finally {
      listFile.delete()
    }
  }
}

/**
 * Removes all temporary rendered segments.
 */
fun cleanup() {
  File("render").deleteRecursively()
}

/**
 * Runs the complete MIDI-to-video pipeline.
 */
fun main() {
  println("=== MIDI Video Renderer ===")

  println("[1/7] Loading samples...")
  val samples = SampleLibrary(File("samples"))
  println("      ${samples.all().size} samples loaded.")

  println("[2/7] Loading pause source...")
  val pause = pauseSource(File("samples/pause.mp4"))
  println("      Pause source: $pause")

  println("[3/7] Reading MIDI...")
  val notes = MidiEventReader().read(File("input.mid"))
  println("      ${notes.size} notes found.")

  println("[4/7] Creating MIDI timeline...")
  val timeline = MidiTimeline().create(notes)
  println("      ${timeline.size} timeline events created.")

  println("[5/7] Planning video segments...")
  val segments = VideoPlanner(samples, pause).create(timeline)
  println("      ${segments.size} video segments planned.")

  println("[6/7] Generating video segments...")
  val generatedSegments = VideoSegmentGenerator().generate(segments)

  println("[7/7] Concatenating video...")
  val output = File("output.mp4")

  VideoConcatenator().concatenate(
    segments = generatedSegments,
    output = output
  )

  println("Cleaning temporary files...")
  cleanup()

  println()
  println("=== Finished ===")
  println("Output: ${output.absolutePath}")
}

/*
import com.lucasalfare.flmidi.*
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

data class Sample(
  val note: String,
  val file: File
)

class SampleLibrary(
  private val directory: File
) {
  private val samples = mutableMapOf<String, Sample>()

  init {
    load()
  }

  private fun load() {
    require(directory.exists()) {
      "Sample directory does not exist: ${directory.path}"
    }

    require(directory.isDirectory) {
      "Sample path is not a directory: ${directory.path}"
    }

    val files = directory.listFiles()
      ?: error("Could not read sample directory: ${directory.path}")

    for (file in files) {
      if (!file.isFile) continue
      if (file.extension.lowercase() != "mp4") continue

      val note = file.nameWithoutExtension

      if (note.isBlank()) {
        error("Invalid sample file: ${file.name}")
      }

      if (samples.containsKey(note)) {
        error("Duplicate sample for note $note")
      }

      samples[note] = Sample(
        note = note,
        file = file
      )
    }
  }

  fun has(note: String): Boolean {
    return samples.containsKey(note)
  }

  fun get(note: String): Sample? {
    return samples[note]
  }

  fun all(): List<Sample> {
    return samples.values.toList()
  }
}

data class NoteEvent(
  val note: String,
  val velocity: Int,
  val start: Long,
  val duration: Long
)

class MidiEventReader {

  fun read(file: File): List<NoteEvent> {
    val midi = MidiReader.fromFile(file.path)

    require(midi.division > 0) {
      "SMPTE MIDI timing is not supported yet"
    }

    val events = mutableListOf<TimedEvent>()

    for (track in midi.tracks) {
      var tick = 0L

      for (event in track) {
        tick += event.deltaTime

        events += TimedEvent(
          tick = tick,
          event = event
        )
      }
    }

    events.sortBy { it.tick }

    val tempos = mutableListOf<TempoEvent>()

    for (event in events) {
      if (event.event is SetTempoMetaEvent) {
        tempos += TempoEvent(
          tick = event.tick,
          tempo = event.event.tempo
        )
      }
    }

    if (tempos.none { it.tick == 0L }) {
      tempos.add(
        0,
        TempoEvent(
          tick = 0,
          tempo = 500_000
        )
      )
    }

    tempos.sortBy { it.tick }

    val activeNotes = mutableMapOf<Pair<Int, Int>, MutableList<StartedNote>>()
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
            val key = event.channel to event.note

            activeNotes
              .getOrPut(key) { mutableListOf() }
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

    val start = ticksToMillis(
      started.tick,
      tempos,
      division
    )

    val end = ticksToMillis(
      endTick,
      tempos,
      division
    )

    result += NoteEvent(
      note = midiNoteName(note),
      velocity = started.velocity,
      start = start,
      duration = end - start
    )

    if (notes.isEmpty()) {
      activeNotes.remove(key)
    }
  }

  private fun ticksToMillis(
    targetTick: Long,
    tempos: List<TempoEvent>,
    division: Int
  ): Long {
    var currentTick = 0L
    var currentTempo = 500_000L
    var microseconds = 0L

    for (tempo in tempos) {
      if (tempo.tick > targetTick) break

      val ticks = tempo.tick - currentTick

      microseconds += ticks * currentTempo / division

      currentTick = tempo.tick
      currentTempo = tempo.tempo.toLong()
    }

    microseconds +=
      (targetTick - currentTick) * currentTempo / division

    return microseconds / 1_000
  }

  private fun midiNoteName(note: Int): String {
    val names = listOf(
      "C", "C#", "D", "D#", "E", "F",
      "F#", "G", "G#", "A", "A#", "B"
    )

    val octave = note / 12 - 1

    return names[note % 12] + octave
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

data class PauseEvent(
  val start: Long,
  val duration: Long
)

sealed interface TimelineEvent {
  val start: Long
  val duration: Long
}

data class TimelineNote(
  val event: NoteEvent
) : TimelineEvent {
  override val start = event.start
  override val duration = event.duration
}

data class TimelinePause(
  val event: PauseEvent
) : TimelineEvent {
  override val start = event.start
  override val duration = event.duration
}

class MidiTimeline {

  fun create(notes: List<NoteEvent>): List<TimelineEvent> {
    if (notes.isEmpty()) return emptyList()

    val sorted = notes.sortedBy { it.start }
    val timeline = mutableListOf<TimelineEvent>()

    var position = 0L

    for (note in sorted) {
      val noteEnd = note.start + note.duration

      if (note.start > position) {
        timeline += TimelinePause(
          PauseEvent(
            start = position,
            duration = note.start - position
          )
        )
      }

      timeline += TimelineNote(note)

      if (noteEnd > position) {
        position = noteEnd
      }
    }

    return timeline.sortedBy { it.start }
  }
}

sealed interface PauseSource {

  data class Video(
    val file: File
  ) : PauseSource

  data object Black : PauseSource
}

fun pauseSource(file: File): PauseSource {
  return if (file.exists() && file.isFile) {
    PauseSource.Video(file)
  } else {
    PauseSource.Black
  }
}

sealed interface VideoSegment {
  val start: Long
  val duration: Long
}

data class NoteSegment(
  val source: File,
  override val start: Long,
  override val duration: Long
) : VideoSegment

data class PauseSegment(
  val source: PauseSource,
  override val start: Long,
  override val duration: Long
) : VideoSegment

class VideoPlanner(
  private val samples: SampleLibrary,
  private val pause: PauseSource
) {

  fun create(timeline: List<TimelineEvent>): List<VideoSegment> {
    val segments = mutableListOf<VideoSegment>()

    for (event in timeline) {
      when (event) {

        is TimelineNote -> {
          val sample = samples.get(event.event.note)
            ?: error(
              "No sample found for note ${event.event.note}"
            )

          require(sample.file.exists() && sample.file.isFile) {
            "Sample file does not exist: ${sample.file.path}"
          }

          require(event.duration > 0) {
            "Invalid note duration: ${event.duration} ms"
          }

          segments += NoteSegment(
            source = sample.file,
            start = event.start,
            duration = event.duration
          )
        }

        is TimelinePause -> {
          require(event.duration > 0) {
            "Invalid pause duration: ${event.duration} ms"
          }

          if (pause is PauseSource.Video) {
            require(pause.file.exists() && pause.file.isFile) {
              "Pause video does not exist: ${pause.file.path}"
            }
          }

          segments += PauseSegment(
            source = pause,
            start = event.start,
            duration = event.duration
          )
        }
      }
    }

    return segments
  }
}

class VideoSegmentGenerator(
  private val outputDirectory: File = File("render"),
  private val workers: Int = minOf(
    4,
    Runtime.getRuntime().availableProcessors()
  )
) {

  fun generate(segments: List<VideoSegment>): List<File> {
    outputDirectory.mkdirs()

    if (segments.isEmpty()) return emptyList()

    val completed = AtomicInteger(0)
    val cache = ConcurrentHashMap<String, File>()

    val executor = Executors.newFixedThreadPool(
      workers.coerceAtLeast(1)
    )

    val startTime = System.currentTimeMillis()

    val progressThread = Thread {
      while (completed.get() < segments.size) {
        val done = completed.get()
        val elapsed = System.currentTimeMillis() - startTime

        val percentage =
          done * 100.0 / segments.size

        val remaining =
          if (done == 0) {
            null
          } else {
            val average = elapsed.toDouble() / done
            (average * (segments.size - done)).toLong()
          }

        print(
          "\r" +
              "Segments: $done/${segments.size} " +
              "(${String.format("%.1f", percentage)}%) " +
              "| Workers: $workers " +
              "| Elapsed: ${formatTime(elapsed)} " +
              "| Remaining: ${remaining?.let(::formatTime) ?: "--:--"}"
        )

        Thread.sleep(500)
      }
    }

    progressThread.start()

    try {
      val futures = segments.mapIndexed { index, segment ->

        executor.submit<File> {
          val output = File(
            outputDirectory,
            "segment_%04d.mp4".format(index + 1)
          )

          val key = cacheKey(segment)

          val cached = cache[key]

          if (cached != null && cached.exists()) {
            Files.copy(
              cached.toPath(),
              output.toPath()
            )
          } else {
            generateSegment(segment, output)

            cache[key] = output
          }

          completed.incrementAndGet()

          output
        }
      }

      val result = futures.map { it.get() }

      progressThread.join()

      val elapsed = System.currentTimeMillis() - startTime

      println(
        "\r" +
            "Segments: ${segments.size}/${segments.size} (100.0%) " +
            "| Workers: $workers " +
            "| Elapsed: ${formatTime(elapsed)} " +
            "| Remaining: 00:00"
      )

      val uniqueSegments = cache.size

      println(
        "FFmpeg renders: $uniqueSegments " +
            "| Total segments: ${segments.size}"
      )

      if (uniqueSegments < segments.size) {
        println(
          "Cache reused: ${segments.size - uniqueSegments} segments"
        )
      }

      return result

    } finally {
      executor.shutdownNow()
    }
  }

  private fun cacheKey(segment: VideoSegment): String {
    return when (segment) {

      is NoteSegment ->
        "note|" +
            segment.source.absolutePath +
            "|" +
            segment.duration

      is PauseSegment ->
        when (val source = segment.source) {

          is PauseSource.Video ->
            "pause|" +
                source.file.absolutePath +
                "|" +
                segment.duration

          PauseSource.Black ->
            "black|" +
                segment.duration
        }
    }
  }

  private fun generateSegment(
    segment: VideoSegment,
    output: File
  ) {
    output.delete()

    val duration = segment.duration / 1000.0

    when (segment) {

      is NoteSegment -> {
        runFfmpeg(
          "-y",
          "-i", segment.source.absolutePath,
          "-t", duration.toString(),
          "-c:v", "libx264",
          "-preset", "veryfast",
          "-pix_fmt", "yuv420p",
          output.absolutePath
        )
      }

      is PauseSegment -> {
        when (val source = segment.source) {

          is PauseSource.Video -> {
            runFfmpeg(
              "-y",
              "-stream_loop", "-1",
              "-i", source.file.absolutePath,
              "-t", duration.toString(),
              "-c:v", "libx264",
              "-preset", "veryfast",
              "-pix_fmt", "yuv420p",
              output.absolutePath
            )
          }

          PauseSource.Black -> {
            runFfmpeg(
              "-y",
              "-f", "lavfi",
              "-i", "color=c=black:s=1280x720:r=30",
              "-t", duration.toString(),
              "-c:v", "libx264",
              "-preset", "veryfast",
              "-pix_fmt", "yuv420p",
              output.absolutePath
            )
          }
        }
      }
    }
  }

  private fun runFfmpeg(vararg arguments: String) {
    val process = ProcessBuilder(
      listOf("ffmpeg", "-loglevel", "error") + arguments
    )
      .inheritIO()
      .start()

    val exitCode = process.waitFor()

    check(exitCode == 0) {
      "FFmpeg failed with exit code $exitCode"
    }
  }

  private fun formatTime(milliseconds: Long): String {
    val totalSeconds = milliseconds / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return if (hours > 0) {
      "%02d:%02d:%02d".format(hours, minutes, seconds)
    } else {
      "%02d:%02d".format(minutes, seconds)
    }
  }
}

class VideoConcatenator {

  fun concatenate(
    segments: List<File>,
    output: File = File("output.mp4")
  ) {
    require(segments.isNotEmpty()) {
      "No video segments to concatenate"
    }

    for (segment in segments) {
      require(segment.exists() && segment.isFile) {
        "Segment does not exist: ${segment.path}"
      }
    }

    val listFile = File.createTempFile(
      "ffmpeg_concat_",
      ".txt"
    )

    try {
      listFile.writeText(
        segments.joinToString("\n") {
          "file '${it.absoluteFile.path.replace("'", "'\\''")}'"
        }
      )

      output.delete()

      val process = ProcessBuilder(
        "ffmpeg",
        "-y",
        "-f", "concat",
        "-safe", "0",
        "-i", listFile.absolutePath,
        "-c", "copy",
        output.absolutePath
      )
        .inheritIO()
        .start()

      val exitCode = process.waitFor()

      check(exitCode == 0) {
        "FFmpeg concatenation failed with exit code $exitCode"
      }

      check(output.exists() && output.isFile) {
        "Output video was not created: ${output.path}"
      }

    } finally {
      listFile.delete()
    }
  }
}

fun cleanup() {
  val renderDirectory = File("render")

  if (renderDirectory.exists()) {
    renderDirectory
      .listFiles()
      ?.forEach { it.delete() }

    renderDirectory.delete()
  }
}

fun main() {
  println("=== MIDI Video Renderer ===")

  println("[1/7] Loading samples...")
  val samples = SampleLibrary(
    File("samples")
  )
  println("      ${samples.all().size} samples loaded.")

  println("[2/7] Loading pause source...")
  val pause = pauseSource(
    File("samples/pause.mp4")
  )
  println("      Pause source: $pause")

  println("[3/7] Reading MIDI...")
  val notes = MidiEventReader().read(
    File("input.mid")
  )
  println("      ${notes.size} notes found.")

  println("[4/7] Creating MIDI timeline...")
  val timeline = MidiTimeline().create(
    notes
  )
  println("      ${timeline.size} timeline events created.")

  println("[5/7] Planning video segments...")
  val segments = VideoPlanner(
    samples = samples,
    pause = pause
  ).create(
    timeline
  )
  println("      ${segments.size} video segments planned.")

  println("[6/7] Generating video segments...")

  val generatedSegments = VideoSegmentGenerator().generate(
    segments
  )

  println("[7/7] Concatenating video...")

  val output = File("output.mp4")

  VideoConcatenator().concatenate(
    segments = generatedSegments,
    output = output
  )

  println("Cleaning temporary files...")
  cleanup()

  println()
  println("=== Finished ===")
  println("Output: ${output.absolutePath}")
}

 */