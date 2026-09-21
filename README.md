```
                                                                           
 ▄▄▄▄▄▄▄ ▄▄▄                   ▄▄▄▄▄▄▄                      ▄▄             
███▀▀▀▀▀ ███                  █████▀▀▀                      ██             
███▄▄    ███            ██ ██  ▀████▄   ▀▀█▄ ███▄███▄ ████▄ ██ ▄█▀█▄ ████▄ 
███▀▀    ███      ▀▀▀▀▀ ██▄██    ▀████ ▄█▀██ ██ ██ ██ ██ ██ ██ ██▄█▀ ██ ▀▀ 
███      ████████        ▀█▀  ███████▀ ▀█▄██ ██ ██ ██ ████▀ ██ ▀█▄▄▄ ██    
                                                      ██                   
                                                      ▀▀                   
```

A small Kotlin tool that turns a MIDI file into a video performance using pre-recorded videos of individual instrument notes.

The MIDI file controls the musical timeline, while video samples provide the visual performance.

## Motivation

This project exists for two reasons.

The first is the audiovisual idea itself: using a MIDI performance to control a sequence of real recorded instrument videos.

The second, and more personal one, is to **give a real purpose to libraries I built from scratch**.

The MIDI layer of this project is powered by **[FLMidi](https://github.com/LucasAlfare/FLMidi/)**, my own MIDI library written entirely from scratch in Kotlin.

FLMidi was not created in isolation. It was built on top of another personal library, **[FLBinary](https://github.com/LucasAlfare/FLBinary/)**, responsible for reading and writing binary data.

The relationship is essentially:

```text
[FLBinary]
    ↓
 [FLMidi]
    ↓
[FL-vSampler]
    ↓
 Final Video
```

[FLBinary](https://github.com/LucasAlfare/FLBinary/) was built from scratch to understand and manipulate raw binary data.

[FLMidi](https://github.com/LucasAlfare/FLMidi/) was then built on top of it to understand MIDI files.

**FL-vSampler** is the next step: taking that MIDI library and using it for something practical, visual and creative.

In other words, this project is not only about rendering MIDI.

It is also about **putting several pieces I built from scratch to use in a real project**.

## Philosophy

The project follows a simple philosophy:

> Build things from scratch, understand how they work, and eventually use them to create something real.

There is no need for a large framework or an overly abstract architecture.

The processing pipeline is intentionally straightforward:

```text
Recorded Samples
       +
      MIDI
       ↓
Sample Library
       ↓
Pause Source
       ↓
     FLMidi
       ↓
  MIDI Timeline
       ↓
  Video Planner
       ↓
Video Segment Generator
       ↓
Video Concatenator
       ↓
   Final Video
```

The MIDI file controls **when** things happen.

The recorded samples determine **what** is shown.

Periods where no note is being played become pause segments.

The goal is to keep every part of this process understandable and verifiable.

## The Library Stack

One of the interesting aspects of FL-vSampler is that the application is built on top of multiple personal libraries.

### FLBinary

**[FLBinary](https://github.com/LucasAlfare/FLBinary/)** is the foundation.

It is a library I wrote from scratch for reading and writing binary data.

Its purpose is to make low-level binary operations explicit and understandable instead of relying entirely on existing abstractions.

### FLMidi

**[FLMidi](https://github.com/LucasAlfare/FLMidi/)** is my own MIDI library, also written from scratch in Kotlin.

It uses FLBinary to parse MIDI files and expose their musical structure.

It is responsible for things such as:

* MIDI tracks
* MIDI events
* note-on events
* note-off events
* velocities
* tempo changes
* MIDI timing

### FL-vSampler

**FL-vSampler** uses FLMidi as part of a larger practical application.

The renderer takes the musical information extracted by FLMidi and connects it to real video samples.

This creates a small stack of personally built software:

```text
FLBinary
   ↓
Binary data
   ↓
FLMidi
   ↓
MIDI data
   ↓
Musical timeline
   ↓
FL-vSampler
   ↓
Video rendering
```

That progression is one of the main reasons this project exists.

## Rendering Strategy

A MIDI note such as:

```text
C4
start: 1000 ms
duration: 500 ms
```

is converted into a video segment using:

```text
samples/C4.mp4
```

A gap in the MIDI timeline becomes a pause segment.

For example:

```text
C4 ──────
         500 ms pause
                  E4 ─────
```

becomes:

```text
C4.mp4 → pause.mp4 → E4.mp4
```

Each segment is generated independently with FFmpeg.

The resulting segments are then concatenated into the final video.

Pause videos are looped when necessary.

If no pause video exists, FL-vSampler uses a generated black background.

## Requirements

### Java / Kotlin

A Kotlin/JVM project with a compatible JDK.

### FFmpeg

FFmpeg must be installed and available through the system `PATH`.

Verify it with:

```bash
ffmpeg -version
```

### FLMidi

The project requires my personal **[FLMidi](https://github.com/LucasAlfare/FLMidi/)** library and its dependencies, including **[FLBinary](https://github.com/LucasAlfare/FLBinary/)**.

The exact API is specific to this project.

## Project Structure

The application expects:

```text
project/
├── samples/
│   ├── C3.mp4
│   ├── D3.mp4
│   ├── E3.mp4
│   └── ...
├── input.mid
└── src/
    └── ...
```

The pause video is optional:

```text
samples/pause.mp4
```

If it does not exist, a black background is used.

Sample names must match MIDI note names:

```text
C3.mp4
C#3.mp4
D3.mp4
D#3.mp4
E3.mp4
...
```

## Usage

Place the MIDI file at:

```text
input.mid
```

Place the recorded note videos inside:

```text
samples/
```

Then run the Kotlin application.

FL-vSampler will:

1. Load the samples.
2. Load the pause source.
3. Read the MIDI file using FLMidi.
4. Build the MIDI timeline.
5. Create the video rendering plan.
6. Generate the individual video segments.
7. Concatenate the segments.

The final video is:

```text
output.mp4
```

Temporary segments are generated inside:

```text
render/
```

and removed after the pipeline finishes.

## Usage Tips

The quality of the input MIDI can have a significant impact on the final video.

For better results, it is recommended to **prepare or clean up the MIDI file before using it with FL-vSampler**.

Ideally, use a MIDI file with:

* Correct note information.
* Reasonable note durations.
* As few unnecessary overlapping notes as possible.
* Clean timing.
* No duplicated or accidental notes.
* A musical structure that makes sense for the video samples being used.

In particular, **overlapping notes and unnecessarily dense MIDI data can produce undesirable results**, since the current renderer treats MIDI notes individually and does not yet have a sophisticated system for handling simultaneous notes.

A cleaner MIDI file generally produces a cleaner video.

If possible, prepare the MIDI specifically for the rendering process rather than simply using an arbitrary MIDI file downloaded from the internet.

## Rendering Cache

The segment generator contains a small cache.

When the same source video is required with the same duration, it is rendered once and reused.

Segment generation also uses a limited number of workers so that multiple FFmpeg processes can run concurrently without creating an excessive number of processes.

## Current Limitations

The first version intentionally keeps the feature set small.

It currently does not implement:

* **Chords / simultaneous notes** — multiple notes occurring at the same time are not yet handled as a proper visual chord or multi-video composition.
* Velocity-dependent samples.
* Articulations.
* Bends.
* Slides.
* Sustain.
* Multiple instruments.
* Multiple cameras.
* Transitions.
* Visual effects.
* Audio synchronization.
* Automatic sample selection.

### Micro-pauses between clips

There is currently a known issue where a very small **micro-pause can sometimes appear between concatenated video clips**.

The exact cause has not yet been identified.

The segments are generated individually and then concatenated with FFmpeg, but for some reason the final video may contain tiny gaps or visual pauses between clips.

This is one of the current problems I am still investigating.

**Suggestions and ideas for solving this are very welcome.**

If you have experience with FFmpeg concatenation, video timestamps, keyframes, frame boundaries, codecs, or related issues, feel free to suggest possible causes or solutions.

## Development Philosophy

The project is intentionally kept simple during development.

The current implementation uses a single Kotlin source file so that the entire pipeline can be inspected and understood easily.

The development priority is:

```text
Understand
    ↓
Build from scratch
    ↓
Use in a real project
    ↓
Validate
    ↓
Optimize
    ↓
Extend
```

The objective is not to create the most sophisticated MIDI renderer possible.

It is to take low-level code written from scratch, build useful abstractions on top of it, and eventually turn those abstractions into something creative and practical.

## Related Projects

This project is built on top of two personal libraries:

* **[FLBinary](https://github.com/LucasAlfare/FLBinary/)** — low-level binary data reading and writing.
* **[FLMidi](https://github.com/LucasAlfare/FLMidi/)** — MIDI parsing and interpretation built on top of FLBinary.

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