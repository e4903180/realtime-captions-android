package tw.captions.app.history

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import tw.captions.core.Transcript
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saved transcripts (ADR-029): one JSON file each in the app's private files folder
 * (filesDir/transcripts). Never uploaded; no audio is kept.
 */
class TranscriptStore(context: Context) {
    private val dir = File(context.filesDir, "transcripts").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("transcripts_enabled", true)
        set(v) = prefs.edit().putBoolean("transcripts_enabled", v).apply()

    /** Newest first. */
    fun loadAll(): List<Transcript> =
        (dir.listFiles { f -> f.extension == "json" } ?: emptyArray())
            .mapNotNull { runCatching { fromJson(JSONObject(it.readText())) }.getOrNull() }
            .sortedByDescending { it.startedMs }

    fun save(t: Transcript) {
        val tmp = File(dir, "${t.id}.json.tmp")
        tmp.writeText(toJson(t).toString())
        tmp.renameTo(File(dir, "${t.id}.json"))
    }

    fun delete(id: String) {
        File(dir, "$id.json").delete()
    }

    fun deleteAll() {
        dir.listFiles { f -> f.extension == "json" }?.forEach { it.delete() }
    }

    companion object {
        private val dateFormat get() = SimpleDateFormat("M月d日 EEE HH:mm", Locale.TAIWAN)

        fun displayTitle(t: Transcript): String = t.title?.takeIf { it.isNotBlank() } ?: dateFormat.format(Date(t.startedMs))

        fun subtitle(t: Transcript): String = "${dateFormat.format(Date(t.startedMs))}，${Transcript.minutes(t.durationSec)}，${t.engine}"

        fun plainText(t: Transcript): String {
            val clock = SimpleDateFormat("HH:mm:ss", Locale.TAIWAN)
            return t.plainText("${displayTitle(t)}\n${subtitle(t)}") { clock.format(Date(it)) }
        }

        /** File name without characters other systems reject. */
        fun fileStem(t: Transcript): String {
            val base = "逐字稿_" + SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date(t.startedMs)) + (t.title?.let { "_$it" } ?: "")
            return base.replace(Regex("[/\\\\:?%*|\"<>\\n]"), "-")
        }

        private fun toJson(t: Transcript) = JSONObject().apply {
            put("id", t.id)
            t.title?.let { put("title", it) }
            put("started", t.startedMs)
            put("ended", t.endedMs)
            put("engine", t.engine)
            put("lines", JSONArray().apply {
                t.lines.forEach { l ->
                    put(JSONObject().apply {
                        put("id", l.id)
                        l.speaker?.let { put("speaker", it) }
                        put("text", l.text)
                        put("start", l.start)
                        put("end", l.end)
                    })
                }
            })
        }

        private fun fromJson(o: JSONObject): Transcript {
            val arr = o.getJSONArray("lines")
            return Transcript(
                id = o.getString("id"),
                title = o.optString("title").takeIf { o.has("title") && it.isNotEmpty() },
                startedMs = o.getLong("started"),
                endedMs = o.getLong("ended"),
                engine = o.optString("engine"),
                lines = (0 until arr.length()).map { i ->
                    val l = arr.getJSONObject(i)
                    Transcript.Line(
                        id = l.getLong("id"),
                        speaker = l.optString("speaker").takeIf { l.has("speaker") && it.isNotEmpty() },
                        text = l.getString("text"),
                        start = l.getDouble("start"),
                        end = l.getDouble("end"),
                    )
                },
            )
        }
    }
}
