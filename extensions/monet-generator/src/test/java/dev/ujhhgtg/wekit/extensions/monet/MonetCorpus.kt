package dev.ujhhgtg.wekit.extensions.monet

import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

internal object MonetCorpus {
    private val cache = File("../../.wekit/monet-corpus").apply { mkdirs() }

    fun graph(apk: File): MonetResourceGraph {
        val cached = cache.resolve("v9-${apk.name}-${apk.length()}-${apk.lastModified()}.graph")
        if (cached.isFile) return ObjectInputStream(cached.inputStream().buffered()).use {
            it.readObject() as MonetResourceGraph
        }
        return MonetApkResourceGraphLoader.load(listOf(apk), "com.tencent.mm").also { graph ->
            ObjectOutputStream(cached.outputStream().buffered()).use { it.writeObject(graph) }
        }
    }

    fun graph(name: String, apks: List<File>): MonetResourceGraph {
        val inputs = if (name == "play3084" && apks.isEmpty()) {
            cache.resolve("play3084-resources.txt").readLines().map { File("../..").resolve(it) }
        } else apks
        val cached = cache.resolve("v11-$name.graph")
        if (cached.isFile) return ObjectInputStream(cached.inputStream().buffered()).use {
            it.readObject() as MonetResourceGraph
        }
        return MonetApkResourceGraphLoader.load(inputs, "com.tencent.mm").also { graph ->
            ObjectOutputStream(cached.outputStream().buffered()).use { it.writeObject(graph) }
        }
    }
}
