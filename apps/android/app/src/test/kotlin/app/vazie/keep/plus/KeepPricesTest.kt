package app.vazie.keep.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class KeepPricesTest {
    private val body = """
        {"currency":"RUB","plans":[
          {"id":"keep_plus_month","kind":"subscription","amountKopecks":14900,"regularAmountKopecks":null,"earlyAccess":null},
          {"id":"keep_plus_lifetime","kind":"lifetime","amountKopecks":199000,"regularAmountKopecks":349000,"earlyAccess":{"limit":555,"remaining":540}}],
         "checkout":{"available":false,"providers":["tochka"]}}
    """.trimIndent()

    @Test
    fun `prices are read as the backend sent them`() {
        val prices = parsePrices(body)!!
        assertEquals(2, prices.plans.size)
        assertEquals(14_900L, prices.plans[0].amountKopecks)
        assertNull(prices.plans[0].earlyRemaining)
        assertEquals(540, prices.plans[1].earlyRemaining)
        assertEquals(349_000L, prices.plans[1].regularKopecks)
        assertFalse(prices.checkoutAvailable)
        assertEquals(listOf("tochka"), prices.providers)
    }

    @Test
    fun `a broken answer is not a price`() {
        assertNull(parsePrices("{}"))
        assertNull(parsePrices("nope"))
        assertNotNull(parsePrices(body))
    }

    @Test
    fun `rubles are grouped and whole amounts have no kopecks`() {
        assertEquals("149 ₽", formatRubles(14_900))
        assertEquals("1 490 ₽", formatRubles(149_000))
        assertEquals("3 490 ₽", formatRubles(349_000))
        assertEquals("149,50 ₽", formatRubles(14_950))
    }
}
