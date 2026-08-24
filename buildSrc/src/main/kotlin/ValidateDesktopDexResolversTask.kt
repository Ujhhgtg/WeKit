import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction

abstract class ValidateDesktopDexResolversTask : DefaultTask() {
    @get:InputDirectory
    abstract val sourceDir: DirectoryProperty

    @get:Optional
    @get:Input
    abstract val includePaths: ListProperty<String>

    @TaskAction
    fun validate() {
        val paths = includePaths.getOrElse(emptyList())
        val violations = sourceDir.asFileTree.matching { include("**/*.kt") }
            .files
            .sortedBy { it.path }
            .filter { paths.isEmpty() || paths.any(it.path::endsWith) }
            .flatMap(::scanDexResolverSources)
            .flatMap(::findDesktopIncompatibleAccesses)
        if (violations.isNotEmpty()) error(violations.joinToString("\n") { it.render() })
    }
}
