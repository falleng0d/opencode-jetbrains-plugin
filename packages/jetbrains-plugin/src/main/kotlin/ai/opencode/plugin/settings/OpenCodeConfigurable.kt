package ai.opencode.plugin.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel

class OpenCodeConfigurable : Configurable {

    private var field: JBTextField? = null

    override fun getDisplayName() = "OpenCode"

    override fun createComponent(): JComponent {
        val tf = JBTextField(OpenCodeSettings.instance.executablePath)
        field = tf
        return FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Executable path:"), tf, true)
            .addComponentFillVertically(JPanel(), 0)
            .panel
    }

    override fun isModified() = field?.text != OpenCodeSettings.instance.executablePath

    override fun apply() {
        OpenCodeSettings.instance.executablePath = field?.text ?: ""
    }

    override fun reset() {
        field?.text = OpenCodeSettings.instance.executablePath
    }
}
