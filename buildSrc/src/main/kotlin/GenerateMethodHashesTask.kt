import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.security.MessageDigest

abstract class GenerateMethodHashesTask : DefaultTask() {
    @get:InputDirectory
    abstract val sourceDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Input
    abstract val namespace: Property<String>

    @TaskAction
    fun generate() {
        val srcDir = sourceDir.get().asFile
        val outDir = outputDir.get().asFile
        val outputFile = outDir.resolve("${namespace.get().replace(".", "/")}/dexkit/cache/GeneratedMethodHashes.kt")

        val hashMap = mutableMapOf<String, String>()
        srcDir.walk().filter { it.isFile && it.extension == "kt" && it.readText().contains("IResolvesDex") }.forEach { file ->
            val content = file.readText()
            val packageName = Regex("""package\s+([\w.]+)""").find(content)?.groupValues?.get(1)
            val className = Regex("""(?:class|object)\s+(\w+)""").find(content)?.groupValues?.get(1) ?: return@forEach
            val fullClassName = if (packageName != null) "$packageName.$className" else className

            val resolveDexMatch = Regex("""override\s+fun\s+resolveDex\s*\(""").find(content)
            if (resolveDexMatch != null) {
                val start = content.indexOf('{', resolveDexMatch.range.last)
                if (start != -1) {
                    var count = 0
                    for (i in start until content.length) {
                        if (content[i] == '{') count++ else if (content[i] == '}') count--
                        if (count == 0) {
                            val body = content.substring(start, i + 1)
                            val hash = MessageDigest.getInstance("MD5").digest(body.toByteArray()).joinToString("") { "%02x".format(it) }
                            hashMap[fullClassName] = hash
                            break
                        }
                    }
                }
            }
        }

        outputFile.parentFile.mkdirs()
        outputFile.writeText(
            """
            package ${namespace.get()}.dexkit.cache

            object GeneratedMethodHashes {
                val HASHES = mapOf(${hashMap.entries.sortedBy { it.key }.joinToString(", \n") { "\"${it.key}\" to \"${it.value}\"" }})
            }
        """.trimIndent()
        )
    }
}
