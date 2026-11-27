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
        azurePerHour = prefs.getFloat(KEY_PRICE, 1.0f).toDouble(),
        azureFreeTier = prefs.getBoolean(KEY_FREE, true),
        monthlyLimit = prefs.getFloat(KEY_LIMIT, 0f).toDouble(),
    )

    fun savePrices(p: CloudUsage.Prices) {
        prefs.edit().putFloat(KEY_PRICE, p.azurePerHour.toFloat()).putBoolean(KEY_FREE, p.azureFreeTier).putFloat(KEY_LIMIT, p.monthlyLimit.toFloat()).apply()
    }

    fun reset() = prefs.edit().remove(KEY_DAYS).apply()

    private companion object {
        const val KEY_DAYS = "azure_seconds_by_day"
        const val KEY_PRICE = "azure_per_hour"
        const val KEY_FREE = "azure_free_tier"
        const val KEY_LIMIT = "monthly_limit"
    }
}
