package dev.ujhhgtg.wekit.features.items.chat

/**
 * Thin facade over [ReadReceiptsTunnelRuntime], preserving the surface the callers of the old
 * Binder client were wired to. The runtime is the in-process authoritative tunnel owner; every
 * member here is a plain delegation, and `status` reads the runtime's authoritative snapshot.
 */
internal object ReadReceiptsTunnelController {
    val status: ReadReceiptsTunnelStatus
        get() = ReadReceiptsTunnelRuntime.readStatus()

    val credentialExists: Boolean
        get() = ReadReceiptsTunnelRuntime.credentialExists()

    val browserLoginState: CloudflareLoginState
        get() = ReadReceiptsTunnelRuntime.browserLoginState

    val browserAccountId: String
        get() = ReadReceiptsTunnelRuntime.browserAccountId

    val browserExistingTunnels: List<ExistingTunnel>
        get() = ReadReceiptsTunnelRuntime.browserExistingTunnels

    val committedCredentialMetadata: CommittedTunnelCredentialMetadata?
        get() = ReadReceiptsTunnelRuntime.committedCredentialMetadata()

    val browserMetadataRebindDecision: BrowserMetadataRebindDecision
        get() = ReadReceiptsTunnelRuntime.browserMetadataRebindDecision

    val browserLoginRestartRequired: Boolean
        get() = ReadReceiptsTunnelRuntime.browserLoginRestartRequired

    val credentialMetadataLoading: Boolean
        get() = ReadReceiptsTunnelRuntime.credentialMetadataLoading

    fun verifiedEndpoint(): String? = status
        .takeIf { it.state == ReadReceiptsTunnelState.CONNECTED }
        ?.publicUrl

    fun needsVisibleStart() = ReadReceiptsTunnelRuntime.needsVisibleStart()

    fun originAuthenticator(): String = ReadReceiptsTunnelRuntime.originAuthenticator()

    fun startVisible(
        mode: ReadReceiptsTunnelMode,
        originPort: Int,
        hostname: String,
        token: String?,
        onHandoff: (OriginRequestTerminal<Unit>) -> Unit,
    ) = ReadReceiptsTunnelRuntime.start(
        mode = mode,
        originPort = originPort,
        hostname = hostname,
        token = token,
        connectorAuthenticator = originAuthenticator(),
        onHandoff = onHandoff,
    )

    fun stop(onStopped: ((Result<Unit>) -> Unit)? = null) =
        ReadReceiptsTunnelRuntime.stop(onStopped)

    /**
     * Compatibility no-op re-read: the runtime's authoritative status is current on every
     * [status] read, so there is nothing to refresh beyond touching the snapshot.
     */
    fun refresh() {
        ReadReceiptsTunnelRuntime.readStatus()
    }

    fun deleteCredential() = ReadReceiptsTunnelRuntime.deleteCredential()

    suspend fun beginBrowserLogin(): CloudflareLoginState =
        ReadReceiptsTunnelRuntime.beginBrowserLogin()

    suspend fun listExistingTunnels(): List<ExistingTunnel> =
        ReadReceiptsTunnelRuntime.listExistingTunnels()

    suspend fun selectExistingTunnel(
        id: String,
        canonicalRoot: String,
        fixedPort: Int,
    ): Result<Unit> = ReadReceiptsTunnelRuntime.selectExistingTunnel(id, canonicalRoot, fixedPort)

    suspend fun cancelBrowserLogin(): Result<Unit> =
        ReadReceiptsTunnelRuntime.cancelBrowserLogin()

    suspend fun logoutBrowserLogin(): Result<Unit> =
        ReadReceiptsTunnelRuntime.logoutBrowserLogin()
}

internal class BrowserLoginException(
    val errorCode: ReadReceiptsTunnelErrorCode,
    diagnostic: String,
    cause: Throwable? = null,
) : IllegalStateException(diagnostic, cause)
