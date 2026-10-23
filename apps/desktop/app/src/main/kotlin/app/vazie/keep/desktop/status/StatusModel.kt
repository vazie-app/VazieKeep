package app.vazie.keep.desktop.status

import app.vazie.keep.core.KeepCore
import app.vazie.keep.desktop.platform.DesktopPlatform

sealed interface CoreState {
    data class Connected(val version: String) : CoreState

    data object Unavailable : CoreState
}

data class StatusUiState(val platform: String, val core: CoreState)

class StatusModel(private val core: KeepCore, private val platform: DesktopPlatform) {
    fun load(): StatusUiState = StatusUiState(
        platform = platform.displayName,
        core = runCatching { core.info() }.fold({ CoreState.Connected(it.version) }, { CoreState.Unavailable }),
    )
}
