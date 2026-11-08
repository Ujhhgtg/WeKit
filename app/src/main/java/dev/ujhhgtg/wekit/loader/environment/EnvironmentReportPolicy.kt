package dev.ujhhgtg.wekit.loader.environment

/** Only fields whose meaning was established by controlled native runs are edited. */
class EnvironmentReportPolicy(
    private val modulePackage: String,
    private val moduleApk: String,
    private val moduleDataDirectory: String,
    private val loaderDataDirectory: String = moduleDataDirectory,
    private val methodFlags: () -> Map<Int, NormsgMethodCodec.Flags> = { emptyMap() },
) {
    fun isModuleMapping(path: String): Boolean {
        val clean = path.removeSuffix(" (deleted)")
        return clean == moduleApk ||
            clean.startsWith("$moduleApk!/") ||
            clean == moduleDataDirectory ||
            clean.startsWith("$moduleDataDirectory/") ||
            clean == loaderDataDirectory ||
            clean.startsWith("$loaderDataDirectory/") ||
            clean.split('/').any { it == modulePackage || it.startsWith("$modulePackage-") } ||
            clean.startsWith("/data/adb/lspd/") ||
            clean.startsWith("/data/adb/modules/zygisk_lsposed/") ||
            clean == "/memfd:wk" ||
            clean == "/memfd:wekit-inline-hook" ||
            clean == "/memfd:wekit-payload" ||
            clean.substringAfterLast('/') in
                setOf(
                    "libwekit_native.so",
                    "libwekit_zygisk.so",
                    "libwekit_loader.so",
                    "libdexsql_jni.so",
                    "liblsposed.so",
                    "liblspd.so",
                )
    }

    fun rewriteFields(message: ByteArray): ByteArray {
        val fields = ReportWire.fields(message)
        val inner by lazy { NormsgRootCodec(message) }
        var working = message
        if (fields.any { it.number == 231 && it.wire == 0 }) {
            val root = inner.decode(message)
            val cleared =
                root and 7L.inv() // KernelSU, Magisk, APatch; retain unconfirmed upper bits.
            if (cleared != root) working = inner.encode(cleared, working)
        }
        val originals = methodFlags()
        if (originals.isNotEmpty() && fields.any { it.number == 206 && it.wire == 2 }) {
            val codec = NormsgMethodCodec(message)
            val methods = ReportWire.bytes(working, 206)
            val restored =
                ReportWire.rewrite(methods) { field ->
                    val original = originals[field.number]
                    if (original == null || field.wire != 2) return@rewrite null
                    val before = methods.copyOfRange(field.data, field.end)
                    val after = codec.restore(before, original)
                    if (before.contentEquals(after)) null
                    else ReportWire.encode(field.number, after)
                }
            if (!methods.contentEquals(restored)) {
                working = ReportWire.replace(working, 206, ReportWire.encode(206, restored))
            }
        }
        return ReportWire.rewrite(working) { field ->
            when (field.number) {
                in BOOLEAN_FIELDS if field.wire == 0 && field.value == 1L -> {
                    ReportWire.encode(field.number, 0L)
                }

                86 if field.wire == 0 -> {
                    clearVerifiedBits(field, DEBUG_PROBE_MASK)
                }

                128 if field.wire == 0 -> {
                    clearVerifiedBits(field, INJECTION_PROBE_MASK)
                }

                52 if field.wire == 2 -> {
                    ByteArray(0)
                }

                36 if
                    field.wire == 2 &&
                        isModuleMapping(
                            String(working, field.data, field.end - field.data, Charsets.UTF_8),
                        )
                 -> {
                    ByteArray(0)
                }

                else -> {
                    null
                }
            }
        }
    }

    private fun clearVerifiedBits(field: ReportWire.Field, mask: Long): ByteArray? {
        // These are uint32 carriers, not booleans. Preserve all unconfirmed/time-derived bits.
        if (field.value !in 0..0xffff_ffffL) return null
        val cleared = field.value and mask.inv()
        return if (cleared == field.value) null else ReportWire.encode(field.number, cleared)
    }

    /** ac() is text. Edit it before the caller constructs/signs operation 62's message. */
    fun rewriteClientCheckHook(value: String, requestedSeparator: String): String {
        if (value.isEmpty()) return value
        // The audited native code formats the JNI string handle as an ostream pointer.
        // Only accept one consistent separator before a path; ambiguous formats pass through.
        val pointerSeparators = POINTER_SEPARATOR.findAll(value).map { it.value }.toSet()
        val separator =
            when {
                pointerSeparators.size == 1 -> {
                    pointerSeparators.single()
                }

                pointerSeparators.isNotEmpty() -> {
                    return value
                }

                requestedSeparator.isNotEmpty() && value.contains(requestedSeparator) -> {
                    requestedSeparator
                }

                else -> {
                    return if (isModuleMapping(value)) "" else value
                }
            }
        val paths = value.split(separator)
        if (paths.any { !it.startsWith('/') && !it.startsWith('[') }) return value
        return paths.filterNot(::isModuleMapping).joinToString(separator)
    }

    companion object {
        // su-existence, debugger-connected, ADB, ActivityManager.isUserAMonkey().
        private val BOOLEAN_FIELDS = setOf(2, 3, 53, 54)
        private const val DEBUG_PROBE_MASK = 0x54L
        private const val INJECTION_PROBE_MASK = 0x50L
        private val POINTER_SEPARATOR = Regex("0x[0-9a-fA-F]+(?=[/\\[])")
    }
}
