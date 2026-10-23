package app.vazie.keep.plus

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

data class PlanPrice(
    val id: String,
    val isLifetime: Boolean,
    val amountKopecks: Long,
    val regularKopecks: Long?,
    val earlyRemaining: Int?,
)

data class KeepPrices(val plans: List<PlanPrice>, val checkoutAvailable: Boolean, val providers: List<String>)

fun parsePrices(text: String): KeepPrices? = try {
    val root = JSONObject(text)
    val plans = root.getJSONArray("plans")
    KeepPrices(
        plans = (0 until plans.length()).map {
            val plan = plans.getJSONObject(it)
            PlanPrice(
                id = plan.getString("id"),
                isLifetime = plan.getString("kind") == "lifetime",
                amountKopecks = plan.getLong("amountKopecks"),
                regularKopecks = if (plan.isNull("regularAmountKopecks")) null else plan.getLong("regularAmountKopecks"),
                earlyRemaining = plan.optJSONObject("earlyAccess")?.getInt("remaining"),
            )
        },
        checkoutAvailable = root.getJSONObject("checkout").getBoolean("available"),
        providers = root.getJSONObject("checkout").getJSONArray("providers").let { list -> (0 until list.length()).map(list::getString) },
    )
} catch (_: JSONException) {
    null
}

fun interface KeepPricesApi {
    suspend fun fetch(): KeepPrices?
}

class HttpKeepPricesApi(private val keepOrigin: String, private val io: CoroutineDispatcher = Dispatchers.IO) : KeepPricesApi {
    override suspend fun fetch(): KeepPrices? = withContext(io) {
        try {
            val connection = URL("$keepOrigin/v1/prices").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept", "application/json")
                if (connection.responseCode != 200) null else parsePrices(connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
            } finally {
                connection.disconnect()
            }
        } catch (_: IOException) {
            null
        }
    }

    private companion object {
        const val TIMEOUT_MS = 15_000
    }
}

fun formatRubles(kopecks: Long): String {
    val rubles = kopecks / 100
    val rest = kopecks % 100
    val grouped = rubles.toString().reversed().chunked(3).joinToString(" ").reversed()
    return if (rest == 0L) "$grouped ₽" else "$grouped,${rest.toString().padStart(2, '0')} ₽"
}
