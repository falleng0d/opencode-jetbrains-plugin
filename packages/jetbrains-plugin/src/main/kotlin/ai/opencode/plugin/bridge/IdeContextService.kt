package ai.opencode.plugin.bridge

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.CaretListener
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.util.ui.ColorUtil
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

@Service(Service.Level.PROJECT)
class IdeContextService(private val project: Project) : Disposable {

    private val executor = Executors.newSingleThreadScheduledExecutor()
    private var caretTask: ScheduledFuture<*>? = null

    init {
        subscribeToFileEditorEvents()
        subscribeToCaretEvents()
        subscribeToThemeEvents()
    }

    // ── public ────────────────────────────────────────────────────────────────

    fun sendCurrentState() {
        sendTheme()
        sendTabs()
        sendActiveEditor()
        sendProjectInfo()
    }

    // ── subscriptions ─────────────────────────────────────────────────────────

    private fun subscribeToFileEditorEvents() {
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    sendTabs()
                    sendActiveEditor()
                }
                override fun fileOpened(src: FileEditorManager, file: VirtualFile) = sendTabs()
                override fun fileClosed(src: FileEditorManager, file: VirtualFile) = sendTabs()
            }
        )
    }

    private fun subscribeToCaretEvents() {
        EditorFactory.getInstance().addEditorFactoryListener(
            object : EditorFactoryListener {
                override fun editorCreated(event: EditorFactoryEvent) {
                    val editor = event.editor
                    if (editor.project != project) return
                    editor.caretModel.addCaretListener(object : CaretListener {
                        override fun caretPositionChanged(e: CaretEvent) {
                            scheduleCaretUpdate(editor)
                        }
                    })
                }
            },
            this,
        )
    }

    private fun subscribeToThemeEvents() {
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            EditorColorsManager.TOPIC,
            EditorColorsListener { sendTheme() }
        )
    }

    // ── senders ───────────────────────────────────────────────────────────────

    private fun sendActiveEditor() {
        val mgr    = FileEditorManager.getInstance(project)
        val editor = (mgr.selectedEditor as? TextEditor)?.editor ?: return
        val file   = mgr.selectedFiles.firstOrNull() ?: return
        ApplicationManager.getApplication().runReadAction {
            dispatchEditorState(file, editor)
        }
    }

    private fun scheduleCaretUpdate(editor: com.intellij.openapi.editor.Editor) {
        caretTask?.cancel(false)
        caretTask = executor.schedule({
            ApplicationManager.getApplication().runReadAction {
                val file = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
                if (file != null && editor.project == project) {
                    dispatchEditorState(file, editor)
                }
            }
        }, 500, TimeUnit.MILLISECONDS)
    }

    private fun dispatchEditorState(file: VirtualFile, editor: com.intellij.openapi.editor.Editor) {
        val caret     = editor.caretModel.primaryCaret
        val pos       = caret.logicalPosition
        val line      = pos.line + 1
        val col       = pos.column + 1
        val doc       = editor.document
        val lineCount = doc.lineCount
        val surrounding = getSurrounding(editor, pos.line, radius = 50)
        val lang      = file.fileType.name

        project.service<BrowserBridge>().sendActiveEditor(
            path            = file.path,
            language        = lang,
            line            = line,
            col             = col,
            lineCount       = lineCount,
            surroundingCode = surrounding,
        )
    }

    private fun getSurrounding(
        editor: com.intellij.openapi.editor.Editor,
        caretLine: Int,
        radius: Int,
    ): String {
        val doc   = editor.document
        val start = maxOf(0, caretLine - radius)
        val end   = minOf(doc.lineCount - 1, caretLine + radius)
        return buildString {
            for (l in start..end) {
                val s = doc.getLineStartOffset(l)
                val e = doc.getLineEndOffset(l)
                appendLine(doc.getText(TextRange(s, e)))
            }
        }
    }

    private fun sendTabs() {
        val mgr    = FileEditorManager.getInstance(project)
        val active = mgr.selectedFiles.firstOrNull()
        val tabs   = mgr.openFiles.map { f ->
            BrowserBridge.TabPayload(
                path     = f.path,
                name     = f.name,
                active   = f == active,
                modified = false,
            )
        }
        project.service<BrowserBridge>().sendTabs(tabs)
    }

    private fun sendTheme() {
        val dark   = !JBColor.isBright()
        val bg     = ColorUtil.toHex(JBColor.background())
        val fg     = ColorUtil.toHex(JBColor.foreground())
        val accent = ColorUtil.toHex(JBColor.namedColor("Link.activeForeground", JBColor.BLUE))
        val border = ColorUtil.toHex(JBColor.namedColor("Component.borderColor", JBColor.GRAY))
        project.service<BrowserBridge>().sendTheme(dark, "#$bg", "#$fg", "#$accent", "#$border")
    }

    private fun sendProjectInfo() {
        val dir    = project.basePath ?: return
        val name   = project.name
        val branch = runCatching { readGitBranch(dir) }.getOrNull()
        project.service<BrowserBridge>().sendProjectInfo(dir, name, branch)
    }

    private fun readGitBranch(dir: String): String? {
        val head = java.io.File("$dir/.git/HEAD")
        if (!head.exists()) return null
        val raw = head.readText().trim()
        return if (raw.startsWith("ref: refs/heads/")) raw.removePrefix("ref: refs/heads/") else null
    }

    override fun dispose() {
        executor.shutdownNow()
    }
}
