package app.vazie.keep.review

enum class InstallStore { GooglePlay, RuStore }

data class ReviewState(
    val launches: Int = 0,
    val nextAskAt: Int = FIRST_ASK_AT_LAUNCH,
    val asks: Int = 0,
    val finished: Boolean = false,
) {
    fun afterLaunch() = copy(launches = launches + 1)

    fun isDue(store: InstallStore?) = store != null && !finished && launches >= nextAskAt

    fun answered() = copy(finished = true)

    fun postponed(): ReviewState {
        val used = asks + 1
        return if (used >= MAX_ASKS) copy(asks = used, finished = true) else copy(asks = used, nextAskAt = launches + LAUNCHES_BETWEEN_ASKS)
    }

    companion object {
        const val FIRST_ASK_AT_LAUNCH = 3
        const val LAUNCHES_BETWEEN_ASKS = 7
        const val MAX_ASKS = 2
    }
}
