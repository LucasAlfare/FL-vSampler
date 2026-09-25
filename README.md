```
                                                                           
 ▄▄▄▄▄▄▄ ▄▄▄                   ▄▄▄▄▄▄▄                      ▄▄             
███▀▀▀▀▀ ███                  █████▀▀▀                      ██             
███▄▄    ███            ██ ██  ▀████▄   ▀▀█▄ ███▄███▄ ████▄ ██ ▄█▀█▄ ████▄ 
███▀▀    ███      ▀▀▀▀▀ ██▄██    ▀████ ▄█▀██ ██ ██ ██ ██ ██ ██ ██▄█▀ ██ ▀▀ 
███      ████████        ▀█▀  ███████▀ ▀█▄██ ██ ██ ██ ████▀ ██ ▀█▄▄▄ ██    
                                                      ██                   
                                                      ▀▀                   
```

# FL-vSampler

A Kotlin tool that turns a MIDI file into a video performance using pre-recorded videos of individual instrument notes.

The MIDI file controls the musical timeline, while the recorded samples provide the audio and visual performance.

## How It Works

The complete pipeline is:

```text
MIDI
 ↓
FLMidi
 ↓
MIDI Timeline
 ↓
 ┌────────────────────────┐
 │                        │
 ▼                        ▼
Audio Pipeline       Video Pipeline
 │                        │
 ▼                        ▼
Sample Audio         Sample Frames
 │                        │
 ▼                        ▼
Float32 RAM          LRU Frame Cache
 │                        │
 ▼                        ▼
Chunked Mixing       Lazy Extraction
 │                        │
 ▼                        ▼
Peak Analysis        Frame Streaming
 │                        │
 ▼                        │
Normalized WAV            │
 │                        │
 └────────────┬───────────┘
              ▼
            FFmpeg
              ↓
          output.mp4
```

## 1. Read the MIDI

The MIDI file is read using **[FLMidi](https://github.com/LucasAlfare/FLMidi/)**.

The MIDI events are converted into a timeline containing:

- Note.
- Start time.
- Duration.
- Velocity.
- Tempo information.

The timeline becomes the timing reference for both audio and video.

## 2. Load the Samples

The project expects one video sample for each instrument note:

```text
samples/
├── C3.mp4
├── C#3.mp4
├── D3.mp4
├── D#3.mp4
├── E3.mp4
└── ...
```

An optional pause sample can also be provided:

```text
samples/pause.mp4
```

The sample videos contain both the instrument audio and the visual performance.

Sample videos should ideally have consistent duration, resolution and frame rate.

## 3. Prepare the Audio

Only the audio from the sample videos actually required by the MIDI is extracted.

FFmpeg converts the audio to:

```text
48 kHz
Stereo
PCM16
```

The samples are then represented as `Float32` data in RAM.

The complete final audio is not stored in RAM.

## 4. Generate the Master Audio

The MIDI timeline is processed in configurable chunks.

For each note, its corresponding sample audio is placed at the correct position in the timeline.

Overlapping notes are mixed together.

A short fade-out is applied at the end of note playback.

The audio rendering happens in two passes.

### Pass 1

The complete timeline is analyzed to find the global audio peak.

### Pass 2

The timeline is processed again, applying the normalization factor and writing the final audio incrementally to:

```text
master_audio.wav
```

## 5. Generate the Video Frames

The final video is processed frame by frame.

For each output frame, the renderer determines:

1. The current MIDI timeline position.
2. Which note or pause is active.
3. Which sample video should be used.
4. Which frame from that sample corresponds to the current position.

The complete source videos are never loaded into RAM.

Only the required frames are extracted.

## 6. Frame Cache

Extracted frames are stored in an LRU cache in RAM.

The cache has a configurable maximum size, for example:

```text
512 MB
```

When a requested frame is already cached, it is reused.

When it is not cached, FFmpeg extracts the frame and it is added to the cache.

When the cache reaches its limit, the least recently used frames are removed.

This keeps video memory usage bounded regardless of the length of the final MIDI performance.

```text
Request Frame
     ↓
   Cache?
 ┌───┴───┐
Yes      No
 │        │
 ▼        ▼
Reuse   FFmpeg
          ↓
       JPEG Frame
          ↓
        Cache
```

## 7. Stream the Video

The generated JPEG frames are sent directly to FFmpeg through a pipe.

The complete video is therefore never accumulated in memory and no intermediate video segment is created for every MIDI note.

```text
Sample Video
     ↓
Frame Extraction
     ↓
JPEG
     ↓
LRU Cache
     ↓
image2pipe
     ↓
FFmpeg
```

## 8. Create the Final Video

FFmpeg receives:

```text
Video frames → image2pipe
Audio        → master_audio.wav
```

and produces:

```text
output.mp4
```

The final encoding uses:

- H.264 / libx264 for video.
- AAC for audio.
- `yuv420p` pixel format.

The final result is a continuous video synchronized to the MIDI timeline.

## Requirements

### Java / Kotlin

A compatible Kotlin/JVM environment and JDK.

### FFmpeg

FFmpeg must be installed and available through the system `PATH`.

Verify with:

```bash
ffmpeg -version
```

### FLMidi

The project uses:

- **[FLMidi](https://github.com/LucasAlfare/FLMidi/)** — MIDI parsing.
- **[FLBinary](https://github.com/LucasAlfare/FLBinary/)** — binary data handling used by FLMidi.

## Project Structure

```text
project/
├── samples/
│   ├── C3.mp4
│   ├── C#3.mp4
│   ├── D3.mp4
│   ├── D#3.mp4
│   ├── E3.mp4
│   ├── ...
│   └── pause.mp4
├── input.mid
└── src/
    └── ...
```

The pause sample is optional.

If it is unavailable, the renderer can use a fallback frame for pauses.

Sample filenames must match MIDI note names:

```text
C3.mp4
C#3.mp4
D3.mp4
D#3.mp4
E3.mp4
...
```

## Usage

1. Place the MIDI file at:

```text
input.mid
```

2. Place the recorded note videos inside:

```text
samples/
```

3. Make sure FFmpeg is available through `PATH`.

4. Run the Kotlin application.

The renderer will:

```text
Read MIDI
    ↓
Build timeline
    ↓
Extract required sample audio
    ↓
Generate normalized master audio
    ↓
Generate video frames on demand
    ↓
Cache frames in RAM
    ↓
Stream frames to FFmpeg
    ↓
Combine video + master audio
    ↓
output.mp4
```

Temporary rendering data is removed when processing finishes.

## Usage Tips

For better results, prepare or clean up the MIDI before rendering.

Ideally, use MIDI with:

- Correct notes.
- Reasonable note durations.
- Clean timing.
- No duplicated or accidental notes.
- As few unnecessary overlapping notes as possible.
- A musical structure compatible with the available samples.

The current visual renderer does not yet implement sophisticated visualization of simultaneous notes, so dense MIDI files and overlapping notes may produce undesirable visual results.

## Current Limitations

The current implementation does not yet provide:

- Proper visual chords / simultaneous-note composition.
- Velocity-dependent samples.
- Articulations.
- Bends.
- Slides.
- Sustain.
- Multiple instruments.
- Multiple cameras.
- Visual effects.
- Automatic sample selection.
- Advanced musical expression.

The audio pipeline can mix overlapping notes, but the visual pipeline still selects a primary sample for each output position.

## Related Projects

- **[FLBinary](https://github.com/LucasAlfare/FLBinary/)** — binary data reading and writing.
- **[FLMidi](https://github.com/LucasAlfare/FLMidi/)** — MIDI parsing and interpretation built on top of FLBinary.

## [LICENSE](LICENSE)

MIT License

Copyright (c) 2026 Francisco Lucas

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.