package com.lucasalfare.flvsampler

import com.lucasalfare.flmidi.Event
import com.lucasalfare.flmidi.MidiReader
import com.lucasalfare.flmidi.NoteOffControlEvent
import com.lucasalfare.flmidi.NoteOnControlEvent
import com.lucasalfare.flmidi.SetTempoMetaEvent
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays
import java.util.LinkedHashMap
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos

/*
Você vai implementar suporte a acordes neste seguinte projeto.

## OBJETIVO ABSOLUTO

Transformar o renderer de vídeo de um sistema que renderiza apenas um sample por vez em um sistema capaz de renderizar simultaneamente todos os samples correspondentes às notas MIDI que estiverem ativas naquele instante.

REGRA PRINCIPAL:

**O comportamento atual para uma única nota deve continuar funcionando. Quando houver N notas simultaneamente ativas, o vídeo deve mostrar os N samples simultaneamente organizados em uma grade, sem alterar a lógica de síntese de áudio.**

Não reescreva partes funcionais sem necessidade.

## RESTRIÇÕES

1. Não modificar desnecessariamente `MidiEventReader`.
2. Não modificar a API nem a implementação de FLMidi.
3. Não modificar a lógica existente de síntese de áudio para implementar acordes.
4. Manter `MidiTimeline` e `TimelineNote` funcionando para o áudio.
5. Criar uma representação visual separada para os acordes.
6. Manter compatibilidade total com MIDI monofônico.
7. Não fazer otimizações prematuras.
8. Não introduzir frameworks ou dependências externas.
9. Preferir classes pequenas e responsabilidades claras.
10. Não remover a documentação existente.
11. Não criar uma arquitetura excessivamente abstrata.

## SEMÂNTICA DOS ACORDES

Não agrupe somente notas que possuem exatamente o mesmo `start`.

A timeline visual deve representar o conjunto de notas efetivamente ativas em cada intervalo de tempo.

Use todos os pontos de início e fim das notas como boundaries temporais.

Exemplo:

C4: 1000–2000
E4: 1000–1500
G4: 1000–2000

Deve produzir:

1000–1500 → [C4, E4, G4]
1500–2000 → [C4, G4]

Outro exemplo:

C4: 1000–2000
E4: 1200–1800

Deve produzir:

1000–1200 → [C4]
1200–1800 → [C4, E4]
1800–2000 → [C4]

Portanto, a unidade de renderização visual deve ser um segmento temporal contendo uma lista de notas ativas.

## NOVA TIMELINE VISUAL

Criar uma estrutura separada para vídeo, por exemplo:

* `VideoTimelineEvent`
* `VideoNotesSegment`
* `VideoTimeline`

Os nomes podem ser ajustados caso exista nomenclatura melhor no projeto, mas a responsabilidade deve permanecer separada da timeline usada pelo áudio.

Cada segmento deve possuir:

* `start`
* `duration`
* `notes`

Uma lista vazia de notas representa pausa.

Ignorar notas com duração zero.

## LAYOUT

Implementar inicialmente exatamente estas regras:

1 nota  → 1×1
2 notas → 2×1
3 notas → 3×1
4 notas → 2×2

Para mais de 4 notas, não inventar uma regra silenciosamente. Gerar erro explícito e facilmente modificável.

Criar uma pequena abstração para representar o layout:

* número de colunas
* número de linhas

## COMPOSIÇÃO DOS FRAMES

O renderer atual obtém frames individuais dos arquivos dos samples e os envia para o FFmpeg.

Manter essa estratégia.

Quando houver várias notas ativas:

1. obter o frame correspondente de cada sample;
2. redimensionar cada frame para sua célula;
3. preservar a proporção original do vídeo;
4. centralizar o vídeo dentro da célula;
5. preencher áreas restantes com preto;
6. montar todos os frames em uma única imagem;
7. enviar somente essa imagem final para o pipe do FFmpeg.

Não iniciar um processo FFmpeg separado para cada sample ou para cada frame.

Usar as APIs padrão da JVM disponíveis no projeto para decodificação, composição, redimensionamento e codificação dos frames, evitando novas dependências.

## RESOLUÇÃO

O renderer atual trabalha com frames-base de 1280×720.

Manter 1280×720 como canvas final.

A grade deve dividir o canvas entre suas células.

Não distorcer os vídeos para preencher a célula. Preservar a proporção 16:9 e usar letterboxing quando necessário.

## TEMPO DOS SAMPLES

Para cada nota ativa, calcular o frame usando o tempo decorrido desde `note.start`.

Não usar o início do segmento visual como referência para o sample.

Por exemplo:

`sampleFrameIndex = elapsedSinceNoteStart * fps`

Assim uma nota que entrou no acorde depois do seu início original continuará sendo reproduzida do ponto correto.

## NOTAS QUE TERMINAM ANTES

Quando uma nota de um acorde terminar antes das outras, ela deixa de fazer parte do conjunto ativo.

A célula correspondente deve mostrar o primeiro frame do `pause.mp4`, quando esse arquivo existir.

Usar o mecanismo de pause/fallback já existente como base.

## PIPELINE

Adicionar ao `PipelineContext` a timeline visual separada.

A pipeline deve ficar conceitualmente:

read MIDI
→ create audio timeline
→ create video timeline
→ initialize engines
→ load samples
→ synthesize audio
→ render video
→ cleanup

A síntese de áudio deve continuar recebendo a timeline original com `TimelineNote`, pois ela já suporta sobreposição de notas.

## RENDERER

Modificar `MemoryVideoRenderer` para trabalhar com os segmentos visuais.

O comportamento monofônico deve continuar equivalente ao comportamento atual:

um segmento com uma nota deve simplesmente resultar em um frame daquela nota.

Para segmentos com múltiplas notas, gerar o frame composto.

Eliminar a premissa atual de que existe apenas um `activeEvent` visual por frame.

## CACHE

Manter `RamFrameCache`.

Inicialmente, não alterar sua estratégia nem criar um novo sistema de cache.

Somente adaptar o fluxo para permitir vários frames por frame composto.

Depois da implementação funcional, analisar eventuais gargalos.

## LOGS

Manter os logs existentes e adicionar apenas os necessários para diagnóstico.

Durante a leitura da timeline visual, informar quantos segmentos existem e, se útil, a maior quantidade de notas simultâneas encontrada.

Durante o render, informar a configuração da grade quando ela mudar, sem imprimir uma linha para cada frame.

## TESTES FUNCIONAIS OBRIGATÓRIOS

Verificar explicitamente:

* 1 nota;
* 2 notas simultâneas;
* 3 notas simultâneas;
* 4 notas simultâneas;
* acordes com durações diferentes;
* notas sobrepostas com starts diferentes;
* pausas;
* sample inexistente;
* `pause.mp4` inexistente;
* mais de 4 notas simultaneamente.

Critério principal:

**O áudio deve permanecer exatamente com a semântica atual, enquanto o vídeo passa a representar visualmente todas as notas ativas.**

Antes de alterar código, analise as classes existentes e reaproveite o que já existe.

Ao final:

1. apresente a estrutura final dos arquivos;
2. apresente o código completo dos arquivos alterados ou criados;
3. não omita trechos importantes com comentários como `// restante do código`;
4. explique brevemente quais responsabilidades foram separadas;
5. confirme que a lógica de áudio não foi alterada.
 */

object Profiler {
  fun logMemory(tag: String) {
    val runtime = Runtime.getRuntime()
    val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
    val maxMb = runtime.maxMemory() / (1024 * 1024)
    println("[PROFILER] Memory — $tag: $usedMb MB used / $maxMb MB JVM maximum")
  }

  inline fun <T> measure(tag: String, block: () -> T): T {
    println(); println("[PROFILER] Starting: $tag"); logMemory("Before $tag")
    val start = System.currentTimeMillis()
    val result = block()
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
    if (notes.isEmpty()) activeNotes.remove(key)
  }

  private fun ticksToMillis(targetTick: Long, tempos: List<TempoEvent>, division: Int): Long {
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

// --- REPRESENTAÇÃO DA TIMELINE VISUAL DE VÍDEO ---

data class VideoNotesSegment(
  val start: Long,
  val duration: Long,
  val notes: List<NoteEvent>
)

class VideoTimeline {
  fun create(notes: List<NoteEvent>): List<VideoNotesSegment> {
    val validNotes = notes.filter { it.duration > 0 }
    if (validNotes.isEmpty()) return emptyList()

    val boundaries = mutableSetOf<Long>()
    boundaries.add(0L)
    for (note in validNotes) {
      boundaries.add(note.start)
      boundaries.add(note.start + note.duration)
    }

    val sortedBoundaries = boundaries.sorted()
    val segments = mutableListOf<VideoNotesSegment>()

    for (i in 0 until sortedBoundaries.size - 1) {
      val segStart = sortedBoundaries[i]
      val segEnd = sortedBoundaries[i + 1]
      val segDuration = segEnd - segStart
      if (segDuration <= 0) continue

      val activeNotes = validNotes.filter { note ->
        note.start <= segStart && (note.start + note.duration) >= segEnd
      }

      segments.add(VideoNotesSegment(segStart, segDuration, activeNotes))
    }

    return segments
  }
}

data class GridLayout(val cols: Int, val rows: Int) {
  companion object {
    fun forNoteCount(count: Int): GridLayout = when (count) {
      0, 1 -> GridLayout(1, 1)
      2 -> GridLayout(2, 1)
      3 -> GridLayout(3, 1)
      4 -> GridLayout(2, 2)
      else -> throw IllegalArgumentException(
        "Número de notas simultâneas não suportado para o layout visual: $count. O limite máximo é 4."
      )
    }
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
    val left = FloatArray(totalSamples)
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

    val masterL = FloatArray(chunkSamples)
    val masterR = FloatArray(chunkSamples)
    val totalChunks = (totalSamples + chunkSamples - 1) / chunkSamples

    println(); println("[AUDIO] Pass 1/2: calculating global peak...")
    var maxPeak = 0.0f
    var chunkStart = 0
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
      var processedSamples = 0
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
      val key = event.sampleKey()
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
    val totalDataLen = totalSamples.toLong() * 4
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
    val header = ByteArray(44)
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
  private var currentBytes = 0L
  private var hits = 0L
  private var misses = 0L
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
      val iterator = cache.entries.iterator()
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

  // Cache LRU simples para imagens BufferedImage de samples já decodificados
  private val decodedImageCache = object : LinkedHashMap<String, BufferedImage>(32, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BufferedImage>?): Boolean {
      return size > 64 // Limite ajustável de quadros decodificados em RAM
    }
  }

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

  private fun getDecodedFrame(videoFile: File, frameIndex: Int, fallbackFrameBytes: ByteArray): BufferedImage {
    val key = "${videoFile.absolutePath}_$frameIndex"
    decodedImageCache[key]?.let { return it }

    val frameBytes = try {
      getFrame(videoFile, frameIndex)
    } catch (e: Exception) {
      fallbackFrameBytes
    }

    val img = ImageIO.read(ByteArrayInputStream(frameBytes))
      ?: ImageIO.read(ByteArrayInputStream(fallbackFrameBytes))

    if (img != null) {
      decodedImageCache[key] = img
    }
    return img
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
    videoTimeline: List<VideoNotesSegment>,
    frameSources: Map<String, File>,
    masterAudioWav: File,
    outputMp4: File
  ) {
    require(videoTimeline.isNotEmpty()) { "Cannot render video from an empty timeline." }
    require(masterAudioWav.exists()) { "Master audio file not found: ${masterAudioWav.absolutePath}" }

    val totalMs = videoTimeline.maxOf { it.start + it.duration }
    val frameDurationMs = 1000.0 / fps
    val totalFrames = ceil(totalMs / frameDurationMs).toInt()
    require(totalFrames > 0) { "Timeline does not contain any renderable frames." }

    val fallbackSource = frameSources[PAUSE_KEY] ?: frameSources.values.firstOrNull()
    ?: error("No video sample is available for rendering.")
    val fallbackFrameBytes = getFrame(fallbackSource, 0)

    // Mudança importante: alterado para rawvideo rgb24 eliminando o overhead de encoder MJPEG via Java
    val process = ProcessBuilder(
      "ffmpeg",
      "-y",
      "-f", "rawvideo",
      "-pixel_format", "rgb24",
      "-video_size", "1280x720",
      "-framerate", fps.toString(),
      "-i", "pipe:0",
      "-i", masterAudioWav.absolutePath,
      "-c:v", "libx264",
      "-preset", "fast",
      "-pix_fmt", "yuv420p",
      "-c:a", "aac",
      "-b:a", "192k",
      "-shortest",
      outputMp4.absolutePath
    ).redirectError(ProcessBuilder.Redirect.INHERIT).start()

    println("[VIDEO] Streaming $totalFrames frames via raw RGB24 pipeline..."); println("[FRAME CACHE] Initial: ${frameCache.stats()}")

    var activeIndex = 0
    var lastLoggedLayout: GridLayout? = null

    // REUSO DE RECURSOS DE CANVAS (Alocados 1 única vez fora do loop)
    val compositeCanvas = BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB)
    val g2d = compositeCanvas.createGraphics()
    g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)

    // Buffer de bytes reaproveitável para escrita de pixel RGB24
    val rgbBuffer = ByteArray(1280 * 720 * 3)
    val pixelData = (compositeCanvas.raster.dataBuffer as DataBufferInt).data

    process.outputStream.use { pipeOut ->
      for (frameIdx in 0 until totalFrames) {
        val timeMs = (frameIdx * frameDurationMs).toLong()

        while (activeIndex < videoTimeline.size && timeMs >= videoTimeline[activeIndex].start + videoTimeline[activeIndex].duration) {
          activeIndex++
        }

        val activeSegment =
          videoTimeline.getOrNull(activeIndex)?.takeIf { timeMs >= it.start && timeMs < it.start + it.duration }
        val activeNotes = activeSegment?.notes ?: emptyList()
        val layout = GridLayout.forNoteCount(activeNotes.size)

        if (layout != lastLoggedLayout) {
          println("[VIDEO] Grid layout changed to ${layout.cols}x${layout.rows} (${activeNotes.size} active notes)")
          lastLoggedLayout = layout
        }

        // Desenha diretamente no BufferedImage reusado
        renderToCompositeCanvas(
          g2d = g2d,
          activeNotes = activeNotes,
          layout = layout,
          timeMs = timeMs,
          frameSources = frameSources,
          fallbackSource = fallbackSource,
          fallbackFrameBytes = fallbackFrameBytes
        )

        // Converter os dados de pixels INT_RGB para o buffer RGB24 de bytes para o Pipe sem alocar nova memória
        convertIntRgbToRgb24Buffer(pixelData, rgbBuffer)

        // Envia os pixels brutos diretamente para o Pipe do FFmpeg
        pipeOut.write(rgbBuffer)

        if (frameIdx > 0 && frameIdx % 1000 == 0) {
          println(); println("[VIDEO] Frame $frameIdx / $totalFrames")
          Profiler.logMemory("Video rendering"); println("[FRAME CACHE] ${frameCache.stats()}")
        }
      }
      pipeOut.flush()
    }

    g2d.dispose()
    check(process.waitFor() == 0) { "FFmpeg video encoding failed." }
    println(); println("[FRAME CACHE] Final: ${frameCache.stats()}")
  }

  private fun renderToCompositeCanvas(
    g2d: java.awt.Graphics2D,
    activeNotes: List<NoteEvent>,
    layout: GridLayout,
    timeMs: Long,
    frameSources: Map<String, File>,
    fallbackSource: File,
    fallbackFrameBytes: ByteArray
  ) {
    // 1. Limpa o canvas único
    g2d.color = Color.BLACK
    g2d.fillRect(0, 0, 1280, 720)

    if (activeNotes.isEmpty()) {
      val img = getDecodedFrame(fallbackSource, 0, fallbackFrameBytes)
      g2d.drawImage(img, 0, 0, 1280, 720, null)
      return
    }

    val cellWidth = 1280 / layout.cols
    val cellHeight = 720 / layout.rows

    for (i in activeNotes.indices) {
      val note = activeNotes[i]
      val col = i % layout.cols
      val row = i / layout.cols

      val cellX = col * cellWidth
      val cellY = row * cellHeight

      val source = frameSources[note.note] ?: fallbackSource
      val sampleFrameIdx = ((timeMs - note.start) * fps / 1000.0).toInt().coerceAtLeast(0)

      val img = getDecodedFrame(source, sampleFrameIdx, fallbackFrameBytes)

      val imgWidth = img.width
      val imgHeight = img.height
      val imgAspect = imgWidth.toDouble() / imgHeight
      val cellAspect = cellWidth.toDouble() / cellHeight

      var drawWidth = cellWidth
      var drawHeight = cellHeight

      if (imgAspect > cellAspect) {
        drawHeight = (cellWidth / imgAspect).toInt()
      } else {
        drawWidth = (cellHeight * imgAspect).toInt()
      }

      val drawX = cellX + (cellWidth - drawWidth) / 2
      val drawY = cellY + (cellHeight - drawHeight) / 2

      g2d.drawImage(img, drawX, drawY, drawWidth, drawHeight, null)
    }
  }

  private fun convertIntRgbToRgb24Buffer(srcPixels: IntArray, dstBuffer: ByteArray) {
    var srcIdx = 0
    var dstIdx = 0
    val totalPixels = srcPixels.size
    while (srcIdx < totalPixels) {
      val pixel = srcPixels[srcIdx]
      dstBuffer[dstIdx] = (pixel shr 16 and 0xFF).toByte()     // R
      dstBuffer[dstIdx + 1] = (pixel shr 8 and 0xFF).toByte()  // G
      dstBuffer[dstIdx + 2] = (pixel and 0xFF).toByte()        // B
      srcIdx++
      dstIdx += 3
    }
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

data class SamplerConfig(
  val frameCacheMaxMb: Int = 512,
  val audioChunkSeconds: Int = 10,
  val audioSampleRate: Int = 48_000,
  val videoFps: Int = 60,
  val samplesDir: File = File("samples"),
  val midiFile: File = File("input.mid"),
  val cacheDir: File = File("render_cache"),
  val outputFile: File = File("output.mp4")
)

class PipelineContext(val config: SamplerConfig) {
  val pauseFile = File(config.samplesDir, "pause.mp4")
  val masterWav = File(config.cacheDir, "master_audio.wav")
  val audioSynth =
    AudioSynthesizer(sampleRate = config.audioSampleRate, chunkDurationSeconds = config.audioChunkSeconds)
  val videoRenderer = MemoryVideoRenderer(fps = config.videoFps, maxCacheMb = config.frameCacheMaxMb)
  val pcmSamples = mutableMapOf<String, AudioSample>()
  val frameSources = mutableMapOf<String, File>()
  var notes: List<NoteEvent> = emptyList()
  var timeline: List<TimelineEvent> = emptyList()
  var videoTimeline: List<VideoNotesSegment> = emptyList()
  var uniqueNotes: Set<String> = emptySet()
}

class SamplerPipeline(private val config: SamplerConfig = SamplerConfig()) {
  fun execute() {
    println("=== MIDI VIDEO SAMPLER ===")
    config.cacheDir.mkdirs()
    val context = PipelineContext(config)

    readMidiStep(context)
    initEnginesStep(context)
    loadAudioStep(context)
    synthesizeAudioStep(context)
    renderVideoStep(context)
    cleanupStep(context)
  }

  private fun readMidiStep(context: PipelineContext) {
    println()
    println("[1/5] Reading MIDI...")
    context.notes = MidiEventReader().read(context.config.midiFile)
    context.timeline = MidiTimeline().create(context.notes)
    context.videoTimeline = VideoTimeline().create(context.notes)
    context.uniqueNotes = context.notes.map { it.note }.toSet()

    val maxSimultaneous = context.videoTimeline.maxOfOrNull { it.notes.size } ?: 0
    println("[MIDI] Found ${context.notes.size} notes using ${context.uniqueNotes.size} unique pitches.")
    println("[MIDI] Audio timeline events: ${context.timeline.size}")
    println("[VIDEO] Video timeline created with ${context.videoTimeline.size} segments (max simultaneous notes: $maxSimultaneous).")
  }

  private fun initEnginesStep(context: PipelineContext) {
    println()
    println("[2/5] Initializing rendering engines...")
  }

  private fun loadAudioStep(context: PipelineContext) {
    println()
    println("[3/5] Loading audio samples...")
    Profiler.measure("Loading audio samples") {
      for (note in context.uniqueNotes) {
        val sampleVideo = File(context.config.samplesDir, "$note.mp4")
        if (!sampleVideo.exists()) {
          println("[WARNING] Missing sample for note $note: ${sampleVideo.absolutePath}")
          continue
        }
        println("[AUDIO] Loading: ${sampleVideo.name}")
        context.pcmSamples[note] = context.audioSynth.extractSamplePcm(
          videoFile = sampleVideo,
          tempWav = File(context.config.cacheDir, "sample_$note.wav")
        )
        context.frameSources[note] = sampleVideo
      }
      if (context.pauseFile.exists()) {
        println("[AUDIO] Loading pause sample...")
        context.pcmSamples[PAUSE_KEY] = context.audioSynth.extractSamplePcm(
          videoFile = context.pauseFile,
          tempWav = File(context.config.cacheDir, "sample_pause.wav")
        )
        context.frameSources[PAUSE_KEY] = context.pauseFile
      } else {
        println("[WARNING] No pause sample found. Timeline gaps will use the fallback frame.")
      }
    }
    require(context.frameSources.isNotEmpty()) { "No video samples were found in ${context.config.samplesDir.absolutePath}." }
  }

  private fun synthesizeAudioStep(context: PipelineContext) {
    println()
    println("[4/5] Synthesizing master audio...")
    Profiler.measure("Synthesizing master audio") {
      context.audioSynth.synthesize(
        timeline = context.timeline,
        samples = context.pcmSamples,
        outputFile = context.masterWav
      )
    }
  }

  private fun renderVideoStep(context: PipelineContext) {
    println()
    println("[5/5] Rendering final MP4...")
    println("[CONFIG] Frame cache limit: ${context.config.frameCacheMaxMb} MB")
    println("[CONFIG] Audio chunk size: ${context.config.audioChunkSeconds} seconds")
    println("[CONFIG] Video FPS: ${context.config.videoFps}")
    Profiler.measure("Rendering and encoding final MP4") {
      context.videoRenderer.renderVideo(
        videoTimeline = context.videoTimeline,
        frameSources = context.frameSources,
        masterAudioWav = context.masterWav,
        outputMp4 = context.config.outputFile
      )
    }
  }

  private fun cleanupStep(context: PipelineContext) {
    println()
    println("[CLEANUP] Removing temporary files...")
    context.config.cacheDir.deleteRecursively()
    println("[CLEANUP] Temporary files removed.")
    println()
    println("=== PROCESSING COMPLETED SUCCESSFULLY ===")
    println("Output: ${context.config.outputFile.absolutePath}")
  }
}

fun main() {
  SamplerPipeline().execute()
}