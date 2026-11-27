package tw.captions.app.settings

import android.content.Context
import org.json.JSONObject
import tw.captions.core.CloudUsage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Keeps the cloud usage log and prices on this phone only (ADR-028). */
class UsageStore(context: Context) {
    private val prefs = context.getSharedPreferences("cloud_usage", Context.MODE_PRIVATE)

    fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    fun load(): CloudUsage = runCatching {
        val json = JSONObject(prefs.getString(KEY_DAYS, "{}")!!)
        CloudUsage(json.keys().asSequence().associateWith { json.getDouble(it) })
    }.getOrDefault(CloudUsage())

    fun save(usage: CloudUsage) {
        prefs.edit().putString(KEY_DAYS, JSONObject(usage.azureSecondsByDay as Map<*, *>).toString()).apply()
    }

    fun loadPrices() = CloudUsage.Prices(
        azurePerHour = prefs.getFloat(KEY_PRICE, 1.3f).toDouble(),
        azureFreeTier = prefs.getBoolean(KEY_FREE, true),
        monthlyLimit = prefs.getFloat(KEY_LIMIT, 0f).toDouble(),
    )

    fun savePrices(p: CloudUsage.Prices) {
        prefs.edit().putFloat(KEY_PRICE, p.azurePerHour.toFloat()).putBoolean(KEY_FREE, p.azureFreeTier).putFloat(KEY_LIMIT, p.monthlyLimit.toFloat()).apply()
    }

    fun reset() = prefs.edit().remove(KEY_DAYS).remove(KEY_CLAUDE).apply()

    /** AI proof-reading this month: requests and estimated US dollars (from token counts). */
    fun claudeThisMonth(): Pair<Int, Double> = runCatching {
        val o = JSONObject(prefs.getString(KEY_CLAUDE, "{}")!!).optJSONObject(today().substring(0, 7)) ?: return 0 to 0.0
        o.optInt("requests") to o.optDouble("cost", 0.0)
    }.getOrDefault(0 to 0.0)

    fun addClaude(cost: Double) {
        val month = today().substring(0, 7)
        val all = runCatching { JSONObject(prefs.getString(KEY_CLAUDE, "{}")!!) }.getOrDefault(JSONObject())
        val o = all.optJSONObject(month) ?: JSONObject()
        o.put("requests", o.optInt("requests") + 1).put("cost", o.optDouble("cost", 0.0) + cost)
        // Only this month is shown; older months are dropped.
        prefs.edit().putString(KEY_CLAUDE, JSONObject().put(month, o).toString()).apply()
    }

    private companion object {
        const val KEY_DAYS = "azure_seconds_by_day"
        const val KEY_PRICE = "azure_per_hour"
        const val KEY_FREE = "azure_free_tier"
        const val KEY_LIMIT = "monthly_limit"
        const val KEY_CLAUDE = "claude_by_month"
    }
}
