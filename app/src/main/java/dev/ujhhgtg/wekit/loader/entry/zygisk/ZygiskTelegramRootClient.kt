package dev.ujhhgtg.wekit.loader.entry.zygisk

import android.os.ParcelFileDescriptor
import dev.ujhhgtg.wekit.features.items.chat.panel.sticker.TelegramInstalledStickerSet
import dev.ujhhgtg.wekit.features.items.chat.panel.sticker.TelegramStickerDatabase
import java.io.File
import java.util.UUID
import kotlin.io.path.isRegularFile

/**
 * Runtime client for the root companion retained by the Zygisk bootstrap.
 * The companion only exposes Telegram instance discovery and cache4.db
 * snapshots; parsing remains in the injected app process.
 */
object ZygiskTelegramRootClient {

    fun isAvailable(): Boolean = ZygiskEntry.hasTelegramRootCompanion()

    fun discoverInstances(): Result<List<String>> = runCatching {
        check(isAvailable()) { "Zygisk Root Companion 不可用，请完全结束并重新启动微信" }
        ZygiskEntry.listTelegramRootInstances()
            .distinct()
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
            .also { require(it.isNotEmpty()) { "未找到可读取的 Telegram 实例" } }
    }

    fun readInstalledSets(
        cacheDir: File,
        packageName: String,
    ): Result<List<TelegramInstalledStickerSet>> = runCatching {
        check(isAvailable()) { "Zygisk Root Companion 不可用，请完全结束并重新启动微信" }
        val sessionDir = File(cacheDir, "telegram-root-import-${UUID.randomUUID()}")
        require(sessionDir.mkdirs()) { "无法创建数据库临时目录" }
        try {
            val database = File(sessionDir, "cache4.db")
            val wal = File(sessionDir, "cache4.db-wal")
            val shm = File(sessionDir, "cache4.db-shm")
            val sidecars = ParcelFileDescriptor.open(
                database,
                ParcelFileDescriptor.MODE_CREATE or
                        ParcelFileDescriptor.MODE_TRUNCATE or
                        ParcelFileDescriptor.MODE_READ_WRITE,
            ).use { databaseFd ->
                ParcelFileDescriptor.open(
                    wal,
                    ParcelFileDescriptor.MODE_CREATE or
                            ParcelFileDescriptor.MODE_TRUNCATE or
                            ParcelFileDescriptor.MODE_READ_WRITE,
                ).use { walFd ->
                    ParcelFileDescriptor.open(
                        shm,
                        ParcelFileDescriptor.MODE_CREATE or
                                ParcelFileDescriptor.MODE_TRUNCATE or
                                ParcelFileDescriptor.MODE_READ_WRITE,
                    ).use { shmFd ->
                        ZygiskEntry.copyTelegramRootDatabaseSnapshot(
                            packageName = packageName,
                            databaseFd = databaseFd.fd,
                            walFd = walFd.fd,
                            shmFd = shmFd.fd,
                        )
                    }
                }
            }
            if (sidecars and WAL_PRESENT == 0) wal.delete()
            if (sidecars and SHM_PRESENT == 0) shm.delete()
            require(database.toPath().isRegularFile() && database.length() > 0L) {
                "Telegram 数据库快照为空"
            }
            TelegramStickerDatabase.readInstalledSets(database.toPath()).getOrThrow()
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        } finally {
            sessionDir.deleteRecursively()
        }
    }

    private const val WAL_PRESENT = 1
    private const val SHM_PRESENT = 2
}
