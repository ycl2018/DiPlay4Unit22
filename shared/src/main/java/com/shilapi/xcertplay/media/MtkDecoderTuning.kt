package com.shilapi.xcertplay.media

import android.media.MediaCodecList

/** Optional MediaTek decoder modes exposed by old MTK ACodec implementations. */
internal enum class MtkDecoderMode(val diagnosticName: String) {
    NONE("off"),
    LOW_LATENCY("low-latency"),
    LOW_LATENCY_NO_REORDER("low-latency+no-reorder"),
    ;

    fun fallback(): MtkDecoderMode = when (this) {
        LOW_LATENCY_NO_REORDER -> LOW_LATENCY
        LOW_LATENCY -> NONE
        NONE -> NONE
    }

    fun attempts(): List<MtkDecoderMode> = when (this) {
        LOW_LATENCY_NO_REORDER -> listOf(LOW_LATENCY_NO_REORDER, LOW_LATENCY, NONE)
        LOW_LATENCY -> listOf(LOW_LATENCY, NONE)
        NONE -> listOf(NONE)
    }
}

/** Detection and format keys for MediaTek's pre-Android-11 low-latency decoder path. */
object MtkDecoderTuning {
    const val LOW_LATENCY_KEY = "vdec-lowlatency"

    // This is the spelling used by MTK's ACodec even though it maps to NoReorderMode.
    const val NO_REORDER_KEY = "vdec-no-record"

    fun isDecoderName(name: String): Boolean =
        name.startsWith("OMX.MTK.", ignoreCase = true) ||
            name.startsWith("c2.mtk.", ignoreCase = true)

    /** The published vdec-* bridge is an ACodec/OMX extension, not a Codec2 vendor parameter. */
    fun supportsLegacyAcodecKeys(name: String): Boolean =
        name.startsWith("OMX.MTK.", ignoreCase = true)

    /** The MTK decoder Android would normally consider first for [mime], or null. */
    fun selectedDecoderName(mime: String): String? = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }?.name?.takeIf(::supportsLegacyAcodecKeys)
    }.getOrNull()
}

internal fun appliedMtkDecoderMode(requested: MtkDecoderMode, codecName: String): MtkDecoderMode =
    requested.takeIf { MtkDecoderTuning.supportsLegacyAcodecKeys(codecName) } ?: MtkDecoderMode.NONE

internal fun MtkDecoderMode.formatKeys(): List<String> = when (this) {
    MtkDecoderMode.NONE -> emptyList()
    MtkDecoderMode.LOW_LATENCY -> listOf(MtkDecoderTuning.LOW_LATENCY_KEY)
    MtkDecoderMode.LOW_LATENCY_NO_REORDER ->
        listOf(MtkDecoderTuning.LOW_LATENCY_KEY, MtkDecoderTuning.NO_REORDER_KEY)
}
