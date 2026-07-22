package com.zapret2.app.viewmodel

/** Mirrors repository line-ending canonicalization without allocating a normalized copy. */
internal fun fitsNormalizedEditorBudget(
    value: String,
    maxBytes: Int,
    trimTrailingNewlines: Boolean,
    appendTrailingNewline: Boolean,
): Boolean {
    if (maxBytes < 0) return false
    var endExclusive = value.length
    if (trimTrailingNewlines) {
        while (endExclusive > 0 &&
            (value[endExclusive - 1] == '\r' || value[endExclusive - 1] == '\n')
        ) {
            endExclusive -= 1
        }
    }
    var bytes = if (appendTrailingNewline) 1 else 0
    if (bytes > maxBytes) return false
    if (endExclusive <= (maxBytes - bytes) / 3) return true
    var index = 0
    while (index < endExclusive) {
        val current = value[index]
        if (current == '\r') {
            bytes += 1
            index += if (index + 1 < endExclusive && value[index + 1] == '\n') 2 else 1
        } else {
            val width = packedUtf8Width(value, index)
            bytes += width ushr UTF8_WIDTH_SHIFT
            index += width and UTF8_CODE_UNIT_MASK
        }
        if (bytes > maxBytes) return false
    }
    return true
}

private const val UTF8_WIDTH_SHIFT = 2
private const val UTF8_CODE_UNIT_MASK = 0b11

private fun packedUtf8Width(value: String, index: Int): Int {
    val current = value[index]
    return when {
        current.code <= 0x7F -> (1 shl UTF8_WIDTH_SHIFT) or 1
        current.code <= 0x7FF -> (2 shl UTF8_WIDTH_SHIFT) or 1
        Character.isHighSurrogate(current) &&
            index + 1 < value.length &&
            Character.isLowSurrogate(value[index + 1]) -> (4 shl UTF8_WIDTH_SHIFT) or 2
        else -> (3 shl UTF8_WIDTH_SHIFT) or 1
    }
}
