package dev.ujhhgtg.wekit.features.items.scripting_java

import bsh.Interpreter
import java.nio.file.Path

data class JavaPluginInfo(
    val name: String,
    val author: String? = null,
    val version: String? = null,
    val updateTime: String? = null,
)

data class JavaPlugin(
    val name: String,
    val dir: Path,
    val info: JavaPluginInfo,
    val content: String,
    val interpreter: Interpreter,
) {
    companion object {
        fun parseInfoProp(content: String): JavaPluginInfo {
            val props = mutableMapOf<String, String>()
            for (line in content.lines()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith('#')) continue
                val eq = trimmed.indexOf('=')
                if (eq > 0) {
                    props[trimmed.substring(0, eq).trim()] = trimmed.substring(eq + 1).trim()
                }
            }
            return JavaPluginInfo(
                name = props["name"] ?: "unnamed",
                author = props["author"],
                version = props["version"],
                updateTime = props["updateTime"],
            )
        }
    }
}
