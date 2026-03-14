package ai.opencode.plugin.server

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI

private val LOG = logger<ServerManager>()

@Service(Service.Level.APP)
class ServerManager : Disposable {

    private var process: Process? = null
    var port: Int = -1
        private set

    fun start(): Int {
        // 1. If our own process is alive, return it
        if (process?.isAlive == true && port > 0) return port

        // 2. If a singleton is already running, reuse it
        val existing = readLock()?.takeIf { isHealthy(it.port) }
        if (existing != null) {
            port = existing.port
            return port
        }

        // 3. Start a new global server
        val bin  = resolvedBin()
        port     = freePort()

        LOG.info("Starting global opencode binary=$bin port=$port")

        process = ProcessBuilder(bin, "serve", "--port", "$port")
            .directory(File(System.getProperty("user.home")))
            .redirectErrorStream(true)
            .start()

        Thread {
            process!!.inputStream.bufferedReader().lines().forEach {
                LOG.debug("[opencode] $it")
            }
        }.also { it.isDaemon = true }.start()

        waitUntilHealthy(port, timeoutMs = 15_000)
        writeLock(port)
        return port
    }

    fun isRunning() = process?.isAlive == true

    override fun dispose() {
        // Do not kill the global server on project close. Only stop if this
        // process started it and the app is shutting down.
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

    private fun lockFile(): File {
        val dir = File(System.getProperty("user.home"), ".opencode-plugin")
        dir.mkdirs()
        return File(dir, "server.json")
    }

    private data class Lock(val port: Int, val pid: Long)

    private fun readLock(): Lock? {
        val file = lockFile()
        if (!file.exists()) return null
        return runCatching {
            val text = file.readText()
            val port = Regex("\"port\"\\s*:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toInt() ?: return null
            val pid = Regex("\"pid\"\\s*:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toLong() ?: 0L
            Lock(port, pid)
        }.getOrNull()
    }

    private fun writeLock(port: Int) {
        val pid = ProcessHandle.current().pid()
        val text = "{" + "\"port\":" + port + ",\"pid\":" + pid + "}"
        lockFile().writeText(text)
    }

    private fun isHealthy(port: Int): Boolean {
        return try {
            val conn = URI("http://localhost:$port/").toURL()
                .openConnection() as HttpURLConnection
            conn.connectTimeout = 300
            conn.readTimeout    = 300
            conn.connect()
            val code = conn.responseCode
            conn.disconnect()
            code > 0
        } catch (_: Exception) {
            false
        }
    }

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
