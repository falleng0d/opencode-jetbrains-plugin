package ai.opencode.plugin.toolwindow

import ai.opencode.plugin.bridge.BrowserBridge
import ai.opencode.plugin.bridge.IdeContextService
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp
import javax.swing.JLabel

class OpenCodeToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val factory = ContentFactory.getInstance()

        if (!JBCefApp.isSupported()) {
            val label = JLabel("JCEF is not available in this IDE environment.")
            val content = factory.createContent(label, "", false)
            toolWindow.contentManager.addContent(content)
            return
        }

        val bridge = project.service<BrowserBridge>()

        // Ensure context service is initialised (subscribes to editor events)
        project.service<IdeContextService>()

        // Load the webapp
        bridge.load()

        val content = factory.createContent(bridge.browser.component, "", false)
        content.setDisposer(bridge)
        toolWindow.contentManager.addContent(content)
    }
}
