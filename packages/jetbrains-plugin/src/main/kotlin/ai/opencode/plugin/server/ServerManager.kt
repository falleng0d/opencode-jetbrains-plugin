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

        val bin = OpenCodeSettings.instance.executablePath.ifEmpty { findOnPath() }
        val cwd = project.basePath ?: error("Project has no base path")
        port    = freePort()

        LOG.info("Starting opencode server on port $port, cwd=$cwd")

        process = ProcessBuilder(bin, "serve", "--port", "$port", "--cwd", cwd)
            .directory(File(cwd))
            .redirectErrorStream(true)
            .start()

        // Drain stdout to avoid blocking
        Thread {
            process!!.inputStream.bufferedReader().lines().forEach {
                LOG.debug("[opencode] $it")
            }
        }.also { it.isDaemon = true }.start()

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
