package dev.ujhhgtg.wekit.utils.android

import android.content.Intent
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

@OptIn(ExperimentalContracts::class)
inline fun Intent(block: Intent.() -> Unit): Intent {
    contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
    return Intent().apply(block)
}
