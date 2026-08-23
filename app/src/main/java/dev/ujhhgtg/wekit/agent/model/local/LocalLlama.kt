package dev.ujhhgtg.wekit.agent.model.local

object LocalLlama {
    const val PROVIDER_ID = "local-llama"
    val BACKENDS = listOf("auto", "cpu", "vulkan", "opencl")
}
