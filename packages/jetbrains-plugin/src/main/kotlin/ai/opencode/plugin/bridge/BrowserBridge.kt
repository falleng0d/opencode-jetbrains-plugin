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
        browser.loadURL("http://localhost:$port")
    }

    override fun dispose() {
        query?.dispose()
        browser.dispose()
    }

    // ── push  IDE → Webapp ────────────────────────────────────────────────────

    fun send(payload: String) {
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
                OpenFileDescriptor(project, vf, line - 1, 0)
            else
                OpenFileDescriptor(project, vf)
            FileEditorManager.getInstance(project).openTextEditor(desc, true)
        }
    }

    // ── runtime injection ─────────────────────────────────────────────────────

    private fun injectRuntime(b: JBCefBrowser) {
        val port = project.service<ServerManager>().port
        val dir  = project.basePath ?: ""
        val name = project.name
        val q    = query ?: return
        b.cefBrowser.executeJavaScript("""
            window.__OPENCODE_CONFIG__ = {
                serverUrl:   'http://localhost:$port',
                projectDir:  ${dir.json()},
                projectName: ${name.json()},
                target:      'jetbrains'
            };
            window.__sendToIDE = function(msg) {
                ${q.inject("msg")}
            };
        """.trimIndent(), b.cefBrowser.url, 0)
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    data class TabPayload(val path: String, val name: String, val active: Boolean, val modified: Boolean)

    private fun String.json(): String =
        "\"${replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")}\""
}
