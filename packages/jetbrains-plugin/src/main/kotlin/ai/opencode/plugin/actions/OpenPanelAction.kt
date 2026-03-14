package ai.opencode.plugin.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.ToolWindowManager

class OpenPanelAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val tw = ToolWindowManager.getInstance(project).getToolWindow("OpenCode") ?: return
        if (tw.isVisible) {
            tw.hide()
        } else {
            tw.activate(null)
        }
    }
}
