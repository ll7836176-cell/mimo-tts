package com.jingyue.mimotts.core.spi

sealed interface BundlePrimitiveSnapshot {
    data class BooleanValue(val value: Boolean) : BundlePrimitiveSnapshot
    data class IntValue(val value: Int) : BundlePrimitiveSnapshot
    data class FloatValue(val value: Float) : BundlePrimitiveSnapshot
    data class StringValue(val value: String) : BundlePrimitiveSnapshot
}

data class BundleEntrySnapshot(
    val ordinal: Int,
    val key: String,
    val value: BundlePrimitiveSnapshot,
)

data class GenericTtsRequestParamsSnapshot(
    val entries: List<BundleEntrySnapshot>,
) {
    init {
        require(entries.withIndex().all { (index, entry) -> index == entry.ordinal }) {
            "entry ordinals must be contiguous"
        }
    }
}
