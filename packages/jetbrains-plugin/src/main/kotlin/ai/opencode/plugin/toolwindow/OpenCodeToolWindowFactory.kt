package ai.opencode.plugin.toolwindow

import ai.opencode.plugin.bridge.BrowserBridge
import ai.opencode.plugin.bridge.IdeContextService
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp
import com.intellij.util.ui.FormBuilder
import java.io.File
import javax.swing.JComponent
import javax.swing.JPanel

private val LOG = logger<OpenCodeToolWindowFactory>()

class OpenCodeToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val factory = ContentFactory.getInstance()

        if (!JBCefApp.isSupported()) {
            val label = JBLabel("JCEF is not available in this IDE environment.")
            val content = factory.createContent(label, "", false)
            toolWindow.contentManager.addContent(content)
            return
        }

        val bridge = project.service<BrowserBridge>()

        // Ensure context service is initialised (subscribes to editor events)
        project.service<IdeContextService>()

        val content = runCatching {
            bridge.load()
            factory.createContent(bridge.browser.component, "", false).also {
                it.setDisposer(bridge)
            }
        }.getOrElse { err ->
            LOG.warn("Failed to initialize OpenCode toolwindow", err)
            bridge.dispose()
            factory.createContent(error(err), "", false)
        }

        toolWindow.contentManager.addContent(content)
    }

    private fun error(err: Throwable): JComponent {
        val path = File(System.getProperty("user.home"), ".opencode-plugin/bin").absolutePath
        val msg = err.message ?: err.javaClass.simpleName

        return FormBuilder.createFormBuilder()
            .addComponent(JBLabel("<html><b>OpenCode failed to start.</b></html>"))
            .addComponent(JBLabel("<html>$msg</html>"))
            .addComponent(JBLabel("<html>Expected extracted binaries in: $path</html>"))
            .addComponent(JBLabel("<html>Try reinstalling the plugin, then reopen the toolwindow. If the issue persists, check the IDE log for OpenCode errors.</html>"))
            .addComponentFillVertically(JPanel(), 0)
            .panel
    }
}
