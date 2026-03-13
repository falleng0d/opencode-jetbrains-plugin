package ai.opencode.plugin.actions

import ai.opencode.plugin.bridge.BrowserBridge
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.wm.ToolWindowManager

class AddToContextAction : AnAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = editor?.selectionModel?.hasSelection() == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor  = e.getData(CommonDataKeys.EDITOR) ?: return
        val file    = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val sel     = editor.selectionModel

        if (!sel.hasSelection()) return

        val startLine = editor.offsetToLogicalPosition(sel.selectionStart).line + 1
        val endLine   = editor.offsetToLogicalPosition(sel.selectionEnd).line + 1
        val code      = sel.selectedText ?: return
        val lang      = file.fileType.name.lowercase()

        project.service<BrowserBridge>().sendSelection(
            path      = file.path,
            startLine = startLine,
            endLine   = endLine,
            code      = code,
            language  = lang,
        )

        // Focus the tool window so the user sees the pill
        ToolWindowManager.getInstance(project).getToolWindow("OpenCode")?.activate(null)
    }
}
