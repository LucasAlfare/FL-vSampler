package com.lucasalfare.flvsampler.util

import com.lucasalfare.flvsampler.midi.TimelineEvent
import com.lucasalfare.flvsampler.midi.TimelineNote
import com.lucasalfare.flvsampler.midi.TimelinePause

internal const val PAUSE_KEY = "__pause__"

fun TimelineEvent.sampleKey(): String =
  when (this) {
    is TimelineNote -> event.note
    is TimelinePause -> PAUSE_KEY
  }

fun ByteArray.writeAscii(
  offset: Int,
  value: String
) {
  value.forEachIndexed { index, char ->
    this[offset + index] = char.code.toByte()
  }
}