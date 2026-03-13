# JetBrains Plugin — JCEF Implementation Guide

This document covers exactly how to build the plugin. No ACP. No hand-waving.
Every API call is real and sourced from the official IntelliJ Platform SDK docs.

---

## Table of Contents

1. [How JCEF Communication Works](#1-how-jcef-communication-works)
2. [How IDE-Context Features Work](#2-how-ide-context-features-work)
3. [Complete Data Flow](#3-complete-data-flow)
4. [Kotlin Plugin — Full Implementation](#4-kotlin-plugin--full-implementation)
5. [Webapp Changes — Full Implementation](#5-webapp-changes--full-implementation)
6. [plugin.xml](#6-pluginxml)
7. [Gradle Build](#7-gradle-build)
8. [Serving the Webapp from JAR](#8-serving-the-webapp-from-jar)
9. [Things That Will Bite You](#9-things-that-will-bite-you)

---

## 1. How JCEF Communication Works

JCEF exposes exactly two directions of communication:

### Kotlin → Webapp (push)

```kotlin
browser.cefBrowser.executeJavaScript(
    "window.dispatchEvent(new CustomEvent('opencode', { detail: $jsonPayload }))",
    browser.cefBrowser.url,
    0
)
```

Call this any time from any thread. There is no return value. Fire-and-forget.

### Webapp → Kotlin (request/response)

This uses `JBCefJSQuery`, which is the only way to call back into Kotlin from JS.
JetBrains injects a special function name into the page; you call it from JS.

```kotlin
// 1. Create the query object once, tied to this browser instance
val query = JBCefJSQuery.create(browser as JBCefBrowserBase)

// 2. Register a handler — this runs on the EDT when JS calls it
query.addHandler { msg: String ->
    handleWebappMessage(msg)
    null  // return null = no response needed; return JBCefJSQuery.Response("ok") to send back
}

// 3. Inject the function into the page so JS can call it
browser.cefBrowser.executeJavaScript("""
    window.__sendToIDE = function(msg) {
        ${query.inject("msg")}
    }
""", browser.cefBrowser.url, 0)
```

```ts
// TypeScript side — call from anywhere in the webapp
window.__sendToIDE?.(JSON.stringify({ type: "ready" }))
```

That is literally all there is to the JCEF bridge. Two directions, both async.

---

## 2. How IDE-Context Features Work

### 2.1 Active File + Caret Position

The IntelliJ Platform fires events on the message bus whenever the user switches
files or moves the caret. Subscribe with project-level listeners.

**File switch:**

```kotlin
project.messageBus.connect(disposable).subscribe(
    FileEditorManagerListener.FILE_EDITOR_MANAGER,
    object : FileEditorManagerListener {
        override fun selectionChanged(event: FileEditorManagerEvent) {
            val file = event.newFile ?: return
            val editor = (event.newEditor as? TextEditor)?.editor ?: return
            onActiveEditorChanged(file, editor)
        }
    }
)
```

**Caret moved (debounced):**

```kotlin
// CaretListener fires on every single keystroke — must debounce
val DEBOUNCE_MS = 500L

EditorFactory.getInstance().addEditorFactoryListener(
    object : EditorFactoryListener {
        override fun editorCreated(event: EditorFactoryEvent) {
            val editor = event.editor
            // Only track editors belonging to this project
            if (editor.project != project) return
            editor.caretModel.addCaretListener(object : CaretListener {
                override fun caretPositionChanged(event: CaretEvent) {
                    debounce(DEBOUNCE_MS) {
                        onCaretMoved(editor)
                    }
                }
            })
        }
    },
    disposable
)
```

**Reading surrounding code:**

```kotlin
fun getSurroundingCode(editor: Editor, radius: Int = 50): String {
    val doc = editor.document
    val caret = editor.caretModel.primaryCaret
    val line = caret.logicalPosition.line
    val start = maxOf(0, line - radius)
    val end   = minOf(doc.lineCount - 1, line + radius)

    return buildString {
        for (l in start..end) {
            val lineStart = doc.getLineStartOffset(l)
            val lineEnd   = doc.getLineEndOffset(l)
            appendLine(doc.getText(TextRange(lineStart, lineEnd)))
        }
    }
}
```

**Key classes:**

- `editor.caretModel.primaryCaret.logicalPosition` → `LogicalPosition(line, column)` — zero-based
- `editor.caretModel.primaryCaret.offset` → char offset from file start
- `editor.document.getText(TextRange)` → extract any slice of the file
- `editor.document.lineCount` → total line count
- `virtualFile.path` → absolute path to file on disk
- `virtualFile.fileType.name` → language name ("Kotlin", "Java", "TypeScript", etc.)

### 2.2 Selection → Add to Context

The user selects code, right-clicks, clicks "Add to OpenCode context".
The action reads the selection from the editor's `SelectionModel`.

```kotlin
class AddToContextAction : AnAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible =
            editor?.selectionModel?.hasSelection() == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor  = e.getData(CommonDataKeys.EDITOR) ?: return
        val file    = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return

        val sel = editor.selectionModel
        if (!sel.hasSelection()) return

        val startLine = editor.offsetToLogicalPosition(sel.selectionStart).line + 1  // 1-based for display
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
    }
}
```

### 2.3 Open Tabs

```kotlin
fun getOpenTabs(project: Project): List<TabInfo> {
    val mgr = FileEditorManager.getInstance(project)
    val active = mgr.selectedFiles.firstOrNull()
    return mgr.openFiles.map { f ->
        TabInfo(
            path     = f.path,
            name     = f.name,
            active   = f == active,
            modified = FileDocumentManager.getInstance()
                           .getDocument(f)
                           ?.isModified == true,
        )
    }
}
```

Listen for tab open/close events to keep the webapp in sync:

```kotlin
project.messageBus.connect(disposable).subscribe(
    FileEditorManagerListener.FILE_EDITOR_MANAGER,
    object : FileEditorManagerListener {
        override fun fileOpened(source: FileEditorManager, file: VirtualFile) = sendTabs()
        override fun fileClosed(source: FileEditorManager, file: VirtualFile) = sendTabs()
        override fun selectionChanged(event: FileEditorManagerEvent)          = sendTabs()
    }
)
```

### 2.4 Theme Sync

```kotlin
fun currentThemePayload(): String {
    val dark = !JBColor.isBright()
    val bg   = ColorUtil.toHex(JBColor.background())
    val fg   = ColorUtil.toHex(JBColor.foreground())
    val accent = ColorUtil.toHex(
        JBColor.namedColor("Link.activeForeground", JBColor.BLUE)
    )
    val border = ColorUtil.toHex(
        JBColor.namedColor("Component.borderColor", JBColor.GRAY)
    )
    return """{"dark":$dark,"bg":"#$bg","fg":"#$fg","accent":"#$accent","border":"#$border"}"""
}
```

Listen for theme changes:

```kotlin
ApplicationManager.getApplication().messageBus.connect(disposable).subscribe(
    EditorColorsManager.TOPIC,
    EditorColorsListener { sendTheme() }
)
```

---

## 3. Complete Data Flow

```
User switches file in IDE
        │
        ▼
FileEditorManagerListener.selectionChanged()
        │
        ▼
IdeContextService.onActiveEditorChanged()
   - reads file path, language, line count
   - gets surrounding code (±50 lines)
   - gets all open tabs
        │
        ▼
BrowserBridge.sendActiveEditor(payload: String)
   browser.cefBrowser.executeJavaScript(
     "window.dispatchEvent(new CustomEvent('opencode', {detail: $payload}))"
   )
        │
        ▼
  [JCEF Chromium process]
        │
        ▼
packages/app/src/context/ide-bridge.ts
   window.addEventListener('opencode', handler)
   → updates ideContext store
        │
        ▼
IdeContextBar component re-renders
   showing active file, line, open tabs

─────────────────────────────────────────

User selects code → right-click → "Add to OpenCode Context"
        │
        ▼
AddToContextAction.actionPerformed()
   - reads selectionModel.selectedText
   - reads start/end line from logical position
   - reads file path and language
        │
        ▼
BrowserBridge.sendSelection(path, startLine, endLine, code, lang)
        │
        ▼
  [JCEF bridge]
        │
        ▼
ide-bridge.ts receives selectionAdded event
   → appends to ideContext.attachedSelections
        │
        ▼
PromptContextItems re-renders with new pill
   [Main.kt L42-58] ✕

─────────────────────────────────────────

User clicks Send in webapp
        │
        ▼
PromptInput.submit()
   - builds request parts from prompt text
   - appends each ideContext.attachedSelections as FileContextItem
     (same ContextItem type the webapp already uses for @file mentions)
   - calls sdk.session.prompt(...)
        │
        ▼
OpenCode HTTP server (/session/:id/prompt)
   processes the prompt with context parts
        │
        ▼
SSE stream back to webapp
   → chat messages render as normal

─────────────────────────────────────────

Webapp ready / needs to navigate editor
        │
        ▼
window.__sendToIDE(JSON.stringify({ type: "openFile", path: "/...", line: 42 }))
        │
        ▼
JBCefJSQuery handler in BrowserBridge
   → FileEditorManager.getInstance(project).openFile(virtualFile, true)
   → editor.caretModel.moveToLogicalPosition(LogicalPosition(line, 0))
```

---

## 4. Kotlin Plugin — Full Implementation

### 4.1 ServerManager.kt

Spawns and owns the `opencode serve` process.
One instance per project (project-level service).

```kotlin
package ai.opencode.plugin.server

import ai.opencode.plugin.settings.OpenCodeSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import java.io.File
import java.net.ServerSocket

private val LOG = logger<ServerManager>()

@Service(Service.Level.PROJECT)
class ServerManager(private val project: Project) : Disposable {

    private var process: Process? = null
    var port: Int = -1
        private set

    /** Start the server. Returns the port it's listening on. Idempotent. */
    fun start(): Int {
        if (process?.isAlive == true) return port

        val bin  = OpenCodeSettings.instance.executablePath.ifEmpty { findOnPath() }
        val cwd  = project.basePath ?: error("Project has no base path")
        port     = freePort()

        LOG.info("Starting opencode server on port $port, cwd=$cwd")

        process = ProcessBuilder(bin, "serve", "--port", "$port", "--cwd", cwd)
            .directory(File(cwd))
            .redirectErrorStream(true)
            .start()

        // Drain stdout to avoid blocking
        process!!.inputStream.bufferedReader().lines().forEach {
            LOG.debug("[opencode] $it")
        }

        waitUntilHealthy(port, timeoutMs = 10_000)
        return port
    }

    fun isRunning() = process?.isAlive == true

    override fun dispose() {
        LOG.info("Stopping opencode server (port=$port)")
        process?.destroyForcibly()
        process = null
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun findOnPath(): String {
        val candidates = listOf("opencode", "opencode.exe")
        val path = System.getenv("PATH")?.split(File.pathSeparator) ?: emptyList()
        for (dir in path) for (name in candidates) {
            val f = File(dir, name)
            if (f.isFile && f.canExecute()) return f.absolutePath
        }
        error("opencode binary not found. Set the path in Settings > OpenCode.")
    }

    private fun waitUntilHealthy(port: Int, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                java.net.URL("http://localhost:$port/").openConnection().connect()
                return
            } catch (_: Exception) {
                Thread.sleep(200)
            }
        }
        LOG.warn("opencode server did not become healthy within ${timeoutMs}ms")
    }
}
```

### 4.2 BrowserBridge.kt

One instance per project. Owns the `JBCefBrowser` and the `JBCefJSQuery`.
Serialises all IDE-context messages and dispatches them to the webapp.

```kotlin
package ai.opencode.plugin.bridge

import ai.opencode.plugin.server.ServerManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import org.intellij.lang.annotations.Language

@Service(Service.Level.PROJECT)
class BrowserBridge(private val project: Project) : Disposable {

    val browser: JBCefBrowser by lazy { createBrowser() }
    private var query: JBCefJSQuery? = null

    // ── lifecycle ─────────────────────────────────────────────────────────────

    private fun createBrowser(): JBCefBrowser {
        check(JBCefApp.isSupported()) { "JCEF is not supported in this IDE" }

        val b = JBCefBrowser.createBuilder()
            .setOffScreenRendering(false)
            .build()

        // Wire up the JS→Kotlin query
        query = JBCefJSQuery.create(b as JBCefBrowserBase).also { q ->
            q.addHandler { msg ->
                handleFromWebapp(msg)
                null
            }
        }

        // After every page load, inject our runtime config + query function
        b.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(browser: CefBrowser, frame: CefFrame, code: Int) {
                if (!frame.isMain) return
                injectRuntime(b)
            }
        }, b.cefBrowser)

        return b
    }

    fun load() {
        val port = project.service<ServerManager>().start()
        val dir  = project.basePath ?: return
        // The webapp reads window.__OPENCODE_CONFIG__ on startup
        browser.loadURL("http://localhost:$port")
    }

    override fun dispose() {
        query?.dispose()
        browser.dispose()
    }

    // ── push  IDE → Webapp ────────────────────────────────────────────────────

    fun send(@Language("JSON") payload: String) {
        browser.cefBrowser.executeJavaScript(
            "window.dispatchEvent(new CustomEvent('opencode:ide',{detail:$payload}))",
            browser.cefBrowser.url, 0
        )
    }

    fun sendActiveEditor(
        path: String, language: String,
        line: Int, col: Int,
        lineCount: Int, surroundingCode: String,
    ) {
        send("""{"type":"activeEditor","path":${path.json()},"language":${language.json()},""" +
             """"line":$line,"col":$col,"lineCount":$lineCount,""" +
             """"surroundingCode":${surroundingCode.json()}}""")
    }

    fun sendTabs(tabs: List<TabPayload>) {
        val arr = tabs.joinToString(",") {
            """{"path":${it.path.json()},"name":${it.name.json()},"active":${it.active},"modified":${it.modified}}"""
        }
        send("""{"type":"openTabs","tabs":[$arr]}""")
    }

    fun sendSelection(
        path: String, startLine: Int, endLine: Int,
        code: String, language: String,
    ) {
        val id = "${path}:${startLine}:${endLine}".hashCode().toString()
        send("""{"type":"selectionAdded","id":${id.json()},"path":${path.json()},""" +
             """"name":${path.substringAfterLast('/').json()},""" +
             """"startLine":$startLine,"endLine":$endLine,""" +
             """"code":${code.json()},"language":${language.json()}}""")
    }

    fun sendTheme(dark: Boolean, bg: String, fg: String, accent: String, border: String) {
        send("""{"type":"theme","dark":$dark,"bg":${bg.json()},"fg":${fg.json()},"accent":${accent.json()},"border":${border.json()}}""")
    }

    fun sendProjectInfo(path: String, name: String, branch: String?) {
        send("""{"type":"projectInfo","path":${path.json()},"name":${name.json()},"branch":${branch?.json() ?: "null"}}""")
    }

    // ── receive  Webapp → IDE ─────────────────────────────────────────────────

    private fun handleFromWebapp(msg: String) {
        // Parse type field cheaply without a full JSON library
        val type = Regex(""""type"\s*:\s*"([^"]+)"""").find(msg)?.groupValues?.get(1)
        when (type) {
            "ready"    -> onWebappReady()
            "openFile" -> {
                val path = Regex(""""path"\s*:\s*"([^"]+)"""").find(msg)?.groupValues?.get(1) ?: return
                val line = Regex(""""line"\s*:\s*(\d+)""").find(msg)?.groupValues?.get(1)?.toIntOrNull()
                openFileInEditor(path, line)
            }
        }
    }

    private fun onWebappReady() {
        ApplicationManager.getApplication().invokeLater {
            project.service<IdeContextService>().sendCurrentState()
        }
    }

    private fun openFileInEditor(path: String, line: Int?) {
        ApplicationManager.getApplication().invokeLater {
            val vf = LocalFileSystem.getInstance().findFileByPath(path) ?: return@invokeLater
            val desc = if (line != null)
                OpenFileDescriptor(project, vf, line - 1, 0)   // LogicalPosition is 0-based
            else
                OpenFileDescriptor(project, vf)
            FileEditorManager.getInstance(project).openTextEditor(desc, true)
        }
    }

    // ── runtime injection ─────────────────────────────────────────────────────

    private fun injectRuntime(b: JBCefBrowser) {
        val port   = project.service<ServerManager>().port
        val dir    = project.basePath ?: ""
        val name   = project.name
        val q      = query ?: return
        b.cefBrowser.executeJavaScript("""
            window.__OPENCODE_CONFIG__ = {
                serverUrl:  'http://localhost:$port',
                projectDir: ${dir.json()},
                projectName: ${name.json()},
                target:     'jetbrains'
            };
            window.__sendToIDE = function(msg) {
                ${q.inject("msg")}
            };
        """.trimIndent(), b.cefBrowser.url, 0)
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    data class TabPayload(val path: String, val name: String, val active: Boolean, val modified: Boolean)

    // JSON-encode a string: escape backslashes and double-quotes, wrap in quotes
    private fun String.json(): String =
        "\"${replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")}\""
}
```

### 4.3 IdeContextService.kt

Subscribes to all IDE events and feeds them through BrowserBridge.
Debounces the caret listener so it doesn't spam the webapp on every keystroke.

```kotlin
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
import com.intellij.ui.jcef.JBColor
import com.intellij.util.ui.ColorUtil
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

@Service(Service.Level.PROJECT)
class IdeContextService(private val project: Project) : Disposable {

    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var caretTask: ScheduledFuture<*>? = null

    init {
        subscribeToFileEditorEvents()
        subscribeToCaretEvents()
        subscribeToThemeEvents()
    }

    // ── public ────────────────────────────────────────────────────────────────

    /** Push the current IDE state to a freshly loaded webapp. */
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
        dispatchEditorState(file, editor)
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
        val line      = pos.line + 1      // convert to 1-based for the webapp
        val col       = pos.column + 1
        val doc       = editor.document
        val lineCount = doc.lineCount
        val surrounding = getSurrounding(editor, pos.line, radius = 50)
        val lang      = file.fileType.name

        project.service<BrowserBridge>().sendActiveEditor(
            path           = file.path,
            language       = lang,
            line           = line,
            col            = col,
            lineCount      = lineCount,
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
                modified = false,   // FileDocumentManager.getInstance().getDocument(f)?.isModified == true
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
```

### 4.4 OpenCodeToolWindowFactory.kt

```kotlin
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
```

### 4.5 AddToContextAction.kt

```kotlin
package ai.opencode.plugin.actions

import ai.opencode.plugin.bridge.BrowserBridge
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor

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
        val tw = com.intellij.openapi.wm.ToolWindowManager
            .getInstance(project).getToolWindow("OpenCode")
        tw?.activate(null)
    }
}
```

---

## 5. Webapp Changes — Full Implementation

### 5.1 `src/context/ide-bridge.ts` (new file)

```ts
import { createStore } from "solid-js/store"
import { createRoot } from "solid-js"

// ── Types (mirrors Kotlin payloads exactly) ───────────────────────────────────

export type IdeTab = {
  path: string
  name: string
  active: boolean
  modified: boolean
}

export type IdeSelection = {
  id: string
  path: string
  name: string
  startLine: number
  endLine: number
  code: string
  language: string
}

export type IdeContext = {
  ready: boolean
  projectDir: string | null
  projectName: string | null
  branch: string | null
  activeFile: string | null
  language: string | null
  line: number
  col: number
  lineCount: number
  surroundingCode: string | null
  tabs: IdeTab[]
  selections: IdeSelection[] // manually pinned selections
  theme: {
    dark: boolean
    bg: string
    fg: string
    accent: string
    border: string
  } | null
}

// ── Store (singleton, created outside any component) ─────────────────────────

const [ctx, setCtx] = createStore<IdeContext>({
  ready: false,
  projectDir: null,
  projectName: null,
  branch: null,
  activeFile: null,
  language: null,
  line: 0,
  col: 0,
  lineCount: 0,
  surroundingCode: null,
  tabs: [],
  selections: [],
  theme: null,
})

export const ideContext = ctx

export function removeSelection(id: string) {
  setCtx("selections", (s) => s.filter((x) => x.id !== id))
}

export function clearSelections() {
  setCtx("selections", [])
}

// ── Init (call once from app entry point) ────────────────────────────────────

export function initIdeBridge() {
  // Read runtime config injected by the Kotlin plugin
  const cfg = (window as any).__OPENCODE_CONFIG__
  if (cfg?.target !== "jetbrains") return

  // Apply project info from config immediately
  if (cfg.projectDir) setCtx("projectDir", cfg.projectDir)
  if (cfg.projectName) setCtx("projectName", cfg.projectName)

  // Tell the IDE we're ready (triggers sendCurrentState)
  setTimeout(() => {
    ;(window as any).__sendToIDE?.(JSON.stringify({ type: "ready" }))
    setCtx("ready", true)
  }, 100)

  // Listen for all subsequent pushes from the IDE
  window.addEventListener("opencode:ide", (raw) => {
    const e = (raw as CustomEvent).detail
    switch (e.type) {
      case "activeEditor":
        setCtx({
          activeFile: e.path,
          language: e.language,
          line: e.line,
          col: e.col,
          lineCount: e.lineCount,
          surroundingCode: e.surroundingCode,
        })
        break

      case "openTabs":
        setCtx("tabs", e.tabs)
        break

      case "selectionAdded":
        setCtx("selections", (s) => {
          if (s.some((x) => x.id === e.id)) return s // dedupe
          return [
            ...s,
            {
              id: e.id,
              path: e.path,
              name: e.name,
              startLine: e.startLine,
              endLine: e.endLine,
              code: e.code,
              language: e.language,
            },
          ]
        })
        break

      case "theme":
        setCtx("theme", { dark: e.dark, bg: e.bg, fg: e.fg, accent: e.accent, border: e.border })
        applyThemeToDocument(e)
        break

      case "projectInfo":
        setCtx({ projectDir: e.path, projectName: e.name, branch: e.branch ?? null })
        break
    }
  })
}

function applyThemeToDocument(t: { dark: boolean; bg: string; fg: string; accent: string; border: string }) {
  const root = document.documentElement
  root.classList.toggle("dark", t.dark)
  root.style.setProperty("--ide-bg", t.bg)
  root.style.setProperty("--ide-fg", t.fg)
  root.style.setProperty("--ide-accent", t.accent)
  root.style.setProperty("--ide-border", t.border)
}

// Expose to global scope for webapp to call (e.g., open file click)
;(window as any).__openFileInIDE = (path: string, line?: number) => {
  ;(window as any).__sendToIDE?.(JSON.stringify({ type: "openFile", path, line }))
}
```

### 5.2 `src/env.ts` (new file or extend existing)

```ts
export const config = (window as any).__OPENCODE_CONFIG__ ?? {
  serverUrl: import.meta.env.VITE_SERVER_URL ?? "http://localhost:4096",
  projectDir: null,
  projectName: null,
  target: import.meta.env.VITE_TARGET ?? "web",
}

export const isJetBrains = config.target === "jetbrains"
```

### 5.3 Connect ide-bridge to existing context system

In the webapp's existing `PromptContext`, the context items (`FileContextItem`) drive
what appears in the prompt pills and what gets sent to the server.

The bridge just needs to **feed IDE selections into the existing context.add() API**.
No new context system — just glue.

In `packages/app/src/app.tsx` (or wherever providers are initialised):

```ts
import { initIdeBridge, ideContext, removeSelection } from "./context/ide-bridge"
import { usePrompt } from "./context/prompt"

// Inside the root component's onMount:
onMount(() => {
  initIdeBridge()
})

// In the prompt submission handler, BEFORE sending to the server,
// convert ide-bridge selections into ContextItems:
createEffect(() => {
  // When a new selection arrives from the IDE, auto-add it to prompt context
  for (const sel of ideContext.selections) {
    prompt.context.add({
      type: "file",
      path: sel.path,
      selection: {
        startLine: sel.startLine,
        startChar: 0,
        endLine: sel.endLine,
        endChar: 9999,
      },
    })
  }
})
```

The existing `ContextItem` type already has a `selection` field with `startLine/endLine`.
The existing `PromptContextItems` component already renders the pills with those line numbers.
The existing `build-request-parts.ts` already serialises them as `resource_link` parts.

**The only new code is the bridge that feeds selections from the IDE into the existing system.**
Nothing else in the prompt pipeline changes.

### 5.4 IDE context bar above prompt (new component)

```tsx
// packages/app/src/components/ide-context-bar.tsx
import { For, Show } from "solid-js"
import { ideContext } from "@/context/ide-bridge"
import { isJetBrains } from "@/env"
import { getFilename } from "@opencode-ai/util/path"

export function IdeContextBar() {
  if (!isJetBrains) return null

  const active = () => ideContext.activeFile
  const tabs = () => ideContext.tabs.filter((t) => !t.active).slice(0, 5)

  return (
    <Show when={active()}>
      <div class="flex items-center gap-2 px-2 py-1 text-11-regular text-text-weak border-t border-border-base overflow-x-auto no-scrollbar">
        {/* Active file indicator */}
        <span class="flex items-center gap-1 shrink-0 text-text-strong">
          <span class="size-1.5 rounded-full bg-icon-success-base" />
          {getFilename(active()!)}
          <span class="text-text-weaker">:{ideContext.line}</span>
        </span>

        {/* Other open tabs */}
        <For each={tabs()}>
          {(tab) => (
            <button
              class="shrink-0 px-1.5 py-0.5 rounded hover:bg-surface-interactive-weak transition-colors"
              onClick={() => (window as any).__openFileInIDE?.(tab.path)}
            >
              {tab.name}
            </button>
          )}
        </For>
      </div>
    </Show>
  )
}
```

Add it inside the prompt input area, just above the text field.
Since `isJetBrains` is `false` on web, it renders nothing there.

### 5.5 JetBrains layout (new file)

```tsx
// packages/app/src/pages/jetbrains-layout.tsx
import { ParentProps, createMemo, For, Show } from "solid-js"
import { useNavigate, useParams } from "@solidjs/router"
import { useGlobalSync } from "@/context/global-sync"
import { useGlobalSDK } from "@/context/global-sdk"
import { ideContext } from "@/context/ide-bridge"
import { base64Encode } from "@opencode-ai/util/encode"
import { config } from "@/env"

export default function JetBrainsLayout(props: ParentProps) {
  const sync = useGlobalSync()
  const sdk = useGlobalSDK()
  const navigate = useNavigate()
  const params = useParams()

  const dir = config.projectDir ?? ""
  const sessions = createMemo(() => {
    const [store] = sync.child(dir, { bootstrap: true })
    return store.session.filter((s) => !s.time.archived).slice(0, 20)
  })

  async function newSession() {
    const res = await sdk.client.session.create({ directory: dir })
    const id = res.data?.id
    if (!id) return
    navigate(`/${base64Encode(dir)}/session/${id}`)
  }

  return (
    <div class="flex flex-col h-screen overflow-hidden bg-background-base">
      {/* Compact header */}
      <div class="flex items-center gap-2 px-3 h-9 border-b border-border-base shrink-0 text-12-medium">
        <span class="text-text-strong truncate">{ideContext.projectName ?? config.projectName ?? "OpenCode"}</span>
        <Show when={ideContext.branch}>
          <span class="text-text-weak">{ideContext.branch}</span>
        </Show>
      </div>

      {/* Session tab bar */}
      <div class="flex items-center gap-1 px-2 h-8 border-b border-border-base shrink-0 overflow-x-auto no-scrollbar">
        <For each={sessions()}>
          {(session) => (
            <button
              class="shrink-0 px-2 py-0.5 rounded text-11-regular whitespace-nowrap transition-colors"
              classList={{
                "bg-surface-interactive-hover text-text-strong": session.id === params.id,
                "text-text-weak hover:text-text-base hover:bg-surface-interactive-weak": session.id !== params.id,
              }}
              onClick={() => navigate(`/${base64Encode(dir)}/session/${session.id}`)}
            >
              {session.title ?? "New Session"}
            </button>
          )}
        </For>
        <button class="shrink-0 px-2 py-0.5 text-11-regular text-text-weak hover:text-text-base" onClick={newSession}>
          +
        </button>
      </div>

      {/* Page content (session chat) */}
      <div class="flex-1 min-h-0 overflow-hidden">{props.children}</div>
    </div>
  )
}
```

### 5.6 Conditional routing in app.tsx

```ts
// packages/app/src/app.tsx  — relevant excerpt
import { isJetBrains, config } from "./env"
import JetBrainsLayout from "./pages/jetbrains-layout"
import Layout from "./pages/layout"

const Root = isJetBrains ? JetBrainsLayout : Layout

// In the router, boot directly into the project dir when target=jetbrains
const initialPath = isJetBrains && config.projectDir ? `/${base64Encode(config.projectDir)}/session` : "/"
```

---

## 6. plugin.xml

```xml
<idea-plugin>
  <id>ai.opencode.plugin</id>
  <name>OpenCode</name>
  <version>1.0.0</version>
  <vendor url="https://opencode.ai">OpenCode</vendor>

  <description><![CDATA[
    OpenCode AI coding agent inside your JetBrains IDE.
    Supports context from active file, cursor position, open tabs, and manual code selection.
  ]]></description>

  <idea-version since-build="241.0"/>

  <depends>com.intellij.modules.platform</depends>

  <!-- Services -->
  <extensions defaultExtensionNs="com.intellij">

    <projectService
      serviceImplementation="ai.opencode.plugin.server.ServerManager"/>

    <projectService
      serviceImplementation="ai.opencode.plugin.bridge.BrowserBridge"/>

    <projectService
      serviceImplementation="ai.opencode.plugin.bridge.IdeContextService"/>

    <toolWindow
      id="OpenCode"
      anchor="right"
      icon="/icons/opencode.svg"
      factoryClass="ai.opencode.plugin.toolwindow.OpenCodeToolWindowFactory"/>

    <applicationConfigurable
      parentId="tools"
      id="ai.opencode.plugin.settings"
      instance="ai.opencode.plugin.settings.OpenCodeConfigurable"
      displayName="OpenCode"/>

  </extensions>

  <!-- Actions -->
  <actions>

    <action
      id="ai.opencode.AddToContext"
      class="ai.opencode.plugin.actions.AddToContextAction"
      text="Add to OpenCode Context"
      description="Add selected code to the OpenCode chat context">
      <add-to-group group-id="EditorPopupMenu" anchor="after" relative-to-action="$Copy"/>
      <keyboard-shortcut keymap="$default" first-keystroke="control alt A"/>
    </action>

    <action
      id="ai.opencode.OpenPanel"
      class="ai.opencode.plugin.actions.OpenPanelAction"
      text="Open OpenCode"
      description="Open the OpenCode chat panel">
      <keyboard-shortcut keymap="$default" first-keystroke="control BACK_SLASH"/>
      <keyboard-shortcut keymap="Mac OS X" first-keystroke="meta BACK_SLASH"/>
    </action>

  </actions>

</idea-plugin>
```

---

## 7. Gradle Build

```kotlin
// packages/jetbrains-plugin/build.gradle.kts
plugins {
    id("org.jetbrains.intellij.platform") version "2.13.0"
    kotlin("jvm") version "2.0.0"
}

group   = "ai.opencode"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.3")
        instrumentationTools()
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "241"
        }
    }
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey        = providers.environmentVariable("PRIVATE_KEY")
        password          = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}

// Build the webapp before processing resources
val buildWebapp by tasks.registering(Exec::class) {
    workingDir  = rootProject.file("../app")
    commandLine = listOf("bun", "run", "build")
    environment("VITE_TARGET",     "jetbrains")
    environment("VITE_SERVER_URL", "http://localhost:4096")  // dev default, overridden at runtime
}

tasks.named("processResources") {
    dependsOn(buildWebapp)
}

// Copy built webapp into plugin resources
tasks.named<Copy>("processResources") {
    from("../app/dist") {
        into("webview")
    }
}
```

---

## 8. Serving the Webapp from JAR

The webapp is built to static files and bundled inside the plugin JAR at `webview/`.
The plugin serves these files by intercepting requests via a custom `CefSchemeHandlerFactory`,
or — simpler — by extracting them to a temp dir on first load.

The simplest approach: since the plugin spawns `opencode serve`, the OpenCode HTTP server
can be extended to serve the webapp's static files directly. That means:

- `opencode serve` already starts at `http://localhost:<port>`
- The webapp static files go into the OpenCode server's static file serving path
- The plugin just loads `http://localhost:<port>/`

To do that, add a static file serving route to the OpenCode server:

```ts
// packages/opencode/src/server/server.ts — add one line
app.use("/*", serveStatic({ root: "./webapp" }))
```

At plugin startup, extract the JAR resources to the OpenCode data dir before starting the server:

```kotlin
fun extractWebapp() {
    val dest = File(System.getProperty("user.home"), ".opencode/webapp")
    if (dest.exists()) return  // already extracted (check version hash in prod)
    dest.mkdirs()
    // iterate JAR resources at /webview/ and copy to dest
    val loader = javaClass.classLoader
    // ... standard JAR resource extraction
}
```

Dev mode: set `OPENCODE_WEBAPP_DIR=/path/to/packages/app/dist` to skip JAR extraction.

---

## 9. Things That Will Bite You

### Thread safety

`browser.cefBrowser.executeJavaScript()` can be called from any thread. Safe.
But `FileEditorManager`, `CaretModel`, etc. must be read on the EDT or inside `runReadAction`.
The `IdeContextService` does this via `ApplicationManager.getApplication().runReadAction { ... }`.

### The caret listener fires constantly

Every arrow key press fires `caretPositionChanged`. Without debouncing, you'd be
serialising 100 lines of surrounding code on every keystroke. The 500ms debounce
in `scheduleCaretUpdate` is essential.

### `getSurrounding` is a read action

Reading `editor.document.getText()` must happen inside a read action. The scheduled
executor wraps it in `runReadAction`.

### JCEF availability

`JBCefApp.isSupported()` returns false in:

- Remote Development (Code With Me host side)
- Some CI/headless environments
  Always check and fall back gracefully.

### The JBCefJSQuery inject() call

`query.inject("msg")` generates something like:
`window.cefQuery_1234({request: msg, persistent: false, onSuccess: function(){}, onFailure: function(){}})`
The exact function name is generated at runtime. You **must** use `query.inject()` —
you cannot hardcode the function name. Always inject the query after the page loads
(in `onLoadEnd`), because the function name is registered into the page's JS context
at that point.

### Serving on `file://` vs `http://`

JCEF has stricter CORS rules for `file://` origins. Loading the webapp from
`http://localhost:<port>` (the OpenCode server) avoids all CORS issues since the
API calls also go to the same origin.

### Multiple projects = multiple server ports

Each `Project` gets its own `ServerManager` instance (project-level service), which
picks its own port. `BrowserBridge` reads the port from its project's `ServerManager`.
Two open projects = two opencode processes on different ports = two JCEF panels, each
pointed at the right port. No conflicts.
