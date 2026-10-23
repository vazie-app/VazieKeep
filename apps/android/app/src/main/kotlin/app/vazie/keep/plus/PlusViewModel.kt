package app.vazie.keep.plus

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlusUiState(
    val loading: Boolean = true,
    val prices: KeepPrices? = null,
    val selectedPlan: String? = null,
    val awaitingPayment: Boolean = false,
    val checking: Boolean = false,
    val notSeen: Boolean = false,
    val activated: Boolean = false,
)

fun interface PlusStatus {
    suspend fun refresh(): Boolean
}

// Keep+ is bought on the site: the app shows what the backend sells and sends the person to vazie.app with the plan.
fun sitePlan(priceId: String): String = when (priceId) {
    "keep_plus_month" -> "month"
    "keep_plus_year" -> "year"
    else -> "lifetime"
}

class PlusViewModel(
    private val prices: KeepPricesApi,
    private val siteOrigin: String,
    private val status: PlusStatus,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) : ViewModel() {
    private val mutable = MutableStateFlow(PlusUiState())
    val state: StateFlow<PlusUiState> = mutable.asStateFlow()

    private val events = Channel<String>(Channel.BUFFERED)
    val openUrl: Flow<String> = events.receiveAsFlow()

    init {
        load()
    }

    fun load() {
        mutable.update { it.copy(loading = true) }
        viewModelScope.launch {
            val loaded = prices.fetch()
            mutable.update { current ->
                current.copy(
                    loading = false,
                    prices = loaded,
                    selectedPlan = current.selectedPlan ?: loaded?.plans?.firstOrNull { it.id == "keep_plus_year" }?.id ?: loaded?.plans?.firstOrNull()?.id,
                )
            }
        }
    }

    fun selectPlan(id: String) = mutable.update { it.copy(selectedPlan = id) }

    fun openSite() {
        val plan = mutable.value.selectedPlan ?: return
        mutable.update { it.copy(awaitingPayment = true) }
        events.trySend("$siteOrigin/keep/plus?plan=${sitePlan(plan)}")
    }

    // The person is back from the site: ask the backend for the right, a few times, because the payment service
    // tells Vazie a moment after the person sees "paid".
    fun onResumed() {
        if (!mutable.value.awaitingPayment || mutable.value.checking || mutable.value.activated) return
        mutable.update { it.copy(checking = true, notSeen = false) }
        viewModelScope.launch {
            var activated = false
            repeat(CHECKS) { attempt ->
                if (!activated) {
                    activated = status.refresh()
                    if (!activated && attempt < CHECKS - 1) pause(INTERVAL_MS)
                }
            }
            mutable.update { it.copy(checking = false, activated = activated, notSeen = !activated) }
        }
    }

    fun checkNow() {
        mutable.update { it.copy(checking = false) }
        onResumed()
    }

    private companion object {
        const val CHECKS = 6
        const val INTERVAL_MS = 5_000L
    }
}
