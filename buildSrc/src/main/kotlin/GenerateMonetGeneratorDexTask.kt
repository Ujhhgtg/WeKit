import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.util.zip.ZipFile

/** Shrinks the isolated Monet engine and its runtime dependencies into one extension DEX. */
abstract class GenerateMonetGeneratorDexTask : DefaultTask() {

    @get:InputFile
    abstract val extensionAar: RegularFileProperty

    @get:InputFiles
    @get:Classpath
    abstract val programJars: ConfigurableFileCollection

    @get:InputFiles
    @get:Classpath
    abstract val libraryJars: ConfigurableFileCollection

    @get:InputFiles
    @get:Classpath
    abstract val r8Classpath: ConfigurableFileCollection

    @get:InputFile
    abstract val rulesFile: RegularFileProperty

    @get:Input
    abstract val androidJar: Property<String>

    @get:Input
    abstract val minApi: Property<Int>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        require(out.mkdirs()) { "Could not create Monet extension DEX output: $out" }

        val extractionDir = temporaryDir.resolve("aar-classes").apply {
            deleteRecursively()
            require(mkdirs()) { "Could not create AAR extraction directory: $this" }
        }
        val extensionClasses = extractClassesJar(
            extensionAar.get().asFile,
            extractionDir.resolve("extension.jar"),
        )
        val r8 = r8Classpath.singleFile
        val args = mutableListOf(
            "java", "-cp", r8.absolutePath, "com.android.tools.r8.R8",
            "--release",
            "--min-api", minApi.get().toString(),
            "--lib", androidJar.get(),
            "--pg-conf", rulesFile.get().asFile.absolutePath,
            "--output", out.absolutePath,
        )
        for (jar in libraryJars.files.sortedBy { it.absolutePath }) {
            args += listOf("--lib", jar.absolutePath)
        }
        args += extensionClasses.absolutePath
        for (jar in programJars.files.sortedBy { it.absolutePath }) {
            args += jar.absolutePath
        }

        val process = ProcessBuilder(args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        val dexFiles = out.listFiles()
            ?.filter { it.isFile && it.name.matches(Regex("classes(?:[0-9]+)?\\.dex")) }
            .orEmpty()
        if (exitCode != 0 || dexFiles.size != 1 || dexFiles.singleOrNull()?.name != "classes.dex") {
            throw GradleException(
                "R8 failed to produce exactly one Monet extension classes.dex (exit=$exitCode, " +
                    "dex=${dexFiles.map { it.name }}):\n$output",
            )
        }
        logger.lifecycle("Monet generator DEX: {} ({} bytes)", dexFiles.single(), dexFiles.single().length())
    }

    private fun extractClassesJar(aar: java.io.File, destination: java.io.File): java.io.File {
        ZipFile(aar).use { zip ->
            val entry = zip.getEntry("classes.jar")
                ?: throw GradleException("AAR has no classes.jar: $aar")
            zip.getInputStream(entry).use { input ->
                destination.outputStream().use(input::copyTo)
            }
        }
        return destination
    }
}
