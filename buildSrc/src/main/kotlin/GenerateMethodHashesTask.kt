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

        val hashMap = srcDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .mapNotNull(::scanDexResolverSource)
            .associate { source ->
                val body = source.blocks.joinToString("\n") { it.text }
                source.qualifiedClassName to md5(body)
            }

        outputFile.parentFile.mkdirs()
        outputFile.writeText(
            """
            package ${namespace.get()}.dexkit.cache

            object GeneratedMethodHashes {
                val HASHES = mapOf(${hashMap.entries.sortedBy { it.key }.joinToString(", \n") { "\"${it.key}\" to \"${it.value}\"" }})
            }
            """.trimIndent(),
        )
    }
}

private fun md5(input: String): String =
    MessageDigest.getInstance("MD5").digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
