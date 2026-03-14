package ai.opencode.plugin.server

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI

private val LOG = logger<ServerManager>()

@Service(Service.Level.PROJECT)
class ServerManager(private val project: Project) : Disposable {

    private var process: Process? = null
    var port: Int = -1
        private set

    fun start(): Int {
        if (process?.isAlive == true) return port

        val bin  = resolvedBin()
        val cwd  = project.basePath ?: error("Project has no base path")
        port     = freePort()

        LOG.info("Starting opencode binary=$bin port=$port cwd=$cwd")

        process = ProcessBuilder(bin, "serve", "--port", "$port")
            .directory(File(cwd))
            .redirectErrorStream(true)
            .start()

        Thread {
            process!!.inputStream.bufferedReader().lines().forEach {
                LOG.debug("[opencode] $it")
            }
        }.also { it.isDaemon = true }.start()

        waitUntilHealthy(port, timeoutMs = 15_000)
        return port
    }

    fun isRunning() = process?.isAlive == true

    override fun dispose() {
        process?.destroyForcibly()
        process = null
    }

    // ── binary resolution ────────────────────────────────────────────────────

    /** Returns path to opencode binary, extracting the bundled one if needed. */
    private fun resolvedBin(): String {
        // 1. Check if there's a bundled binary inside the plugin JAR/resources
        val extracted = extractBundled()
        if (extracted != null) return extracted

        // 2. Fall back to well-known install locations
        val home = System.getProperty("user.home")
        val candidates = listOf(
            "$home/.opencode/bin/opencode",
            "$home/.local/bin/opencode",
            "/usr/local/bin/opencode",
        )
        for (p in candidates) {
            val f = File(p)
            if (f.isFile && f.canExecute()) return f.absolutePath
        }

        // 3. Search PATH (may be truncated in desktop-launched IDE)
        val path = System.getenv("PATH")?.split(File.pathSeparator) ?: emptyList()
        for (dir in path) {
            val f = File(dir, "opencode")
            if (f.isFile && f.canExecute()) return f.absolutePath
        }

        error("opencode binary not found. Cannot start server.")
    }

    /**
     * Extracts the bundled binary from plugin resources to
     * ~/.opencode-plugin/bin/opencode and returns its path.
     * Returns null if no bundled binary exists in resources.
     */
    private fun extractBundled(): String? {
        val resource = javaClass.getResourceAsStream("/bin/opencode") ?: return null

        val dest = File(System.getProperty("user.home"), ".opencode-plugin/bin/opencode")
        dest.parentFile.mkdirs()

        // Only re-extract if missing (on reinstall the file is replaced)
        if (!dest.exists()) {
            LOG.info("Extracting bundled opencode binary to ${dest.absolutePath}")
            resource.use { input ->
                dest.outputStream().use { out -> input.copyTo(out) }
            }
            dest.setExecutable(true)
        } else {
            resource.close()
        }

        return dest.absolutePath
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun waitUntilHealthy(port: Int, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                val conn = URI("http://localhost:$port/").toURL()
                    .openConnection() as HttpURLConnection
                conn.connectTimeout = 500
                conn.readTimeout    = 500
                conn.connect()
                val code = conn.responseCode
                conn.disconnect()
                if (code > 0) return
            } catch (_: Exception) {
                Thread.sleep(300)
            }
        }
        LOG.warn("opencode server did not become healthy within ${timeoutMs}ms")
    }
}
