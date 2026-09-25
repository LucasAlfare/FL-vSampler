package com.lucasalfare.flvsampler.midi

import com.lucasalfare.flmidi.Event
import com.lucasalfare.flmidi.MidiReader
import com.lucasalfare.flmidi.NoteOffControlEvent
import com.lucasalfare.flmidi.NoteOnControlEvent
import com.lucasalfare.flmidi.SetTempoMetaEvent
import java.io.File

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
    require(file.exists()) {
      "MIDI file not found: ${file.absolutePath}"
    }

    val midi = MidiReader.fromFile(file.path)

    require(midi.division > 0) {
      "Invalid MIDI time division: ${midi.division}"
    }

    val events = buildList {
      for (track in midi.tracks) {
        var tick = 0L

        for (event in track) {
          tick += event.deltaTime
          add(TimedEvent(tick, event))
        }
      }
    }.sortedBy { it.tick }

    val tempos = buildList {
      for (event in events) {
        if (event.event is SetTempoMetaEvent) {
          add(
            TempoEvent(
              tick = event.tick,
              tempo = event.event.tempo
            )
          )
        }
      }
    }.toMutableList()

    /*
     * MIDI files commonly start with 500,000 microseconds per quarter
     * note (120 BPM). If the file does not explicitly define a tempo
     * at tick zero, use that standard default.
     */
    if (tempos.none { it.tick == 0L }) {
      tempos += TempoEvent(0L, DEFAULT_TEMPO)
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
              .getOrPut(event.channel to event.note, ::mutableListOf)
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

  /**
   * Converts a MIDI note number to scientific pitch notation.
   *
   * For example:
   *
   * `60 -> C4`
   * `61 -> C#4`
   */
  private fun midiNoteName(note: Int): String =
    MIDI_NOTE_NAMES[note % 12] + (note / 12 - 1)

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

  companion object {
    private const val DEFAULT_TEMPO = 500_000

    private val MIDI_NOTE_NAMES = arrayOf(
      "C", "C#", "D", "D#", "E", "F",
      "F#", "G", "G#", "A", "A#", "B"
    )
  }
}