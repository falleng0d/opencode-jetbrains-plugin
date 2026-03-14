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
import com.sun.net.httpserver.HttpServer
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.net.InetSocketAddress
import java.net.ServerSocket

@Service(Service.Level.PROJECT)
class BrowserBridge(private val project: Project) : Disposable {

    val browser: JBCefBrowser by lazy { createBrowser() }
    private var query: JBCefJSQuery? = null

    // Local HTTP server that serves the bundled webview with __OPENCODE_CONFIG__ injected
    private var fileServer: HttpServer? = null
    private var fileServerPort: Int = -1

    // ── lifecycle ─────────────────────────────────────────────────────────────

    private fun createBrowser(): JBCefBrowser {
        check(JBCefApp.isSupported()) { "JCEF is not supported in this IDE" }

        val b = JBCefBrowser.createBuilder()
            .setOffScreenRendering(false)
            .build()

        query = JBCefJSQuery.create(b as JBCefBrowserBase).also { q ->
            q.addHandler { msg ->
                handleFromWebapp(msg)
                null
            }
        }

        b.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(browser: CefBrowser, frame: CefFrame, code: Int) {
                if (!frame.isMain) return
                // Inject the JS query bridge (config is already in the HTML)
                injectQueryBridge(b)
            }
        }, b.cefBrowser)

        return b
    }

    fun load() {
        val apiPort = service<ServerManager>().start()
        fileServerPort = startFileServer(apiPort)
        browser.loadURL("http://localhost:$fileServerPort/")
    }

    override fun dispose() {
        query?.dispose()
        fileServer?.stop(0)
        browser.dispose()
    }

    // ── local file server ─────────────────────────────────────────────────────

    /**
     * Starts a tiny HTTP server that serves the bundled webview files.
     * The index.html response has __OPENCODE_CONFIG__ injected as the very
     * first <script> tag, so it is defined before any module code runs.
     */
    private fun startFileServer(apiPort: Int): Int {
        val port = freePort()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
        fileServer = server

        val dir  = project.basePath ?: ""
        val name = project.name
        val configScript = """
            <script>
            window.__OPENCODE_CONFIG__ = {
                serverUrl:   'http://localhost:$apiPort',
                projectDir:  ${dir.json()},
                projectName: ${name.json()},
                target:      'jetbrains'
            };
            </script>
        """.trimIndent()

        server.createContext("/") { exchange ->
            var path = exchange.requestURI.path.trimStart('/')
            if (path.isEmpty() || path == "index.html") path = "index.html"

            val resource = javaClass.getResourceAsStream("/webview/$path")

            if (resource == null) {
                val body = "Not found: $path".toByteArray()
                exchange.sendResponseHeaders(404, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
                return@createContext
            }

            val mime = mimeFor(path)
            val bytes = resource.use { it.readBytes() }

            val response = if (path == "index.html") {
                // Inject config before </head>
                val html = bytes.toString(Charsets.UTF_8)
                val injected = html.replace("</head>", "$configScript\n</head>")
                injected.toByteArray(Charsets.UTF_8)
            } else {
                bytes
            }

            exchange.responseHeaders.add("Content-Type", mime)
            exchange.responseHeaders.add("Cache-Control", "no-cache")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }

        server.executor = null
        server.start()
        return port
    }

    private fun mimeFor(path: String) = when {
        path.endsWith(".html")  -> "text/html; charset=utf-8"
        path.endsWith(".js")    -> "application/javascript"
        path.endsWith(".css")   -> "text/css"
        path.endsWith(".svg")   -> "image/svg+xml"
        path.endsWith(".png")   -> "image/png"
        path.endsWith(".woff2") -> "font/woff2"
        path.endsWith(".woff")  -> "font/woff"
        path.endsWith(".json")  -> "application/json"
        path.endsWith(".wasm")  -> "application/wasm"
        else                    -> "application/octet-stream"
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
            "ready"    -> ApplicationManager.getApplication().invokeLater {
                project.service<IdeContextService>().sendCurrentState()
            }
            "openFile" -> {
                val path = Regex(""""path"\s*:\s*"([^"]+)"""").find(msg)?.groupValues?.get(1) ?: return
                val line = Regex(""""line"\s*:\s*(\d+)""").find(msg)?.groupValues?.get(1)?.toIntOrNull()
                openFileInEditor(path, line)
            }
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

    // ── query bridge injection ────────────────────────────────────────────────

    private fun injectQueryBridge(b: JBCefBrowser) {
        val q = query ?: return
        b.cefBrowser.executeJavaScript("""
            window.__sendToIDE = function(msg) {
                ${q.inject("msg")}
            };
        """.trimIndent(), b.cefBrowser.url, 0)
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    data class TabPayload(val path: String, val name: String, val active: Boolean, val modified: Boolean)

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun String.json(): String =
        "\"${replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")}\""
}
