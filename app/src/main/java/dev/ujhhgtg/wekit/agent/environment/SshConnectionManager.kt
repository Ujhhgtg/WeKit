package dev.ujhhgtg.wekit.agent.environment

import com.jcraft.jsch.Channel
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpATTRS
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.UserInfo
import dev.ujhhgtg.wekit.agent.ssh.SshAuthenticationException
import dev.ujhhgtg.wekit.agent.ssh.SshCredentials
import dev.ujhhgtg.wekit.agent.ssh.SshEndpoint
import dev.ujhhgtg.wekit.agent.ssh.SshHostKey
import dev.ujhhgtg.wekit.agent.ssh.SshHostKeyDecision
import dev.ujhhgtg.wekit.agent.ssh.SshHostKeyException
import dev.ujhhgtg.wekit.agent.ssh.SshHostKeyVerifier
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class SshConfiguration(
    val host: String,
    val port: Int,
    val username: String,
    val confirmedHostKey: SshHostKey?,
) {
    init {
        require(host.isNotBlank() && !host.any(Char::isWhitespace)) { "SSH host is required" }
        require(port in 1..65535) { "SSH port is invalid" }
        require(username.isNotBlank() && !username.any { it == '\u0000' || it == '\n' || it == '\r' }) { "SSH username is invalid" }
        confirmedHostKey?.let {
            require(it.algorithm.isNotBlank() && it.fingerprint.startsWith("SHA256:")) { "SSH host-key pin is invalid" }
        }
    }
}

data class SshExecResponse(
    val stdout: ByteArray,
    val stderr: ByteArray,
    val exitCode: Int?,
    val timedOut: Boolean,
)

data class SshRemoteMetadata(val size: Long, val modifiedSeconds: Int, val permissions: Int)
data class SshRemoteFile(val bytes: ByteArray, val metadata: SshRemoteMetadata?)

class SshIndeterminateExecutionException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

class SshConcurrentEditException : IllegalStateException("remote file changed while it was being edited")

class SshConnectionManager(
    private val configuration: SshConfiguration,
    private val credentials: SshCredentials,
    private val dispatcher: ExecutorCoroutineDispatcher = Executors.newFixedThreadPool(4) { runnable ->
        Thread(runnable, "weagent-ssh").apply { isDaemon = true }
    }.asCoroutineDispatcher(),
) {
    private val connectionMutex = Mutex()
    private var session: Session? = null
    private var connectedAtNanos = 0L
    private var lastUsedNanos = 0L
    private val lifecycleLock = Any()
    private var retainedHandles = 0
    private var closeRequested = false
    private val closeCompleted = AtomicBoolean()

    suspend fun execute(command: String, timeoutMillis: Long): SshExecResponse {
        require(timeoutMillis in 1..NativeBackend.MAX_TIMEOUT_MILLIS)
        val channel = openChannel("exec") as ChannelExec
        val stdout = channel.inputStream
        val stderr = channel.errStream
        channel.setCommand(command)
        var submitted = false
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        try {
            submitted = true
            runBlockingIo { channel.connect(CHANNEL_CONNECT_TIMEOUT_MS) }
            while (!channel.isClosed) {
                coroutineContext.ensureActive()
                drain(stdout, out)
                drain(stderr, err)
                if (System.nanoTime() >= deadline) {
                    channel.disconnect()
                    return SshExecResponse(out.toByteArray(), err.toByteArray(), null, true)
                }
                delay(IO_POLL_MS)
            }
            drain(stdout, out)
            drain(stderr, err)
            val exit = channel.exitStatus.takeIf { it >= 0 }
            if (exit == null) {
                throw SshIndeterminateExecutionException("SSH server supplied no exit status after command submission")
            }
            return SshExecResponse(out.toByteArray(), err.toByteArray(), exit, false)
        } catch (error: CancellationException) {
            channel.disconnect()
            throw error
        } catch (error: SshIndeterminateExecutionException) {
            throw error
        } catch (error: Throwable) {
            if (submitted) throw SshIndeterminateExecutionException(
                "SSH command state is unknown after submission; it was not replayed", error,
            )
            throw error
        } finally {
            channel.disconnect()
            markUsed()
        }
    }

    suspend fun readFile(path: String, maxBytes: Long): SshRemoteFile = withSftp { sftp ->
        val attributes = try {
            sftp.lstat(path)
        } catch (error: SftpException) {
            if (error.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) return@withSftp SshRemoteFile(ByteArray(0), null)
            throw error
        }
        require(attributes.isReg) { "not a regular file: $path" }
        require(attributes.size <= maxBytes) { "file exceeds $maxBytes bytes" }
        val bytes = sftp.get(path).use(InputStream::readBytes)
        SshRemoteFile(bytes, attributes.toMetadata())
    }

    suspend fun atomicWrite(
        path: String,
        expected: SshRemoteFile,
        updated: ByteArray,
        defaultPermissions: Int = 0b110_000_000,
    ) = withSftp(WRITE_TIMEOUT_MS) { sftp ->
        val current = try {
            val attributes = sftp.lstat(path)
            require(attributes.isReg) { "not a regular file: $path" }
            SshRemoteFile(sftp.get(path).use(InputStream::readBytes), attributes.toMetadata())
        } catch (error: SftpException) {
            if (error.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) SshRemoteFile(ByteArray(0), null) else throw error
        }
        if (current.metadata != expected.metadata || !current.bytes.contentEquals(expected.bytes)) {
            throw SshConcurrentEditException()
        }
        val slash = path.lastIndexOf('/')
        val parent = if (slash < 0) "." else path.substring(0, slash).ifEmpty { "/" }
        val temporary = "$parent/.weagent-edit-${java.util.UUID.randomUUID()}.tmp"
        try {
            sftp.setUseWriteFlushWorkaround(true)
            sftp.put(ByteArrayInputStream(updated), temporary, ChannelSftp.OVERWRITE)
            sftp.chmod(expected.metadata?.permissions ?: defaultPermissions, temporary)
            val beforeCommit = try {
                val attributes = sftp.lstat(path)
                SshRemoteFile(sftp.get(path).use(InputStream::readBytes), attributes.toMetadata())
            } catch (error: SftpException) {
                if (error.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) SshRemoteFile(ByteArray(0), null) else throw error
            }
            if (beforeCommit.metadata != expected.metadata || !beforeCommit.bytes.contentEquals(expected.bytes)) {
                throw SshConcurrentEditException()
            }
            sftp.rename(temporary, path)
        } finally {
            runCatching { sftp.rm(temporary) }
        }
    }

    suspend fun upload(path: String, bytes: ByteArray, permissions: Int = 0b111_101_101) = withSftp(WRITE_TIMEOUT_MS) { sftp ->
        sftp.setUseWriteFlushWorkaround(true)
        sftp.put(ByteArrayInputStream(bytes), path, ChannelSftp.OVERWRITE)
        sftp.chmod(permissions, path)
    }

    suspend fun homeDirectory(): String = withSftp { it.home }

    suspend fun openTerminal(
        command: String,
        environmentVariables: Map<String, String>,
        cols: Int,
        rows: Int,
    ): SshTerminalConnection {
        retainHandle()
        val channel = try {
            openChannel("shell") as ChannelShell
        } catch (error: Throwable) {
            releaseHandle()
            throw error
        }
        environmentVariables.forEach(channel::setEnv)
        channel.setPty(true)
        channel.setPtyType("xterm-256color", cols, rows, 0, 0)
        val input = channel.inputStream
        val output = channel.outputStream
        try {
            runBlockingIo { channel.connect(CHANNEL_CONNECT_TIMEOUT_MS) }
            if (command.isNotBlank()) {
                runBlockingIo {
                    output.write("$command\n".toByteArray())
                    output.flush()
                }
            }
            return SshTerminalConnection(
                readBlock = { maximum -> readTerminal(channel, input, maximum) },
                writeBlock = { bytes -> runBlockingIo { output.write(bytes); output.flush() } },
                resizeBlock = { width, height -> runBlockingIo { channel.setPtySize(width, height, 0, 0) } },
                waitBlock = { waitForChannel(channel) },
                killBlock = { channel.disconnect() },
                closeBlock = {
                    channel.disconnect()
                    markUsed()
                    releaseHandle()
                },
            )
        } catch (error: Throwable) {
            channel.disconnect()
            releaseHandle()
            throw error
        }
    }

    suspend fun openReverseForward(localPort: Int): SshReverseForward {
        require(localPort in 1..65535)
        retainHandle()
        var handedOff = false
        try {
            val active = ensureConnected()
            var lastError: Throwable? = null
            repeat(REVERSE_FORWARD_ATTEMPTS) {
                val remotePort = (REVERSE_PORT_MIN..REVERSE_PORT_MAX).random()
                try {
                    runBlockingIo {
                        active.setPortForwardingR("127.0.0.1", remotePort, "127.0.0.1", localPort)
                    }
                    handedOff = true
                    return SshReverseForward(remotePort) {
                        runCatching { runBlockingIo { active.delPortForwardingR("127.0.0.1", remotePort) } }
                        markUsed()
                        releaseHandle()
                    }
                } catch (error: JSchException) {
                    lastError = error
                }
            }
            throw IllegalStateException("cannot allocate remote loopback forwarding port", lastError)
        } finally {
            if (!handedOff) releaseHandle()
        }
    }

    suspend fun close() {
        val closeNow = synchronized(lifecycleLock) {
            closeRequested = true
            retainedHandles == 0
        }
        if (closeNow) completeClose()
    }

    private suspend fun completeClose() {
        if (!closeCompleted.compareAndSet(false, true)) return
        connectionMutex.withLock {
            session?.disconnect()
            session = null
        }
        dispatcher.close()
    }

    private suspend fun <T> withSftp(timeoutMillis: Long = SFTP_TIMEOUT_MS, action: (ChannelSftp) -> T): T {
        val channel = openChannel("sftp") as ChannelSftp
        try {
            runBlockingIo { channel.connect(CHANNEL_CONNECT_TIMEOUT_MS) }
            return withTimeout(timeoutMillis) { runBlockingIo { action(channel) } }
        } finally {
            channel.disconnect()
            markUsed()
        }
    }

    private suspend fun openChannel(type: String): Channel {
        var firstError: Throwable? = null
        repeat(2) { attempt ->
            val active = ensureConnected()
            try {
                return runBlockingIo {
                    active.sendKeepAliveMsg()
                    active.openChannel(type)
                }
            } catch (error: Throwable) {
                if (attempt == 0) {
                    firstError = error
                    invalidate(active)
                } else {
                    firstError?.let(error::addSuppressed)
                    throw error
                }
            }
        }
        error("unreachable")
    }

    private suspend fun ensureConnected(): Session = connectionMutex.withLock {
        check(!synchronized(lifecycleLock) { closeRequested }) { "SSH connection manager is closed" }
        val now = System.nanoTime()
        session?.takeIf {
            it.isConnected && now - lastUsedNanos < IDLE_TIMEOUT_NANOS && now - connectedAtNanos < LIFETIME_NANOS
        }?.let { return@withLock it }
        session?.disconnect()

        val verifier = SshHostKeyVerifier(configuration.confirmedHostKey)
        val repository = StrictHostKeyRepository(
            verifier,
            SshEndpoint(configuration.host, configuration.port, configuration.username),
        )
        val jsch = JSch().apply {
            setHostKeyRepository(repository)
            if (credentials is SshCredentials.PrivateKey) {
                addIdentity(
                    "weagent",
                    credentials.privateKey.toByteArray(),
                    null,
                    credentials.passphrase?.toByteArray(),
                )
            }
        }
        val created = jsch.getSession(configuration.username, configuration.host, configuration.port).apply {
            setConfig("StrictHostKeyChecking", "yes")
            setConfig("PreferredAuthentications", when (credentials) {
                is SshCredentials.Password -> "password,keyboard-interactive"
                is SshCredentials.PrivateKey -> "publickey"
            })
            if (credentials is SshCredentials.Password) setPassword(credentials.password.toByteArray())
            setServerAliveInterval(SERVER_ALIVE_INTERVAL_MS)
            setServerAliveCountMax(SERVER_ALIVE_COUNT)
            setTimeout(SOCKET_TIMEOUT_MS)
            setDaemonThread(true)
        }
        try {
            withTimeout(CONNECT_DEADLINE_MS) { runBlockingIo { created.connect(CONNECT_TIMEOUT_MS) } }
        } catch (error: Throwable) {
            created.disconnect()
            repository.rejection()?.let { throw it }
            if (error is JSchException && error.message.orEmpty().contains("Auth fail", ignoreCase = true)) {
                throw SshAuthenticationException("SSH authentication failed", error)
            }
            throw error
        }
        val timestamp = System.nanoTime()
        connectedAtNanos = timestamp
        lastUsedNanos = timestamp
        session = created
        created
    }

    private suspend fun invalidate(expected: Session) = connectionMutex.withLock {
        if (session === expected) {
            expected.disconnect()
            session = null
        }
    }

    private fun markUsed() {
        lastUsedNanos = System.nanoTime()
    }

    private fun retainHandle() {
        synchronized(lifecycleLock) {
            check(!closeRequested) { "SSH connection manager is closed" }
            retainedHandles++
        }
    }

    private suspend fun releaseHandle() {
        val closeNow = synchronized(lifecycleLock) {
            check(retainedHandles > 0)
            retainedHandles--
            closeRequested && retainedHandles == 0
        }
        if (closeNow) completeClose()
    }

    private suspend fun <T> runBlockingIo(block: () -> T): T = runInterruptible(dispatcher, block)

    private fun drain(input: InputStream, output: ByteArrayOutputStream) {
        while (true) {
            val available = input.available().coerceAtMost(IO_BUFFER_SIZE)
            if (available <= 0) return
            val buffer = ByteArray(available)
            val count = input.read(buffer)
            if (count <= 0) return
            if (output.size() < MAX_CAPTURE_BYTES) {
                output.write(buffer, 0, minOf(count, MAX_CAPTURE_BYTES - output.size()))
            }
        }
    }

    private suspend fun readTerminal(channel: ChannelShell, input: InputStream, maximum: Int): ByteArray? {
        return runBlockingIo {
            val available = input.available()
            when {
                available > 0 -> ByteArray(minOf(available, maximum)).let { buffer ->
                    val count = input.read(buffer)
                    if (count < 0) ByteArray(0) else buffer.copyOf(count)
                }
                channel.isClosed -> ByteArray(0)
                else -> null
            }
        }
    }

    private suspend fun waitForChannel(channel: Channel): Int? {
        while (!channel.isClosed) {
            coroutineContext.ensureActive()
            delay(IO_POLL_MS)
        }
        return channel.exitStatus.takeIf { it >= 0 }
    }

    private fun SftpATTRS.toMetadata() = SshRemoteMetadata(size, mTime, permissions)

    private class StrictHostKeyRepository(
        private val verifier: SshHostKeyVerifier,
        private val endpoint: SshEndpoint,
    ) : HostKeyRepository {
        @Volatile private var rejected: SshHostKeyException? = null

        override fun check(host: String, key: ByteArray): Int {
            val observed = SshHostKey(HostKey(host, key).type, SshHostKeyVerifier.fingerprint(key))
            return when (verifier.verify(observed)) {
                SshHostKeyDecision.MATCH -> HostKeyRepository.OK
                SshHostKeyDecision.CONFIRMATION_REQUIRED -> {
                    rejected = SshHostKeyException.ConfirmationRequired(endpoint, observed)
                    HostKeyRepository.NOT_INCLUDED
                }
                SshHostKeyDecision.CHANGED -> {
                    rejected = SshHostKeyException.Changed(endpoint, observed)
                    HostKeyRepository.CHANGED
                }
            }
        }

        fun rejection(): SshHostKeyException? = rejected
        override fun add(hostkey: HostKey, ui: UserInfo?) = Unit
        override fun remove(host: String, type: String) = Unit
        override fun remove(host: String, type: String, key: ByteArray) = Unit
        override fun getKnownHostsRepositoryID() = "WeAgent confirmed host key"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String, type: String?): Array<HostKey> = emptyArray()
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val CONNECT_DEADLINE_MS = 20_000L
        private const val CHANNEL_CONNECT_TIMEOUT_MS = 10_000
        private const val SOCKET_TIMEOUT_MS = 30_000
        private const val SERVER_ALIVE_INTERVAL_MS = 15_000
        private const val SERVER_ALIVE_COUNT = 2
        private const val SFTP_TIMEOUT_MS = 30_000L
        private const val WRITE_TIMEOUT_MS = 60_000L
        private const val IO_POLL_MS = 20L
        private const val IO_BUFFER_SIZE = 32 * 1024
        private const val MAX_CAPTURE_BYTES = NativeBackend.DEFAULT_MAX_OUTPUT_BYTES
        private const val IDLE_TIMEOUT_NANOS = 5L * 60 * 1_000_000_000
        private const val LIFETIME_NANOS = 30L * 60 * 1_000_000_000
        private const val REVERSE_FORWARD_ATTEMPTS = 16
        private const val REVERSE_PORT_MIN = 30_000
        private const val REVERSE_PORT_MAX = 59_999
    }
}

class SshTerminalConnection internal constructor(
    private val readBlock: suspend (Int) -> ByteArray?,
    private val writeBlock: suspend (ByteArray) -> Unit,
    private val resizeBlock: suspend (Int, Int) -> Unit,
    private val waitBlock: suspend () -> Int?,
    private val killBlock: suspend () -> Unit,
    private val closeBlock: suspend () -> Unit,
) {
    private val closed = AtomicBoolean()
    suspend fun read(maxBytes: Int) = readBlock(maxBytes)
    suspend fun write(bytes: ByteArray) = writeBlock(bytes)
    suspend fun resize(cols: Int, rows: Int) = resizeBlock(cols, rows)
    suspend fun waitForExit() = waitBlock()
    suspend fun kill() = killBlock()
    suspend fun close() {
        if (closed.compareAndSet(false, true)) closeBlock()
    }
}

class SshReverseForward internal constructor(val remotePort: Int, private val closeBlock: suspend () -> Unit) {
    private val closed = AtomicBoolean()
    suspend fun close() {
        if (closed.compareAndSet(false, true)) closeBlock()
    }
}
