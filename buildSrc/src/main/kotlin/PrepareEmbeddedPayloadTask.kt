import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Stages the private release payload as an asset before AGP signs the bootstrap APK. */
@CacheableTask
abstract class PrepareEmbeddedPayloadTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val appApk: RegularFileProperty

    @get:Input abstract val requireProductionSigning: Property<Boolean>

    @get:Input abstract val productionSigningConfigured: Property<Boolean>

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun prepare() {
        check(!requireProductionSigning.get() || productionSigningConfigured.get()) {
            "bootstrap-local requires the configured release keystore"
        }
        val source = appApk.get().asFile
        require(source.isFile && source.length() in 1..MAX_APK_SIZE) {
            "invalid embedded payload APK: $source"
        }
        val destination = outputDir.get().asFile.resolve("wekit/payload.apk")
        destination.parentFile.mkdirs()
        source.copyTo(destination, overwrite = true)
        check(destination.length() == source.length()) {
            "embedded payload copy is incomplete: $destination"
        }
    }

    private companion object {
        const val MAX_APK_SIZE = 128L * 1024 * 1024
    }
}
