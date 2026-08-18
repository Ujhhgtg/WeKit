import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/**
 * Compiles the script-deps extension pack: one unobfuscated DEX containing
 * fastjson2, okhttp and their transitive dependencies (kotlin-stdlib etc.).
 *
 * The output DEX is published as a versioned Release asset by xtask
 * (`cargo xtask extensions`), never bundled into the module APK.
 */
abstract class GenerateScriptDepsDexTask : DefaultTask() {

    @get:InputFiles
    @get:Classpath
    abstract val jars: ConfigurableFileCollection

    /** Classpath holding com.android.tools.r8.D8 (the R8/D8 fat jar). */
    @get:InputFiles
    @get:Classpath
    abstract val r8Classpath: ConfigurableFileCollection

    /** Path to android.jar of the compile SDK, passed to d8 as --lib. */
    @get:Input
    abstract val androidJar: Property<String>

    @get:Input
    abstract val minApi: Property<Int>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val out = outputDir.get().asFile.apply { mkdirs() }
        val dex = out.resolve("classes.dex")
        dex.delete()

        val r8 = r8Classpath.singleFile
        val args = mutableListOf(
            "java", "-cp", r8.absolutePath, "com.android.tools.r8.D8",
            "--release",
            "--min-api", minApi.get().toString(),
            "--lib", androidJar.get(),
            "--output", out.absolutePath,
        )
        for (jar in jars.files) args.add(jar.absolutePath)

        val process = ProcessBuilder(args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (process.waitFor() != 0 || !dex.isFile) {
            throw GradleException("d8 failed to produce script-deps DEX:\n$output")
        }
        logger.lifecycle("script-deps DEX: {} ({} bytes)", dex, dex.length())
    }
}
