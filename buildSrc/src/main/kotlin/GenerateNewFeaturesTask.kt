import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Collects the features whose source file entered the repository within the last [windowDays],
 * measured backwards from the HEAD commit's date (not the build's wall clock, so the same commit
 * always produces the same output).
 *
 * Feature discovery supplies a stable source key for each feature, so "when was this file added"
 * is a faithful stand-in for "when was this feature written". Renames don't count as additions —
 * git's rename detection reports them as `R`, and only `A` entries are collected.
 *
 * Only source keys and addition times are collected here; joining those keys to discovered
 * features and ordering the resulting UI list are left to the app.
 */
abstract class GenerateNewFeaturesTask : DefaultTask() {
    @get:InputDirectory
    abstract val sourceDir: DirectoryProperty

    @get:Internal
    abstract val repoDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Input
    abstract val namespace: Property<String>

    @get:Input
    abstract val windowDays: Property<Int>

    /** HEAD's hash. Not read by the task — it exists so a new commit invalidates the output. */
    @get:Input
    abstract val gitHead: Property<String>

    @TaskAction
    fun generate() {
        val entries = runCatching { collectEntries() }
            .onFailure { logger.warn("Failed to collect new features from git: ${it.message}") }
            .getOrDefault(emptyList())

        val outputFile = outputDir.get().asFile
            .resolve("${namespace.get().replace(".", "/")}/features/core/NewFeatures.kt")
        outputFile.parentFile.mkdirs()
        outputFile.writeText(
            renderNewFeaturesSource(
                namespace = namespace.get(),
                windowDays = windowDays.get(),
                entries = entries,
            ),
        )
    }

    // -----------------------------------------------------------------------

    /** Feature technical ID to the epoch-second its source file was first added, newest first. */
    private fun collectEntries(): List<Pair<String, Long>> {
        // A shallow clone squashes all of history into one synthetic commit, which would make
        // every feature look brand new. Better to ship an empty list than a bogus full one.
        if (git("rev-parse", "--is-shallow-repository")?.trim() == "true") {
            logger.warn("Shallow clone detected; the 新功能 list will be empty.")
            return emptyList()
        }

        val headEpoch = git("log", "-1", "--format=%ct")?.trim()?.toLongOrNull() ?: run {
            logger.warn("Could not read HEAD's commit date; the 新功能 list will be empty.")
            return emptyList()
        }
        val cutoff = headEpoch - windowDays.get() * 24L * 60L * 60L
        val cutoffIso = DateTimeFormatter.ISO_OFFSET_DATE_TIME
            .format(Instant.ofEpochSecond(cutoff).atOffset(ZoneOffset.UTC))

        val repo = repoDir.get().asFile.canonicalFile
        val source = sourceDir.get().asFile.canonicalFile
        val pathspec = source.relativeToOrNull(repo)?.path ?: source.path

        val log = git(
            "log", "--since=$cutoffIso", "--diff-filter=A", "--name-only", "--format=%ct",
            "--", pathspec,
        ) ?: return emptyList()

        // `git log` walks newest to oldest, so overwriting on every hit leaves the *oldest*
        // addition — the one that matters when a file was deleted and later restored.
        val addedAt = mutableMapOf<String, Long>()
        var timestamp = 0L
        for (line in log.lineSequence()) {
            if (line.isBlank()) continue
            val asEpoch = if ('/' in line) null else line.toLongOrNull()
            if (asEpoch != null) timestamp = asEpoch else if (line.endsWith(".kt")) addedAt[line] = timestamp
        }

        return addedAt.mapNotNull { (path, addedEpoch) ->
            val file = repo.resolve(path)
            if (!file.isFile) return@mapNotNull null
            newFeatureSourceKey(path, pathspec)?.let { it to addedEpoch }
        }.sortedWith(compareByDescending<Pair<String, Long>> { it.second }.thenBy { it.first })
    }

    private fun git(vararg args: String): String? = runCatching {
        val process = ProcessBuilder(listOf("git", *args))
            .directory(repoDir.get().asFile)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        if (process.exitValue() == 0) output else null
    }.getOrNull()

}

internal fun newFeatureSourceKey(path: String, sourcePathspec: String): String? {
    val normalizedPath = path.replace('\\', '/')
    val prefix = sourcePathspec.replace('\\', '/').trimEnd('/') + "/"
    return normalizedPath
        .takeIf { it.startsWith(prefix) && it.endsWith(".kt") }
        ?.removePrefix(prefix)
}

internal fun renderNewFeaturesSource(
    namespace: String,
    windowDays: Int,
    entries: List<Pair<String, Long>>,
): String {
    val body = entries.joinToString(separator = "\n") { (sourceKey, epoch) ->
        "        \"${sourceKey.escapeForKotlin()}\" to ${epoch}L,"
    }
    return buildString {
        append("// Generated by GenerateNewFeaturesTask. Do not edit manually.\n")
        append("package $namespace.features.core\n\n")
        append("object NewFeatures {\n")
        append("    const val WINDOW_DAYS: Int = $windowDays\n\n")
        append("    /** 源文件 key -> 其首次加入仓库的 epoch 秒. */\n")
        append("    val ADDED_AT_BY_SOURCE_KEY: Map<String, Long> = mapOf(\n")
        if (body.isNotEmpty()) append(body).append('\n')
        append("    )\n")
        append("}\n")
    }
}

private fun String.escapeForKotlin(): String = this
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("$", "\${'$'}")
    .replace("\n", "\\n")
