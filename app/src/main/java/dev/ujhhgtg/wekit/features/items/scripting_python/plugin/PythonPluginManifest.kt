package dev.ujhhgtg.wekit.features.items.scripting_python.plugin

import kotlinx.serialization.Serializable

@Serializable
data class PythonPluginManifest(
    val schema: Int,
    val id: String,
    val name: String,
    val version: String,
    val author: String = "",
    val description: String = "",
    val entry: String = "main",
    val minWeKitVersionCode: Int = 0,
    val processes: List<String> = listOf("main"),
)
