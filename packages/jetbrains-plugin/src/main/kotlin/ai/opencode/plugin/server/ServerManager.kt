package ai.opencode.plugin.server

import ai.opencode.plugin.util.resource
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

    private fun isWindows() = System.getProperty("os.name").lowercase().contains("win")

    private fun isMac() = System.getProperty("os.name").lowercase().contains("mac")

    private fun arch() = when (System.getProperty("os.arch").lowercase()) {
        "amd64", "x86_64" -> "x64"
        "aarch64", "arm64" -> "arm64"
        else -> error("Unsupported architecture: ${System.getProperty("os.arch")}")
    }

    private fun isMusl(): Boolean {
        if (isWindows() || isMac()) return false
        if (File("/etc/alpine-release").isFile) return true

        return runCatching {
            val p = ProcessBuilder("ldd", "--version")
                .redirectErrorStream(true)
                .start()
            val text = p.inputStream.bufferedReader().use { it.readText() }.lowercase()
            p.waitFor()
            text.contains("musl")
        }.getOrDefault(false)
    }

    private fun bundledNames() = when {
        isWindows() && arch() == "arm64" -> listOf("opencode-windows-arm64.exe")
        isWindows() -> listOf("opencode-windows-x64-baseline.exe")
        isMac() && arch() == "arm64" -> listOf("opencode-darwin-arm64")
        isMac() -> listOf("opencode-darwin-x64-baseline")
        arch() == "arm64" && isMusl() -> listOf("opencode-linux-arm64-musl", "opencode-linux-arm64")
        arch() == "arm64" -> listOf("opencode-linux-arm64", "opencode-linux-arm64-musl")
        isMusl() -> listOf("opencode-linux-x64-baseline-musl", "opencode-linux-x64-baseline")
        else -> listOf("opencode-linux-x64-baseline", "opencode-linux-x64-baseline-musl")
    }

    private fun executableNames() = if (isWindows()) listOf("opencode.exe") else listOf("opencode")

    private fun extractedBinDir() = File(System.getProperty("user.home"), ".opencode-plugin/bin")

    private fun isRunnable(file: File) = file.isFile && (isWindows() || file.canExecute())

    /** Returns path to opencode binary, extracting the bundled one if needed. */
    private fun resolvedBin(): String {
        // 1. Check if there's a bundled binary inside the plugin JAR/resources
        val extracted = extractBundled()
        if (extracted != null) return extracted

        // 2. Fall back to well-known install locations
        val home = System.getProperty("user.home")
        val candidates = buildList {
            executableNames().forEach { add(File(extractedBinDir(), it).absolutePath) }
            executableNames().forEach { add("$home/.opencode/bin/$it") }
            executableNames().forEach { add("$home/.local/bin/$it") }
            if (!isWindows()) {
                add("/usr/local/bin/opencode")
            }
        }
        for (p in candidates) {
            val f = File(p)
            if (isRunnable(f)) return f.absolutePath
        }

        // 3. Search PATH (may be truncated in desktop-launched IDE)
        val path = System.getenv("PATH")?.split(File.pathSeparator) ?: emptyList()
        for (dir in path) {
            for (name in executableNames()) {
                val f = File(dir, name)
                if (isRunnable(f)) return f.absolutePath
            }
        }

        error("opencode binary not found. Cannot start server.")
    }

    /**
     * Extracts the bundled binary from plugin resources to
     * ~/.opencode-plugin/bin and returns the executable path.
     * Returns null if no bundled binary exists in resources.
     */
    private fun extractBundled(): String? {
        val dir = extractedBinDir()
        dir.mkdirs()

        val exe = File(dir, if (isWindows()) "opencode.exe" else "opencode")
        for (name in bundledNames()) {
            val resource = resource("/bin/$name") ?: continue

            LOG.info("Extracting bundled opencode binary $name to ${exe.absolutePath}")
            resource.use { input ->
                exe.outputStream().use { out -> input.copyTo(out) }
            }
            if (!isWindows()) {
                exe.setExecutable(true)
            }

            if (!isWindows()) return exe.absolutePath.takeIf { exe.isFile && exe.length() > 0L }

            val bare = File(dir, "opencode")
            exe.inputStream().use { input ->
                bare.outputStream().use { out -> input.copyTo(out) }
            }
            return exe.absolutePath.takeIf { exe.isFile && exe.length() > 0L }
        }

        if (exe.isFile && exe.length() > 0L) {
            return exe.absolutePath
        }

        runCatching {
            File(dir, "opencode.exe")
        }.getOrNull()?.takeIf { it.isFile && it.length() > 0L }?.let {
            return it.absolutePath
        }

        runCatching {
            File(dir, "opencode")
        }.getOrNull()?.takeIf { it.isFile && it.length() > 0L }?.let {
            return it.absolutePath
        }

        LOG.warn("Bundled opencode binary not found in plugin resources from ${javaClass.protectionDomain.codeSource.location}")
        return null
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
