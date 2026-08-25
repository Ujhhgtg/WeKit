package dev.ujhhgtg.wekit.extensions.monet

import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

internal object MonetCorpus {
    private val cache = File("../../.wekit/monet-corpus").apply { mkdirs() }

    fun graph(apk: File): MonetResourceGraph {
        val cached = cache.resolve("v3-${apk.name}-${apk.length()}-${apk.lastModified()}.graph")
        if (cached.isFile) return ObjectInputStream(cached.inputStream().buffered()).use {
            it.readObject() as MonetResourceGraph
        }
        return MonetApkResourceGraphLoader.load(listOf(apk), "com.tencent.mm").also { graph ->
            ObjectOutputStream(cached.outputStream().buffered()).use { it.writeObject(graph) }
        }
    }

    fun graph(name: String, apks: List<File>): MonetResourceGraph {
        val cached = cache.resolve("v3-$name.graph")
        if (cached.isFile) return ObjectInputStream(cached.inputStream().buffered()).use {
            it.readObject() as MonetResourceGraph
        }
        return MonetApkResourceGraphLoader.load(apks, "com.tencent.mm").also { graph ->
            ObjectOutputStream(cached.outputStream().buffered()).use { it.writeObject(graph) }
        }
    }
}
