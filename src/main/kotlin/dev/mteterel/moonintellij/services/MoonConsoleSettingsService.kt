package dev.mteterel.moonintellij.services

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros

@Service(Service.Level.PROJECT)
@State(
    name = "MoonConsoleSettings",
    storages = [Storage(StoragePathMacros.WORKSPACE_FILE)],
)
class MoonConsoleSettingsService : PersistentStateComponent<MoonConsoleSettingsService.State> {
    data class State(
        var showLastRunSection: Boolean = true,
        var preferProjectMetadata: Boolean = false,
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    var showLastRunSection: Boolean
        get() = state.showLastRunSection
        set(value) {
            state.showLastRunSection = value
        }

    var preferProjectMetadata: Boolean
        get() = state.preferProjectMetadata
        set(value) {
            state.preferProjectMetadata = value
        }
}
