package com.example.util

import com.example.data.LocalHistoryVault
import com.example.ui.FocusRecord
import android.util.Log

object IntervalMerger {

    data class MergeResult(
        val merged: List<LocalHistoryVault>,
        val trueTotalMs: Long
    )

    data class FocusRecordMergeResult(
        val merged: List<FocusRecord>,
        val trueTotalMs: Long
    )

    /**
     * LEETCODE 56: INTERVAL MERGER FOR STUDY SESSIONS (LocalHistoryVault)
     * Takes a list of raw session blocks and collapses overlapping timestamps.
     */
    fun mergeOverlappingStudyIntervals(blocks: List<LocalHistoryVault>): MergeResult {
        if (blocks.isEmpty()) return MergeResult(emptyList(), 0L)

        // 1. Sort blocks chronologically by start time
        val sorted = blocks.sortedBy { it.start_time_ms }
        val merged = mutableListOf<LocalHistoryVault>()
        merged.add(sorted[0].copy())

        // 2. Iterate and merge overlapping time spans
        for (i in 1 until sorted.size) {
            val current = sorted[i]
            val lastMerged = merged[merged.size - 1]

            // Check if current block starts before or when the last merged block ended
            if (current.start_time_ms <= lastMerged.end_time_ms) {
                // Overlap detected! Expand the boundary to the maximum end timestamp
                val newEndTimeMs = maxOf(lastMerged.end_time_ms, current.end_time_ms)
                val newTotalFocusMs = newEndTimeMs - lastMerged.start_time_ms
                
                merged[merged.size - 1] = lastMerged.copy(
                    end_time_ms = newEndTimeMs,
                    total_focus_ms = newTotalFocusMs,
                    duration_formatted = TimeEngine.formatDuration(newTotalFocusMs),
                    end_time_formatted = TimeEngine.formatTimestamp(newEndTimeMs)
                )
                Log.d("IntervalMerger", "Merged overlapping local intervals: ${lastMerged.record_id} and ${current.record_id}")
            } else {
                // No overlap, push as a distinct study interval
                merged.add(current.copy())
            }
        }

        // 3. Calculate true physical milliseconds studied
        var trueTotalMs = 0L
        for (span in merged) {
            trueTotalMs += (span.end_time_ms - span.start_time_ms)
        }

        return MergeResult(merged, trueTotalMs)
    }

    /**
     * LEETCODE 56: INTERVAL MERGER FOR FOCUS RECORDS (FocusRecord Remote sync)
     * Helper to clean up duplicates / overlaps on the remote sync node.
     */
    fun mergeOverlappingFocusRecords(records: List<FocusRecord>): FocusRecordMergeResult {
        if (records.isEmpty()) return FocusRecordMergeResult(emptyList(), 0L)

        // Helper to extract timestamps
        class HelperInterval(val record: FocusRecord, val startMs: Long, var endMs: Long)

        val helperIntervals = records.map { rec ->
            val startMs = rec.id.substringAfter("sess_").toLongOrNull() 
                ?: (System.currentTimeMillis() - rec.durationSeconds * 1000L)
            val endMs = startMs + (rec.durationSeconds * 1000L)
            HelperInterval(rec, startMs, endMs)
        }

        // Sort chronologically
        val sorted = helperIntervals.sortedBy { it.startMs }
        val mergedHelpers = mutableListOf<HelperInterval>()
        mergedHelpers.add(sorted[0])

        for (i in 1 until sorted.size) {
            val current = sorted[i]
            val lastMerged = mergedHelpers[mergedHelpers.size - 1]

            if (current.startMs <= lastMerged.endMs) {
                // Overlap!
                lastMerged.endMs = maxOf(lastMerged.endMs, current.endMs)
            } else {
                mergedHelpers.add(current)
            }
        }

        // Convert back to FocusRecords
        val mergedRecords = mergedHelpers.map { helper ->
            val finalDurationSecs = ((helper.endMs - helper.startMs) / 1000L).toInt()
            val finalDurationMins = finalDurationSecs / 60
            helper.record.copy(
                durationSeconds = finalDurationSecs,
                durationMinutes = finalDurationMins,
                endTime = TimeEngine.formatTimestamp(helper.endMs)
            )
        }

        var trueTotalMs = 0L
        for (helper in mergedHelpers) {
            trueTotalMs += (helper.endMs - helper.startMs)
        }

        return FocusRecordMergeResult(mergedRecords, trueTotalMs)
    }
}
