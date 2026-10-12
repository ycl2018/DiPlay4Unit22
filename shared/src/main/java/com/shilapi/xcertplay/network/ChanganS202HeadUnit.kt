package com.shilapi.xcertplay.network

import android.os.Build

/** Exact identity of the Android 9 Changan S202 head unit validated by this adaptation branch. */
internal object ChanganS202HeadUnit {
    private const val BOARD = "spm8666p1_64_car"
    private const val HARDWARE = "mt8666"

    val detected: Boolean
        get() = runCatching { matches(Build.BOARD, Build.HARDWARE) }.getOrDefault(false)

    internal fun matches(board: String?, hardware: String?): Boolean =
        board == BOARD && hardware == HARDWARE
}
