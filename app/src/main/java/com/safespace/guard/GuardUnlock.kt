package com.safespace.guard

import android.os.SystemClock

/**
 * Holds the short "parent unlocked" window.
 * Blocking is paused only while the window is open.
 * Lives in memory, so if the app process dies the guard goes back to "blocked".
 */
object GuardUnlock {

    /** How long the guard stays paused after the parent enters the PIN. */
    const val UNLOCK_MS = 30_000L

    @Volatile
    private var openUntil = 0L

    fun open() {
        openUntil = SystemClock.elapsedRealtime() + UNLOCK_MS
    }

    fun close() {
        openUntil = 0L
    }

    fun isOpen(): Boolean = SystemClock.elapsedRealtime() < openUntil
}
