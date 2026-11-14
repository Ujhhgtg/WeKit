import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Adds module installer files before AGP packages and signs the APK. */
@CacheableTask
abstract class GenerateZygiskResourcesTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val templateDir: DirectoryProperty

    @get:Input abstract val versionCode: Property<Int>
    @get:Input abstract val versionName: Property<String>
    @get:Input abstract val variantName: Property<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val source = templateDir.get().asFile
        val output = outputDir.get().asFile
        output.deleteRecursively()
        source.walkTopDown().filter { it.isFile }.forEach { file ->
            val destination = output.resolve(file.relativeTo(source))
            destination.parentFile.mkdirs()
            var text = file.readText().replace("\r\n", "\n")
            if (file.name == "module.prop") {
                text = text.replace("@VERSION_CODE@", versionCode.get().toString())
                    .replace("@VERSION_NAME@", versionName.get())
                    .replace("@VARIANT@", variantName.get())
            }
            destination.writeText(text)
        }
    }
}
