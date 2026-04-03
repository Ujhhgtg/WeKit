import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.File

abstract class ConfigureCargoTask : DefaultTask() {
    @get:Input
    abstract val androidHome: Property<String>

    @get:Input
    abstract val minSdk: Property<Int>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun configure() {
        val home = androidHome.get()
        val minSdk  = minSdk.get()

        val clangPath = findNdkClang(home, minSdk)
            ?: error("No NDK >= 29 found in $home/ndk")
        logger.lifecycle("ConfigureCargoTask: using NDK clang at $clangPath")

        val isWindows = System.getProperty("os.name").orEmpty().contains("Windows", ignoreCase = true)
        val ext = if (isWindows) ".cmd" else ""
        val ndkBinDir = File(clangPath).parent.replace('\\', '/')

        outputFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                """
                [target.aarch64-linux-android]
                ar = "$ndkBinDir/llvm-ar"
                linker = "$ndkBinDir/aarch64-linux-android${minSdk}-clang$ext"

                [target.x86_64-linux-android]
                ar = "$ndkBinDir/llvm-ar"
                linker = "$ndkBinDir/x86_64-linux-android${minSdk}-clang$ext"

                [target.armv7-linux-androideabi]
                ar = "$ndkBinDir/llvm-ar"
                linker = "$ndkBinDir/armv7a-linux-androideabi${minSdk}-clang$ext"

                [target.i686-linux-android]
                ar = "$ndkBinDir/llvm-ar"
                linker = "$ndkBinDir/i686-linux-android${minSdk}-clang$ext"

                [env]
                CC_aarch64_linux_android = "$ndkBinDir/aarch64-linux-android${minSdk}-clang$ext"
                CXX_aarch64_linux_android = "$ndkBinDir/aarch64-linux-android${minSdk}-clang++$ext"
                AR_aarch64_linux_android = "$ndkBinDir/llvm-ar"

                CC_x86_64_linux_android = "$ndkBinDir/x86_64-linux-android${minSdk}-clang$ext"
                CXX_x86_64_linux_android = "$ndkBinDir/x86_64-linux-android${minSdk}-clang++$ext"
                AR_x86_64_linux_android = "$ndkBinDir/llvm-ar"

                CC_armv7-linux-androideabi = "$ndkBinDir/armv7a-linux-androideabi${minSdk}-clang$ext"
                CXX_armv7-linux-androideabi = "$ndkBinDir/armv7a-linux-androideabi${minSdk}-clang++$ext"
                AR_armv7-linux-androideabi = "$ndkBinDir/llvm-ar"

                CC_i686-linux-android = "$ndkBinDir/i686-linux-android${minSdk}-clang$ext"
                CXX_i686-linux-android = "$ndkBinDir/i686-linux-android${minSdk}-clang++$ext"
                AR_i686-linux-android = "$ndkBinDir/llvm-ar"
                """.trimIndent()
            )
        }
        logger.lifecycle("ConfigureCargoTask: written ${outputFile.get().asFile.absolutePath}")
    }
}

private fun findNdkClang(androidHome: String, minSdk: Int, minNdk: Int = 29): String? {
    val ndkRoot = File("$androidHome/ndk")
    if (!ndkRoot.exists()) return null
    val isWindows = System.getProperty("os.name").orEmpty().contains("Windows", ignoreCase = true)
    val ext = if (isWindows) ".cmd" else ""

    return ndkRoot.listFiles()
        ?.filter { it.isDirectory }
        ?.mapNotNull { dir ->
            val parts = dir.name.split(".").mapNotNull { it.toIntOrNull() }
            if (parts.isNotEmpty() && parts[0] >= minNdk) dir else null
        }
        ?.sortedWith(compareBy(*Array(3) { i -> { d: File -> d.name.split(".").getOrNull(i)?.toIntOrNull() ?: 0 } }))
        ?.lastOrNull()
        ?.let { ndkDir ->
            ndkDir.walkTopDown()
                .firstOrNull { it.isFile && it.name.matches(Regex(".*-linux-android${minSdk}-clang${Regex.escapeReplacement(ext)}")) }
                ?.absolutePath
        }
}
