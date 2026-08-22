package dev.ujhhgtg.wekit.extensions.monet

import dev.ujhhgtg.wekit.extensions.monet.api.MonetGenerationEvent
import dev.ujhhgtg.wekit.extensions.monet.api.MonetGenerationListener
import dev.ujhhgtg.wekit.extensions.monet.api.MonetGenerationRequest
import dev.ujhhgtg.wekit.extensions.monet.api.MonetGenerationResult
import dev.ujhhgtg.wekit.extensions.monet.api.MonetGenerationStage
import dev.ujhhgtg.wekit.extensions.monet.api.MonetGeneratorApiV1
import dev.ujhhgtg.wekit.extensions.monet.api.MonetLogLevel
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class MonetGeneratorEntrypointV1 : MonetGeneratorApiV1 {
    override fun generate(
        request: MonetGenerationRequest,
        listener: MonetGenerationListener,
    ): MonetGenerationResult {
        require(request.sdkInt >= 31) { "Android 12 or newer is required" }
        val temporaryOutput = File(request.outputZip.parentFile, request.outputZip.name + ".tmp")
        try {
            listener.onEvent(MonetGenerationEvent.Progress(MonetGenerationStage.PREPARING))
            validatePayload(request)
            if (request.workDir.exists()) {
                require(request.workDir.deleteRecursively()) {
                    "Could not clear Monet work directory: ${request.workDir}"
                }
            }
            require(request.workDir.mkdirs() || request.workDir.isDirectory) {
                "Could not create Monet work directory: ${request.workDir}"
            }

            val templateName = if (request.sdkInt >= 34) {
                "template_api34.apk"
            } else {
                "template_api31.apk"
            }
            val minSdk = if (request.sdkInt >= 34) 34 else 31
            val template = request.payloadDir.resolve(templateName)
            val unsignedApk = request.workDir.resolve("overlay-unsigned.apk")
            val signedApk = request.workDir.resolve("overlay-signed.apk")
            val log = { level: MonetLogLevel, message: String, error: Throwable? ->
                listener.onEvent(MonetGenerationEvent.Log(level, message, error))
            }

            listener.onEvent(
                MonetGenerationEvent.Progress(MonetGenerationStage.BUILDING_OVERLAY),
            )
            val build = MonetOverlayBuilder(
                request,
                MonetTables.load(request.payloadDir),
                template,
                log,
            ).build(unsignedApk)
            listener.onEvent(MonetGenerationEvent.Progress(MonetGenerationStage.SIGNING))
            MonetApkSigner.sign(unsignedApk, signedApk, minSdk)
            listener.onEvent(MonetGenerationEvent.Progress(MonetGenerationStage.PACKAGING))
            MonetModulePackager(request).pack(signedApk, temporaryOutput)
            require(temporaryOutput.isFile) { "Monet module archive was not produced" }
            Files.move(
                temporaryOutput.toPath(),
                request.outputZip.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            return MonetGenerationResult(
                request.outputZip,
                build.kept,
                build.pruned,
                build.added,
            )
        } finally {
            temporaryOutput.delete()
            request.workDir.deleteRecursively()
        }
    }

    private fun validatePayload(request: MonetGenerationRequest) {
        for (name in REQUIRED_PAYLOADS) {
            require(request.payloadDir.resolve(name).isFile) {
                "Missing Monet payload: $name"
            }
        }
    }

    private companion object {
        val REQUIRED_PAYLOADS = listOf(
            "template_api31.apk",
            "template_api34.apk",
            "monet_tables.json",
            "customize.sh",
            "update-binary",
            "updater-script",
        )
    }
}
