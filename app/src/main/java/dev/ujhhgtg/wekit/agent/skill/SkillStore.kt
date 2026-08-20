package dev.ujhhgtg.wekit.agent.skill

import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import java.io.File

/**
 * Owns the on-disk Skills library and implements the standard "Agent Skills" file convention (§ new).
 *
 * Layout — every skill is a directory holding a `SKILL.md`, plus any bundled resource files:
 * ```
 * KnownPaths.moduleData/agent/skills/<skill_name>/SKILL.md
 *                                    <skill_name>/<any bundled files…>
 * ```
 *
 * `SKILL.md` starts with YAML frontmatter carrying at least `name` and `description`, followed by the
 * Markdown body (the actual instructions):
 * ```
 * ---
 * name: pdf-forms
 * description: Fill in PDF forms and extract field data. Use when the user mentions PDF forms.
 * ---
 * # Instructions
 * …
 * ```
 *
 * Skills follow the **dynamic-discovery** model: only each enabled skill's `name` + `description` is
 * advertised (injected into the system prompt as a catalog); the model must call the `load_skill`
 * tool to pull a skill's full body on demand. This mirrors the progressive-disclosure design of the
 * skills spec and this project's `discover_tools` philosophy.
 *
 * Enabled-state is a per-skill `.disabled` marker file inside the skill directory (present = off), so
 * the whole library — content and state — lives in the filesystem with no database coupling.
 */
object SkillStore {

    private const val TAG = "SkillStore"
    const val SKILL_FILE = "SKILL.md"
    private const val DISABLED_MARKER = ".disabled"

    /** Name rules suitable for use as a directory name. */
    private val ILLEGAL_CHARS = Regex("""[/\\:*?"<>|]""")

    private val skillsRoot: File by lazy {
        File(File(KnownPaths.moduleData.toFile(), "agent"), "skills").apply { mkdirs() }
    }

    /** A parsed skill: identity from frontmatter, [body] is the Markdown after the frontmatter. */
    data class Skill(
        val name: String,
        val description: String,
        val body: String,
        val enabled: Boolean,
        /** Sibling files bundled in the skill dir (excludes SKILL.md and the marker). */
        val resources: List<String>,
    )

    sealed interface NameValidation {
        object Ok : NameValidation
        data class Invalid(val reason: String) : NameValidation
    }

    fun validateName(name: String): NameValidation {
        val t = name.trim()
        return when {
            t.isEmpty() -> NameValidation.Invalid("名称不能为空")
            t == "." || t == ".." -> NameValidation.Invalid("名称不能为 '.' 或 '..'")
            ILLEGAL_CHARS.containsMatchIn(t) -> NameValidation.Invalid("名称不能包含 / \\ : * ? \" < > | 等字符")
            else -> NameValidation.Ok
        }
    }

    /** Lists all skills (enabled and disabled), sorted by directory name. */
    fun list(): List<Skill> =
        skillsRoot.listFiles { f -> f.isDirectory }
            ?.sortedBy { it.name }
            ?.mapNotNull { parse(it) }
            ?: emptyList()

    /** Only enabled skills — the catalog advertised to the model. */
    fun enabledSkills(): List<Skill> = list().filter { it.enabled }

    /** Reads one skill by its directory name, or null if absent/unparseable. */
    fun get(dirName: String): Skill? {
        val dir = File(skillsRoot, dirName.trim())
        return if (dir.isDirectory) parse(dir) else null
    }

    /**
     * Creates or overwrites a skill. [dirName] is the folder (and the frontmatter `name`); [body] is
     * the Markdown instructions. Returns the validated directory name, or null if the name is invalid.
     */
    fun save(dirName: String, description: String, body: String): String? {
        if (validateName(dirName) != NameValidation.Ok) return null
        val name = dirName.trim()
        val dir = File(skillsRoot, name).apply { mkdirs() }
        val md = buildString {
            append("---\n")
            append("name: ").append(name).append('\n')
            append("description: ").append(description.replace("\n", " ").trim()).append('\n')
            append("---\n\n")
            append(body.trim()).append('\n')
        }
        return runCatching { File(dir, SKILL_FILE).writeText(md); name }
            .onFailure { WeLogger.e(TAG, "failed to save skill $name", it) }
            .getOrNull()
    }

    fun delete(dirName: String): Boolean =
        runCatching { File(skillsRoot, dirName.trim()).deleteRecursively() }.getOrDefault(false)

    fun setEnabled(dirName: String, enabled: Boolean) {
        val dir = File(skillsRoot, dirName.trim())
        if (!dir.isDirectory) return
        val marker = File(dir, DISABLED_MARKER)
        runCatching { if (enabled) marker.delete() else marker.writeText("") }
            .onFailure { WeLogger.w(TAG, "failed to toggle skill $dirName", it) }
    }

    /**
     * Reads a bundled resource file's text from within a skill dir (for the model to fetch referenced
     * files). Path traversal outside the skill dir is rejected.
     */
    fun readResource(dirName: String, relativePath: String): Result<String> = runCatching {
        val dir = File(skillsRoot, dirName.trim()).canonicalFile
        val target = File(dir, relativePath).canonicalFile
        require(target.path == dir.path || target.path.startsWith(dir.path + File.separator)) {
            "路径越界：$relativePath"
        }
        require(target.isFile) { "文件不存在：$relativePath" }
        target.readText()
    }

    // -- parsing ---------------------------------------------------------------

    private fun parse(dir: File): Skill? {
        val md = File(dir, SKILL_FILE)
        if (!md.isFile) return null
        val raw = runCatching { md.readText() }.getOrNull() ?: return null
        val (front, body) = splitFrontmatter(raw)
        // Fall back to the directory name / first heading when frontmatter is missing a field.
        val name = front["name"]?.takeIf { it.isNotBlank() } ?: dir.name
        val description = front["description"].orEmpty()
        val resources = dir.listFiles { f -> f.isFile && f.name != SKILL_FILE && f.name != DISABLED_MARKER }
            ?.map { it.name }?.sorted() ?: emptyList()
        return Skill(
            name = name,
            description = description,
            body = body.trim(),
            enabled = !File(dir, DISABLED_MARKER).exists(),
            resources = resources,
        )
    }

    /** Splits a `---`-delimited YAML frontmatter block from the body. Returns (fields, body). */
    private fun splitFrontmatter(raw: String): Pair<Map<String, String>, String> {
        val text = raw.replace("\r\n", "\n")
        if (!text.startsWith("---")) return emptyMap<String, String>() to text
        val end = text.indexOf("\n---", startIndex = 3)
        if (end < 0) return emptyMap<String, String>() to text
        val frontBlock = text.substring(3, end).trim('\n')
        val bodyStart = text.indexOf('\n', end + 1)
        val body = if (bodyStart < 0) "" else text.substring(bodyStart + 1)
        val fields = frontBlock.lineSequence()
            .mapNotNull { line ->
                val idx = line.indexOf(':')
                if (idx <= 0) null
                else line.substring(0, idx).trim() to line.substring(idx + 1).trim().trim('"', '\'')
            }
            .toMap()
        return fields to body
    }
}
