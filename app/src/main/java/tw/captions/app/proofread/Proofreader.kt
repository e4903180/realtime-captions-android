package tw.captions.app.proofread

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import org.json.JSONArray
import org.json.JSONObject
import tw.captions.core.ProofreadLine
import tw.captions.core.Proofreading
import java.time.Duration

/**
 * AI proof-reading of finished lines with Claude (ADR-026, same as iPhone). Off by default; needs
 * the user's own Anthropic API key. Only caption text is sent (never audio).
 */
object Proofreader {
    enum class Model(val id: String, val label: String, val inputPerMTok: Double, val outputPerMTok: Double) {
        OPUS("claude-opus-5-5", "Claude Opus 5.5（最準）", 4.0, 20.0),
        HAIKU("claude-haiku-5-5", "Claude Haiku 5.5（快、便宜）", 0.10, 0.50),
    }

    /**
     * [permanent]: retrying will not help (wrong key, no permission, no credit), so proof-reading
     * pauses until the key changes. Anything else (timeout, network, busy) is retried.
     */
    class Failure(message: String, val permanent: Boolean = false) : Exception(message)

    /** Corrected text by line id (only lines the model changed), and the tokens the request used. */
    class Result(val fixed: Map<Long, String>, val inputTokens: Long, val outputTokens: Long)

    private fun client(key: String) = AnthropicOkHttpClient.builder().apiKey(key).timeout(Duration.ofSeconds(60)).build()

    private fun toPlain(v: Any?): Any? = when (v) {
        is JSONObject -> v.keys().asSequence().associateWith { toPlain(v.get(it)) }
        is JSONArray -> List(v.length()) { toPlain(v.get(it)) }
        JSONObject.NULL -> null
        else -> v
    }

    /** Blocking; call from a background thread. */
    fun proofread(key: String, model: Model, context: List<String>, lines: List<ProofreadLine>): Result {
        val schema = JsonOutputFormat.Schema.builder().apply {
            @Suppress("UNCHECKED_CAST")
            (toPlain(JSONObject(Proofreading.JSON_SCHEMA)) as Map<String, Any?>).forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(v)) }
        }.build()
        val params = MessageCreateParams.builder()
            .model(model.id)
            .maxTokens(4000L)
            .system(Proofreading.SYSTEM_PROMPT)
            .outputConfig(
                OutputConfig.builder()
                    .effort(OutputConfig.Effort.LOW)
                    .format(JsonOutputFormat.builder().schema(schema).build())
                    .build(),
            )
            .addUserMessage(Proofreading.userMessage(context, lines))
            .apply {
                if (model == Model.OPUS) {
                    // If a safety check declines the request, the API retries it on a fallback model.
                    putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                    putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                }
            }
            .build()
        val response = try {
            client(key).messages().create(params)
        } catch (e: UnauthorizedException) {
            throw Failure("金鑰不對，請重新貼上", permanent = true)
        } catch (e: PermissionDeniedException) {
            throw Failure("這組金鑰沒有權限使用這個模型", permanent = true)
        } catch (e: RateLimitException) {
            throw Failure("用量太多，稍後再試")
        } catch (e: AnthropicServiceException) {
            val code = e.statusCode()
            val credit = code == 402 || (code == 400 && e.message?.contains("credit") == true)
            throw Failure(
                when {
                    credit -> "帳號額度不足，請到 Anthropic 主控台儲值"
                    code >= 500 -> "Anthropic 服務忙碌"
                    else -> "校稿服務錯誤（$code）"
                },
                permanent = credit,
            )
        } catch (e: AnthropicIoException) {
            throw Failure("連不上 Anthropic")
        }
        val input = response.usage().inputTokens()
        val output = response.usage().outputTokens()
        // A declined request has no usable answer: keep the captions as they are.
        if (response.stopReason().map { it.toString() }.orElse("") == "refusal") return Result(emptyMap(), input, output)
        val text = buildString { response.content().forEach { b -> b.text().ifPresent { append(it.text()) } } }
        val fixed = mutableMapOf<Long, String>()
        runCatching {
            val items = JSONObject(text).getJSONArray("lines")
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val id = item.getLong("id")
                val corrected = item.getString("text")
                val original = lines.firstOrNull { it.id == id }?.text ?: continue
                if (Proofreading.accept(original, corrected)) fixed[id] = corrected.trim()
            }
        }
        return Result(fixed, input, output)
    }

    /** Null when the key works; otherwise what is wrong, in words. Listing models costs nothing. */
    fun testKey(key: String): String? = try {
        client(key).models().list()
        null
    } catch (e: UnauthorizedException) {
        "金鑰不對，請重新貼上"
    } catch (e: AnthropicServiceException) {
        "Anthropic 回應 ${e.statusCode()}"
    } catch (e: Exception) {
        "連不上 Anthropic，請確認網路"
    }
}
