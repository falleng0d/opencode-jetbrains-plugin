package ai.opencode.plugin.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel

class OpenCodeConfigurable : Configurable {

    override fun getDisplayName() = "OpenCode"

    override fun createComponent(): JComponent =
        FormBuilder.createFormBuilder()
            .addComponent(JBLabel("OpenCode uses the bundled server binary. No configuration needed."))
            .addComponentFillVertically(JPanel(), 0)
            .panel

    override fun isModified() = false
    override fun apply() {}
}
