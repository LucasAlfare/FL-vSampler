package com.lucasalfare.flvsampler.midi

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