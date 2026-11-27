package tw.captions.core

import kotlin.math.max

/**
 * How much this phone has used paid cloud recognition, by day ("yyyy-MM-dd" to audio seconds sent).
 * Azure bills by audio time; this is an estimate for this phone only (ADR-028). Pure logic so it can
 * be tested; the app stores the map and passes today's date in.
 */
data class CloudUsage(val azureSecondsByDay: Map<String, Double> = emptyMap()) {

    enum class Range(val label: String) { TODAY("今天"), MONTH("本月"), ALL("全部") }

    /** Editable prices, because they change and depend on the plan. */
    data class Prices(
        /** Azure Speech real-time transcription with speaker labels (1.00 + 0.30), US$ per audio hour. */
        val azurePerHour: Double = 1.3,
        /** On the free tier (F0) the first [AZURE_FREE_HOURS] each month cost nothing. */
        val azureFreeTier: Boolean = true,
        /** Monthly spending limit in US dollars; 0 means no limit. */
        val monthlyLimit: Double = 0.0,
    )

    fun add(day: String, seconds: Double): CloudUsage {
        if (seconds <= 0) return this
        val next = azureSecondsByDay.toMutableMap()
        next[day] = (next[day] ?: 0.0) + seconds
        // Keep a little over a year.
        while (next.size > MAX_DAYS) next.remove(next.keys.min())
        return CloudUsage(next)
    }

    fun seconds(range: Range, today: String): Double = when (range) {
        Range.TODAY -> azureSecondsByDay[today] ?: 0.0
        Range.MONTH -> azureSecondsByDay.filterKeys { it.startsWith(today.take(7)) }.values.sum()
        Range.ALL -> azureSecondsByDay.values.sum()
    }

    /** True when a monthly limit is set and this month's estimated cost has reached it. */
    fun overLimit(today: String, prices: Prices): Boolean =
        prices.monthlyLimit > 0 && azureCost(Range.MONTH, today, prices) >= prices.monthlyLimit

    /** Share of this month's free hours used, 0..1+ (only meaningful on the free tier). */
    fun freeTierUsed(today: String): Double = seconds(Range.MONTH, today) / 3600 / AZURE_FREE_HOURS

    /** Estimated Azure cost in US dollars; free-tier hours are counted per month. */
    fun azureCost(range: Range, today: String, prices: Prices): Double {
        fun paid(seconds: Double): Double {
            val hours = seconds / 3600
            return (if (prices.azureFreeTier) max(0.0, hours - AZURE_FREE_HOURS) else max(0.0, hours)) * prices.azurePerHour
        }
        val byMonth = azureSecondsByDay.entries.groupBy({ it.key.take(7) }, { it.value }).mapValues { it.value.sum() }
        val month = byMonth[today.take(7)] ?: 0.0
        return when (range) {
            Range.ALL -> byMonth.values.sumOf { paid(it) }
            Range.MONTH -> paid(month)
            Range.TODAY -> paid(month) - paid(month - (azureSecondsByDay[today] ?: 0.0))
        }
    }

    companion object {
        const val AZURE_FREE_HOURS = 5.0
        private const val MAX_DAYS = 400
    }
}
