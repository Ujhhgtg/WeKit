package dev.ujhhgtg.wekit.features.items.chat

/** JNI boundary for the loopback-only embedded read-receipts origin. */
internal object ReadReceiptsNative {
    /** Returns null after the server reaches running state, or a bounded error message. */
    external fun startServer(databasePath: String, port: Int): String?

    /** Requests asynchronous shutdown and returns immediately. */
    external fun stopServer()

    /**
     * Returns JSON with stable `state`, `port`, and `error` fields.
     * `port` is non-null only after binding; `error` is non-null only for a failed state.
     */
    external fun serverStatus(): String
}
