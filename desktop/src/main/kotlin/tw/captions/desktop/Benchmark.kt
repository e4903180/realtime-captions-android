package tw.captions.desktop

import org.json.JSONObject
import tw.captions.core.CaptionAssembler
import tw.captions.core.PipelineConfig
import tw.captions.core.TextProcessor
import tw.captions.core.TranscriptEvent
import java.io.File
import java.time.LocalDate
import java.util.Locale

/**
 * Streams every test wav through the sherpa-onnx pipeline in 100 ms chunks and scores it.
 *
 * Timing follows a real-time clock: chunk k becomes available at (k+1)·100 ms of audio time, and
 * the engine can only start on it once it has finished the previous chunk. So an event's wall time
 * is max(previous finish, chunk arrival) + processing time, exactly what a listener would see with
 * a live microphone. Pass --realtime to also sleep for real.
 *
 * Usage: gradlew :desktop:run --args="--models C:/code/sherpa-cache [--quick] [--realtime]"
 */
fun main(args: Array<String>) {
    val opts = args.toList()
    fun opt(name: String) = opts.indexOf(name).takeIf { it >= 0 }?.let { opts[it + 1] }
    val modelsDir = File(opt("--models") ?: System.getenv("SHERPA_MODELS") ?: error("pass --models <dir>"))
    val testdata = File(opt("--testdata") ?: "testdata")
    val out = File(opt("--out") ?: "docs/benchmark.md")
    val quick = "--quick" in opts
    val realTime = "--realtime" in opts

    val truths = File(testdata, "truth").listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }
        .map { parseTruth(it) }
        .filter { File(testdata, "wavs/${it.id}.wav").isFile }
    require(truths.isNotEmpty()) { "no test data; run testdata/generate.ps1 first" }
    val clean = truths.filter { it.noise == "clean" }

    val base = EngineSetup(modelsDir)
    val runs = mutableListOf<Pair<EngineSetup, List<Truth>>>()
    runs += base to truths
    if (!quick) {
        runs += base.copy(asrModel = AsrModel.XASR_480) to truths
        runs += base.copy(asrModel = AsrModel.BILINGUAL_2023) to truths
        runs += base.copy(useVad = false) to clean
        runs += base.copy(pipeline = PipelineConfig(vadEndpointSilenceSec = null)) to clean
        runs += base.copy(pipeline = PipelineConfig(vadEndpointSilenceSec = 0.3f)) to truths
        runs += base.copy(vadThreshold = 0.65f) to truths
        runs += base.copy(speakerModel = SpeakerModel.ERES2NET_BASE) to truths
        for (t in listOf(0.35f, 0.45f, 0.55f, 0.65f)) runs += base.copy(speakerThreshold = t) to truths
    }

    val results = runs.map { (setup, files) ->
        System.err.println("== ${setup.label} (${files.size} files)")
        SherpaStack(setup).use { stack -> setup to files.map { runFile(stack, it, testdata, realTime) } }
    }
    val extra = AsrModel.ALL.associateWith { m ->
        val dir = File(modelsDir, "${m.dir}/test_wavs")
        SherpaStack(base.copy(asrModel = m, speakerModel = null)).use { stack ->
            dir.listFiles { f -> f.extension == "wav" }.orEmpty().sortedBy { it.name }.mapNotNull { f ->
                runCatching { f.name to decodeAll(stack, readWav16k(f)) }.getOrNull()
            }
        }
    }
    out.parentFile.mkdirs()
    out.writeText(report(results, extra, realTime))
    System.err.println("wrote $out")
}

data class Truth(val id: String, val dialog: String, val language: String, val noise: String, val snr: Int?, val duration: Double, val segments: List<Segment>)

fun parseTruth(f: File): Truth {
    val j = JSONObject(f.readText(Charsets.UTF_8))
    val segs = j.getJSONArray("segments")
    return Truth(
        j.getString("id"), j.getString("dialog"), j.getString("language"), j.getString("noise"),
        if (j.isNull("snr_db")) null else j.getInt("snr_db"), j.getDouble("duration"),
        List(segs.length()) {
            val s = segs.getJSONObject(it)
            Segment(s.getDouble("start"), s.getDouble("end"), s.getString("speaker"), s.getString("text"))
        },
    )
}

data class FileResult(
    val truth: Truth,
    val utterances: List<Utterance>,
    val captionText: String,
    val errors: Int,
    val refTokens: Int,
    val firstPartialLatency: List<Double>,
    val finalLatency: List<Double>,
    val speakerAcc: Double,
    val speakers: Int,
    val rtf: Double,
)

fun runFile(stack: SherpaStack, truth: Truth, testdata: File, realTime: Boolean): FileResult {
    val audio = readWav16k(File(testdata, "wavs/${truth.id}.wav"))
    val pipeline = stack.newPipeline()
    val assembler = CaptionAssembler(TextProcessor.default(converter = OpenCcTaiwan))
    val chunk = 1600
    var wall = 0.0
    var busy = 0.0
    val utterances = mutableListOf<Utterance>()
    var uStartAudio = -1.0
    var uFirstPartial = -1.0
    val startNs = System.nanoTime()

    fun handle(events: List<TranscriptEvent>, audioTime: Double, at: Double) {
        for (e in events) {
            assembler.onEvent(e)
            when (e) {
                is TranscriptEvent.Partial -> if (uStartAudio < 0) {
                    uStartAudio = audioTime
                    uFirstPartial = at
                }
                is TranscriptEvent.Final -> {
                    val line = assembler.state.lines.lastOrNull()?.text ?: e.text
                    utterances += Utterance(
                        line, e.speaker?.index,
                        if (uStartAudio < 0) audioTime else uStartAudio, audioTime,
                        if (uFirstPartial < 0) at else uFirstPartial, at,
                    )
                    uStartAudio = -1.0
                    uFirstPartial = -1.0
                }
                else -> {}
            }
        }
    }

    var i = 0
    while (i < audio.size) {
        val n = minOf(chunk, audio.size - i)
        val piece = audio.copyOfRange(i, i + n)
        i += n
        val arrival = i / 16000.0
        if (realTime) {
            val sleepMs = ((arrival * 1000) - (System.nanoTime() - startNs) / 1e6).toLong()
            if (sleepMs > 0) Thread.sleep(sleepMs)
        }
        val t0 = System.nanoTime()
        val events = pipeline.feed(piece)
        val proc = (System.nanoTime() - t0) / 1e9
        busy += proc
        wall = maxOf(wall, arrival) + proc
        handle(events, arrival, wall)
    }
    val t0 = System.nanoTime()
    val tail = pipeline.flush()
    wall += (System.nanoTime() - t0) / 1e9
    handle(tail, audio.size / 16000.0, wall)

    val ref = truth.segments.flatMap { scoringTokens(it.text) }
    val hypText = assembler.state.lines.joinToString(" ") { it.text }
    val hyp = scoringTokens(hypText)

    // Latency per truth segment, using the utterances that best match it.
    val bySeg = utterances.groupBy { matchSegment(it, truth.segments) }
    val ttfp = mutableListOf<Double>()
    val fin = mutableListOf<Double>()
    for ((idx, s) in truth.segments.withIndex()) {
        val us = bySeg[idx] ?: continue
        ttfp += us.minOf { it.firstPartialAt } - s.start
        fin += us.maxOf { it.finalAt } - s.end
    }
    return FileResult(
        truth, utterances, hypText, editDistance(ref, hyp), ref.size, ttfp, fin,
        speakerAccuracy(utterances, truth.segments),
        utterances.mapNotNull { it.speaker }.distinct().size,
        busy / (audio.size / 16000.0),
    )
}

fun decodeAll(stack: SherpaStack, audio: FloatArray): String {
    val p = stack.newPipeline()
    val texts = mutableListOf<String>()
    var i = 0
    while (i < audio.size) {
        val n = minOf(1600, audio.size - i)
        p.feed(audio.copyOfRange(i, i + n)).filterIsInstance<TranscriptEvent.Final>().forEach { texts += it.text }
        i += n
    }
    repeat(10) { p.feed(FloatArray(1600)).filterIsInstance<TranscriptEvent.Final>().forEach { texts += it.text } }
    p.flush().filterIsInstance<TranscriptEvent.Final>().forEach { texts += it.text }
    return TextProcessor.default(converter = OpenCcTaiwan).process(texts.joinToString(" "))
}

private fun f1(x: Double) = if (x.isNaN()) "–" else String.format(Locale.ROOT, "%.1f", x)
private fun f2(x: Double) = if (x.isNaN()) "–" else String.format(Locale.ROOT, "%.2f", x)
private fun pct(x: Double) = if (x.isNaN()) "–" else String.format(Locale.ROOT, "%.1f%%", 100 * x)

private fun errRate(rs: List<FileResult>) = rs.sumOf { it.errors }.toDouble() / rs.sumOf { it.refTokens }.coerceAtLeast(1)

private fun speakerAcc(rs: List<FileResult>): Double {
    val w = rs.filter { !it.speakerAcc.isNaN() }
    return w.sumOf { it.speakerAcc * it.refTokens } / w.sumOf { it.refTokens }.coerceAtLeast(1)
}

private fun conditionOf(t: Truth) = if (t.noise == "clean") "clean" else "${t.noise} ${t.snr} dB"

fun report(results: List<Pair<EngineSetup, List<FileResult>>>, extra: Map<AsrModel, List<Pair<String, String>>>, realTime: Boolean): String = buildString {
    val conditions = listOf("clean", "babble 10 dB", "cafe 10 dB", "babble 5 dB", "cafe 5 dB")
    appendLine("# Offline engine benchmark (sherpa-onnx)")
    appendLine()
    appendLine("Generated by `:desktop` on ${LocalDate.now()} (${System.getProperty("os.name")}, ${Runtime.getRuntime().availableProcessors()} logical CPUs, " +
        "${if (realTime) "real-time playback" else "simulated real-time clock"}). Regenerate with:")
    appendLine()
    appendLine("```")
    appendLine("pwsh -File testdata/generate.ps1           # synthetic dialogs + noisy variants")
    appendLine("./gradlew :desktop:run --args=\"--models <dir with sherpa-onnx models>\"")
    appendLine("```")
    appendLine()
    appendLine("Test set: ${results.first().second.map { it.truth.dialog }.distinct().size} synthetic dialogs spoken by Windows TTS voices " +
        "(zh-TW: Yating, Hanhan, Zhiwei; en: Zira, David, Mark), 2–3 speakers each, 0.6–1.1 s pauses between turns, " +
        "each in clean, babble and café noise at 10 and 5 dB SNR. Audio is streamed in 100 ms chunks.")
    appendLine()
    appendLine("**Metrics.** *Error rate*: character error rate for Chinese (after Traditional conversion), word error rate for English, " +
        "mixed for code-switching; punctuation ignored. *First partial*: time from when a speaker starts until the first caption text appears. " +
        "*Final*: time from when they stop until the caption line is finished (and gets its final speaker tag). " +
        "*Speaker*: share of caption characters with the right speaker tag (best one-to-one mapping of anonymous labels). " +
        "*RTF*: compute time / audio time for the whole pipeline (ASR + VAD + speaker embeddings) on this PC.")
    appendLine()
    appendLine("## Summary")
    appendLine()
    appendLine("| Configuration | Files | zh error clean | zh error 10 dB | zh error 5 dB | en WER clean | en WER 10 dB | First partial p50 / p90 (s) | Final p50 / p90 (s) | Speaker clean | Speaker noisy | RTF |")
    appendLine("|---|---|---|---|---|---|---|---|---|---|---|---|")
    for ((setup, rs) in results) {
        fun sel(lang: String, vararg cond: String) = rs.filter { it.truth.language.startsWith(lang) && conditionOf(it.truth).let { c -> cond.any { c.endsWith(it) } } }
        val ttfp = rs.filter { it.truth.noise == "clean" }.flatMap { it.firstPartialLatency }
        val fin = rs.filter { it.truth.noise == "clean" }.flatMap { it.finalLatency }
        val noisy = rs.filter { it.truth.noise != "clean" }
        appendLine(
            "| ${setup.label} | ${rs.size} | ${pct(errRate(sel("zh", "clean")))} | ${pct(errRate(sel("zh", "10 dB")))} | ${pct(errRate(sel("zh", "5 dB")))} | " +
                "${pct(errRate(sel("en", "clean")))} | ${pct(errRate(sel("en", "10 dB")))} | " +
                "${f2(percentile(ttfp, 50.0))} / ${f2(percentile(ttfp, 90.0))} | ${f2(percentile(fin, 50.0))} / ${f2(percentile(fin, 90.0))} | " +
                "${pct(speakerAcc(rs.filter { it.truth.noise == "clean" }))} | ${if (noisy.isEmpty()) "–" else pct(speakerAcc(noisy))} | " +
                "${f2(rs.sumOf { it.rtf * it.truth.duration } / rs.sumOf { it.truth.duration })} |",
        )
    }
    appendLine()
    appendLine("Latency columns use the clean files. Empty cells mean that configuration was only run on part of the set.")
    appendLine()

    val (mainSetup, main) = results.first()
    appendLine("## By condition — ${mainSetup.label}")
    appendLine()
    appendLine("| Condition | zh error | en WER | First partial p50 (s) | Final p50 (s) | Speaker accuracy |")
    appendLine("|---|---|---|---|---|---|")
    for (c in conditions) {
        val rs = main.filter { conditionOf(it.truth) == c }
        if (rs.isEmpty()) continue
        appendLine(
            "| $c | ${pct(errRate(rs.filter { it.truth.language.startsWith("zh") }))} | ${pct(errRate(rs.filter { it.truth.language.startsWith("en") }))} | " +
                "${f2(percentile(rs.flatMap { it.firstPartialLatency }, 50.0))} | ${f2(percentile(rs.flatMap { it.finalLatency }, 50.0))} | ${pct(speakerAcc(rs))} |",
        )
    }
    appendLine()
    appendLine("## Per file — ${mainSetup.label}")
    appendLine()
    appendLine("| File | Error | Speakers found / true | Speaker accuracy | Captions (speaker: text) |")
    appendLine("|---|---|---|---|---|")
    for (r in main) {
        val truthSpeakers = r.truth.segments.map { it.speaker }.distinct().size
        val caps = r.utterances.joinToString(" / ") { "${it.speaker?.let { s -> ('A' + s).toString() } ?: "?"}: ${it.text}" }.replace("|", "\\|")
        appendLine("| ${r.truth.id} | ${pct(r.errors.toDouble() / r.refTokens)} | ${r.speakers} / $truthSpeakers | ${pct(r.speakerAcc)} | $caps |")
    }
    appendLine()
    appendLine("## Model test wavs (no ground truth; decoded text only)")
    appendLine()
    for ((m, list) in extra) {
        if (list.isEmpty()) continue
        appendLine("**${m.name}**")
        appendLine()
        for ((name, text) in list) appendLine("- `$name`: $text")
        appendLine()
    }
}
