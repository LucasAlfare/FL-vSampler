package com.lucasalfare.flvsampler

import com.lucasalfare.flvsampler.audio.AudioSample
import com.lucasalfare.flvsampler.audio.AudioSynthesizer
import com.lucasalfare.flvsampler.midi.MidiEventReader
import com.lucasalfare.flvsampler.midi.MidiTimeline
import com.lucasalfare.flvsampler.midi.TimelineNote
import com.lucasalfare.flvsampler.profiler.Profiler
import com.lucasalfare.flvsampler.util.PAUSE_KEY
import com.lucasalfare.flvsampler.video.MemoryVideoRenderer
import java.io.File

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