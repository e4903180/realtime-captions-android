package tw.captions.core

import kotlin.test.Test
import kotlin.test.assertEquals

class CloudUsageTest {
    private val r = CloudUsage.Range
    private val paidPrices = CloudUsage.Prices(azurePerHour = 1.0, azureFreeTier = false)

    @Test
    fun sumsByDayMonthAndAll() {
        val u = CloudUsage().add("2026-10-31", 600.0).add("2026-11-01", 300.0).add("2026-11-27", 120.0).add("2026-11-27", 60.0)
        assertEquals(180.0, u.seconds(r.TODAY, "2026-11-27"))
        assertEquals(480.0, u.seconds(r.MONTH, "2026-11-27"))
        assertEquals(1080.0, u.seconds(r.ALL, "2026-11-27"))
    }

    @Test
    fun ignoresZeroAndNegative() {
        assertEquals(CloudUsage(), CloudUsage().add("2026-11-27", 0.0).add("2026-11-27", -5.0))
    }

    @Test
    fun paidPlanCostsByHour() {
        val u = CloudUsage().add("2026-11-27", 7200.0)
        assertEquals(2.0, u.azureCost(r.TODAY, "2026-11-27", paidPrices), 1e-9)
    }

    @Test
    fun freeTierCoversFirstFiveHoursEachMonth() {
        val free = CloudUsage.Prices(azurePerHour = 1.0, azureFreeTier = true)
        val u = CloudUsage()
            .add("2026-10-10", 6 * 3600.0) // October: 1 paid hour
            .add("2026-11-01", 4 * 3600.0) // November: 4 h free so far
            .add("2026-11-27", 2 * 3600.0) // today: 1 h free + 1 h paid
        assertEquals(1.0, u.azureCost(r.TODAY, "2026-11-27", free), 1e-9)
        assertEquals(1.0, u.azureCost(r.MONTH, "2026-11-27", free), 1e-9)
        assertEquals(2.0, u.azureCost(r.ALL, "2026-11-27", free), 1e-9)
        assertEquals(1.2, u.freeTierUsed("2026-11-27"), 1e-9)
    }

    @Test
    fun keepsAboutAYear() {
        var u = CloudUsage()
        for (i in 0 until 410) u = u.add("d" + i.toString().padStart(4, '0'), 1.0)
        assertEquals(400, u.azureSecondsByDay.size)
        assertEquals(false, u.azureSecondsByDay.containsKey("d0000"))
    }
}
