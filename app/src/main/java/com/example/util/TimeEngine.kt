package com.example.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TimeEngine {
    /**
     * 1. DURATION FORMATTER: Converts raw integer milliseconds into HH:MM:SS
     * Used for: UI Countdown Timer, Base Focus Time, Break Duration
     * Example: 5415000 ms -> "01:30:15"
     */
    fun formatDuration(ms: Long): String {
        if (ms <= 0) return "00:00:00"
        
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }

    /**
     * 2. TIMESTAMP FORMATTER: Converts a Unix Epoch timestamp into HH:MM:SS:MS
     * Used for: Event Logs, Start/End Timestamps, Lamport Audit Trail
     * Example: Date.now() -> "19:02:39:450"
     */
    fun formatTimestamp(timestampMs: Long): String {
        if (timestampMs <= 0) return "00:00:00:000"
        
        val date = Date(timestampMs)
        val sdf = SimpleDateFormat("HH:mm:ss:SSS", Locale.US)
        return sdf.format(date)
    }

    /**
     * 3. LIVE DELTA CALCULATOR: Runs every 100ms in your UI loop
     * Computes exact elapsed time without modifying the saved database base.
     */
    fun calculateLiveElapsedMs(baseFocusMs: Long, lastEventTsMs: Long, status: String): Long {
        if (status != "FOCUSING" || lastEventTsMs <= 0) {
            return baseFocusMs // Clock is frozen (Paused or Idle)
        }
        val runningDelta = System.currentTimeMillis() - lastEventTsMs
        return baseFocusMs + runningDelta
    }
}
