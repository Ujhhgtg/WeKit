package dev.ujhhgtg.wekit.bootstrap.auth

import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/**
 * Caches single-use challenges pre-issued by the server (challenge pipelining), so most
 * signed requests need no separate POST /v1/challenges round trip.
 *
 * Challenges bound to this process (the initialization report) only live in memory.
 * Floating challenges (the next process initialization) are persisted, because the next
 * WeChat process starts with a fresh process nonce and a fresh JVM.
 */
class ChallengePool(store: InstallationStore) {
    private val file = File(store.directory, "challenges.json")
    private val bound = ArrayList<ChallengeTicket>()
    private val serializer = ListSerializer(ChallengeTicket.serializer())

    /** Never sign with a challenge that could expire while the request is in flight. */
    private val expiryMarginMs = 5_000L
    private val maximumFloating = 4

    /**
     * Removes and returns a usable ticket for (method, path), preferring one bound to the
     * current process. Returns null when the caller must request a fresh challenge.
     */
    @Synchronized
    fun take(method: String, path: String, processNonce: String): ChallengeTicket? {
        val now = System.currentTimeMillis()
        dropExpiredBound(now)
        val index = bound.indexOfFirst { it.method == method && it.path == path && it.processNonce == processNonce }
        if (index >= 0) return bound.removeAt(index)
        val floating = loadFloating()
        val ticket = floating.firstOrNull { it.method == method && it.path == path && usable(it, now) }
            ?: return null
        saveFloating(floating.filter { it.challengeId != ticket.challengeId })
        return ticket
    }

    /** Stores tickets carried by a successful signed response. */
    @Synchronized
    fun offer(tickets: List<ChallengeTicket>, processNonce: String) {
        if (tickets.isEmpty()) return
        val now = System.currentTimeMillis()
        var floating: MutableList<ChallengeTicket>? = null
        for (ticket in tickets) {
            if (!usable(ticket, now) || !valid(ticket)) continue
            val boundNonce = ticket.processNonce
            if (boundNonce != null) {
                if (boundNonce == processNonce && bound.none { it.challengeId == ticket.challengeId })
                    bound.add(ticket)
            } else {
                val current = floating ?: loadFloating().also { floating = it }
                if (current.none { it.challengeId == ticket.challengeId }) current.add(ticket)
            }
        }
        floating?.let { saveFloating(it) }
    }

    /** Number of usable floating initialization tickets; sent to the server as a hint. */
    @Synchronized
    fun floatingCount(): Int {
        val now = System.currentTimeMillis()
        val floating = loadFloating()
        val valid = floating.filter { usable(it, now) && it.path == "/v1/initializations" && it.method == "POST" }
        if (valid.size != floating.size) saveFloating(valid)
        return valid.size
    }

    private fun usable(ticket: ChallengeTicket, now: Long) = ticket.expiresAt - expiryMarginMs > now

    private fun valid(ticket: ChallengeTicket) = runCatching {
        require(ticket.challengeId.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        WireProtocol.decode(ticket.serverNonce, 32)
        require(ticket.method == "POST" || ticket.method == "GET")
        require(ticket.path.startsWith("/v1/") && ticket.path.length <= 160)
        require(ticket.expiresAt > 0)
    }.isSuccess

    private fun dropExpiredBound(now: Long) = bound.removeAll { !usable(it, now) }

    private fun loadFloating(): MutableList<ChallengeTicket> {
        if (!file.exists()) return mutableListOf()
        return runCatching {
            val bytes = file.inputStream().use { BootstrapApi.readBounded(it, 32768) }
            val text = bytes.toString(Charsets.UTF_8)
            if (text.isEmpty()) return@runCatching mutableListOf()
            WireJson.format.decodeFromString(serializer, text)
                .filter { valid(it) }
                .filter { it.processNonce == null }
                .filter { usable(it, System.currentTimeMillis()) }
                .takeLast(maximumFloating)
                .toMutableList()
        }.getOrElse {
            // A corrupt or stale cache must never block authentication; just refetch.
            file.delete()
            mutableListOf()
        }
    }

    private fun saveFloating(tickets: List<ChallengeTicket>) {
        val bounded = tickets.takeLast(maximumFloating)
        val bytes = WireJson.format.encodeToString(serializer, bounded).toByteArray(Charsets.UTF_8)
        val temporary = File(file.parentFile, "challenges.${WireProtocol.requestId()}.part")
        try {
            temporary.outputStream().use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            check(temporary.renameTo(file)) { "Cannot publish challenge cache" }
        } finally {
            temporary.delete()
        }
    }
}
