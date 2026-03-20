package ai.opencode.plugin.util

import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.jar.JarFile

fun Any.resource(path: String): InputStream? {
    val name = path.removePrefix("/")

    javaClass.classLoader.getResourceAsStream(name)?.let { return it }
    javaClass.getResourceAsStream("/$name")?.let { return it }
    javaClass.getResourceAsStream(name)?.let { return it }

    val file = runCatching {
        File(javaClass.protectionDomain.codeSource.location.toURI())
    }.getOrNull() ?: return null

    if (file.isDirectory) {
        val child = File(file, name)
        return child.takeIf { it.isFile }?.inputStream()
    }

    if (!file.isFile || file.extension.lowercase() != "jar") return null

    val jar = JarFile(file)
    val entry = jar.getJarEntry(name) ?: run {
        jar.close()
        return null
    }

    return object : FilterInputStream(jar.getInputStream(entry)) {
        override fun close() {
            try {
                super.close()
            } finally {
                jar.close()
            }
        }
    }
}
