package com.zapret2.app.data

/** Fixed paths for bounded runtime diagnostics; lifecycle state comes only from status v5. */
internal object RuntimeStatePaths {
    const val STATE_DIR = "/data/adb/zapret2-state"
    const val LOG_FILE = "$STATE_DIR/nfqws2.log"
    const val ERROR_LOG_FILE = "$STATE_DIR/nfqws2.error"
    const val RUNTIME_CMDLINE_FILE = "$STATE_DIR/nfqws2.cmdline"
}

/** Canonical non-negative decimal fields shared by typed lifecycle protocols. */
internal object ProtocolDecimal {
    private val nonNegative = Regex("0|[1-9][0-9]*")

    fun isCanonicalNonNegativeLong(value: String): Boolean =
        value.matches(nonNegative) && value.toLongOrNull() != null
}
