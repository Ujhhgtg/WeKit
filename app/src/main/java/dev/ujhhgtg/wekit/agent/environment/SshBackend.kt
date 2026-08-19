package dev.ujhhgtg.wekit.agent.environment

import dev.ujhhgtg.wekit.agent.bridge.ToolBridgeServer
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

class SshBackend(
    override val snapshot: EnvironmentSnapshot,
    internal val connection: SshConnectionManager,
) : LinuxEnvironmentBackend {
    init {
        require(snapshot.type == LinuxEnvironmentType.SSH)
    }

    override suspend fun exec(
        command: String,
        timeoutMillis: Long,
        environmentVariables: Map<String, String>,
    ): ExecResult {
        val startedAt = System.nanoTime()
        val localBridgePort = environmentVariables["WEAGENT_BRIDGE_PORT"]?.toIntOrNull()
        val forward = localBridgePort?.let { connection.openReverseForward(it) }
        val remoteEnvironment = if (forward == null) environmentVariables else {
            environmentVariables + ToolBridgeServer.Endpoint(
                localBridgePort,
                environmentVariables.getValue("WEAGENT_BRIDGE_TOKEN"),
            ).environment(forward.remotePort)
        }
        return try {
            val response = connection.execute(shellCommand(command, remoteEnvironment), timeoutMillis)
            ExecResult(
                stdout = response.stdout.toString(StandardCharsets.UTF_8),
                stderr = response.stderr.toString(StandardCharsets.UTF_8),
                exitCode = response.exitCode,
                timedOut = response.timedOut,
                elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000,
            )
        } finally {
            forward?.close()
        }
    }

    override suspend fun readUtf8(path: String, maxBytes: Long): String {
        val remote = connection.readFile(resolvePath(path), maxBytes)
        require(remote.metadata != null) { "file does not exist: $path" }
        return decode(remote.bytes)
    }

    override suspend fun edit(request: FileEditRequest) {
        require(!request.replaceAll || request.oldString != null) { "replaceAll is invalid in creation mode" }
        val path = resolvePath(request.path)
        val original = connection.readFile(path, NativeBackend.MAX_EDIT_BYTES)
        val content = decode(original.bytes)
        val updated = when (val old = request.oldString) {
            null -> request.newString.also {
                require(content.isEmpty()) { "creation requires a missing or empty file" }
            }
            else -> {
                require(old.isNotEmpty()) { "oldString must not be empty" }
                val count = countOccurrences(content, old)
                require(count > 0) { "oldString was not found" }
                require(request.replaceAll || count == 1) { "oldString occurs $count times" }
                if (request.replaceAll) content.replace(old, request.newString)
                else content.replaceFirst(old, request.newString)
            }
        }
        connection.atomicWrite(path, original, updated.toByteArray(StandardCharsets.UTF_8))
    }

    override fun resolvePath(path: String): String {
        require('\u0000' !in path) { "path contains NUL" }
        val absolute = if (path.startsWith('/')) path else "${snapshot.workingDirectory.trimEnd('/')}/$path"
        val components = ArrayDeque<String>()
        absolute.split('/').forEach { component ->
            when (component) {
                "", "." -> Unit
                ".." -> if (components.isNotEmpty()) components.removeLast()
                else -> components.addLast(component)
            }
        }
        val resolved = "/" + components.joinToString("/")
        require(listOf("/proc", "/sys", "/dev").none { resolved == it || resolved.startsWith("$it/") }) {
            "virtual and device files are not supported"
        }
        return resolved
    }

    override suspend fun ensureBridge(): BridgeInstallArtifact {
        val home = connection.homeDirectory()
        val upload = "$home/.weagent-invoke-tool-${java.util.UUID.randomUUID()}"
        connection.upload(upload, REMOTE_HELPER.toByteArray(StandardCharsets.UTF_8))
        val install = connection.execute(
            """
            set -e
            trap 'rm -f ${quote(upload)}' EXIT
            if [ "$(id -u)" = 0 ]; then
              install -m 755 ${quote(upload)} /usr/bin/invoke_tool
              printf '/usr/bin/invoke_tool\n'
            elif command -v sudo >/dev/null 2>&1 && sudo -n true >/dev/null 2>&1; then
              sudo -n install -m 755 ${quote(upload)} /usr/bin/invoke_tool
              printf '/usr/bin/invoke_tool\n'
            else
              mkdir -p "${'$'}HOME/.local/bin"
              mv ${quote(upload)} "${'$'}HOME/.local/bin/invoke_tool"
              chmod 755 "${'$'}HOME/.local/bin/invoke_tool"
              printf '%s/.local/bin/invoke_tool\n' "${'$'}HOME"
            fi
            """.trimIndent(),
            30_000,
        )
        check(install.exitCode == 0) { install.stderr.toString(StandardCharsets.UTF_8).ifBlank { "SSH helper installation failed" } }
        val executable = install.stdout.toString(StandardCharsets.UTF_8).trim().lineSequence().last()
        return BridgeInstallArtifact(executable, executable.substringBeforeLast('/'))
    }

    override suspend fun checkHealth(): EnvironmentHealth = try {
        val result = connection.execute(
            "command -v bash dd wc >/dev/null && test -d ${quote(snapshot.workingDirectory)} && printf healthy",
            15_000,
        )
        if (result.exitCode == 0) EnvironmentHealth(EnvironmentHealthState.HEALTHY)
        else EnvironmentHealth(
            EnvironmentHealthState.DEGRADED,
            result.stderr.toString(StandardCharsets.UTF_8).ifBlank { "remote Bash, dd, wc, or working directory is unavailable" },
        )
    } catch (error: Throwable) {
        EnvironmentHealth(EnvironmentHealthState.UNAVAILABLE, error.message)
    }

    override suspend fun close() = connection.close()

    private fun shellCommand(command: String, environmentVariables: Map<String, String>): String {
        val exports = environmentVariables.entries.joinToString(" ") { (key, value) ->
            require(key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "invalid environment variable name" }
            "$key=${quote(value)}"
        }
        val invocation = "cd ${quote(snapshot.workingDirectory)} && exec /bin/bash -lc ${quote(command)}"
        return if (exports.isEmpty()) invocation else "export $exports; $invocation"
    }

    private fun decode(bytes: ByteArray): String = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()

    private fun countOccurrences(content: String, needle: String): Int {
        var count = 0
        var offset = 0
        while (true) {
            val found = content.indexOf(needle, offset)
            if (found < 0) return count
            count++
            offset = found + needle.length
        }
    }

    private fun quote(value: String) = "'${value.replace("'", "'\\''")}'"

    companion object {
        private val REMOTE_HELPER = """
            #!/bin/bash
            set -euo pipefail
            export LC_ALL=C
            : "${'$'}{WEAGENT_BRIDGE_PORT:?WEAGENT_BRIDGE_PORT is not set}"
            : "${'$'}{WEAGENT_BRIDGE_TOKEN:?WEAGENT_BRIDGE_TOKEN is not set}"
            json_quote() {
              local value=${'$'}1
              value=${'$'}{value//\\/\\\\}; value=${'$'}{value//\"/\\\"}
              value=${'$'}{value//${'$'}'\n'/\\n}; value=${'$'}{value//${'$'}'\r'/\\r}; value=${'$'}{value//${'$'}'\t'/\\t}
              printf '"%s"' "${'$'}value"
            }
            case "${'$'}{1:-}" in
              list) shift; if [ "${'$'}#" -eq 0 ]; then payload='{"op":"list"}'; elif [ "${'$'}#" -eq 2 ] && [ "${'$'}1" = --provider ]; then payload="{\"op\":\"list\",\"provider\":$(json_quote "${'$'}2")}"; else exit 2; fi ;;
              search) [ "${'$'}#" -eq 2 ] || exit 2; payload="{\"op\":\"search\",\"keyword\":$(json_quote "${'$'}2")}" ;;
              schema) [ "${'$'}#" -eq 2 ] || exit 2; payload="{\"op\":\"schema\",\"name\":$(json_quote "${'$'}2")}" ;;
              call) [ "${'$'}#" -eq 4 ] && [ "${'$'}3" = --json ] || exit 2; payload="{\"op\":\"call\",\"name\":$(json_quote "${'$'}2"),\"arguments\":${'$'}4}" ;;
              *) exit 2 ;;
            esac
            exec 3<>"/dev/tcp/127.0.0.1/${'$'}WEAGENT_BRIDGE_PORT"
            printf 'WBT/1 %s %s\n%s' "${'$'}WEAGENT_BRIDGE_TOKEN" "${'$'}{#payload}" "${'$'}payload" >&3
            IFS=' ' read -r version token length <&3
            [ "${'$'}version" = WBT/1 ] && [ "${'$'}token" = "${'$'}WEAGENT_BRIDGE_TOKEN" ] || exit 3
            dd bs=1 count="${'$'}length" status=none <&3
            printf '\n'
        """.trimIndent() + "\n"
    }
}
