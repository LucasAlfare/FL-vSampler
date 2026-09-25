package com.lucasalfare.flvsampler.midi

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
  override val start = event.start
  override val duration = event.duration
}

/**
 * Timeline representation of a silent or pause interval.
 */
data class TimelinePause(
  val durationMs: Long,
  override val start: Long
) : TimelineEvent {
  override val duration = durationMs
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
    if (notes.isEmpty()) return emptyList()

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
      position = maxOf(position, note.start + note.duration)
    }

    return timeline
  }
}