package ai.opencode.plugin.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(
    name = "OpenCodeSettings",
    storages = [Storage("opencode.xml")],
)
@Service(Service.Level.APP)
class OpenCodeSettings : PersistentStateComponent<OpenCodeSettings.State> {

    data class State(
        var executablePath: String = "",
    )

    private var state = State()

    var executablePath: String
        get()  = state.executablePath
        set(v) { state.executablePath = v }

    override fun getState(): State = state

    override fun loadState(s: State) {
        state = s
    }

    companion object {
        val instance: OpenCodeSettings
            get() = ApplicationManager.getApplication().getService(OpenCodeSettings::class.java)
    }
}
