package com.lucasalfare.flvsampler.audio

/**
 * Stereo floating-point PCM audio sample.
 *
 * Each element represents one sample frame, with the corresponding left
 * and right channel values stored separately.
 */
class AudioSample(
  val left: FloatArray,
  val right: FloatArray
)