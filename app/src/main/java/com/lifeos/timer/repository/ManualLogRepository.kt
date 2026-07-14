package com.lifeos.timer.repository

import androidx.room.withTransaction
import com.example.data.AppDatabase
import com.example.data.LocalHistoryVault
import com.lifeos.timer.data.local.*
import com.lifeos.timer.util.TimeEngine
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class ManualLogRepository(
    private val database: AppDatabase,
    private val timerDao: TimerDao,
    private val gson: Gson
) {
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val MAX_DAILY_MANUAL_MS = 6 * 60 * 60 * 1000L // 6 Hours in Milliseconds

    /**
     * LOG MANUAL FOCUS SESSION WITH 6-HOUR DAILY CAP
     * @return Pair<Boolean, String> -> (Success status, UI Message/Reason)
     */
    suspend fun logManualStudySession(
        taskTitle: String,
        subjectTag: String,
        durationMinutes: Int
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (durationMinutes <= 0) return@withContext Pair(false, "Duration must be greater than 0 minutes.")

        val nowMs = System.currentTimeMillis()
        val durationMs = durationMinutes * 60 * 1000L
        val dateStr = dateFormat.format(Date(nowMs))

        // --- STEP 1: THE 6-HOUR DAILY QUOTA CHECK ---
        val existingManualTodayMs = timerDao.getTodayManualFocusTimeMs(dateStr)
        val projectedTotalMs = existingManualTodayMs + durationMs

        if (projectedTotalMs > MAX_DAILY_MANUAL_MS) {
            val remainingMs = (MAX_DAILY_MANUAL_MS - existingManualTodayMs).coerceAtLeast(0L)
            val remainingMins = remainingMs / (60 * 1000L)
            println("Manual log rejected: Exceeds 6-hour daily limit. Remaining today: ${remainingMins}m.")
            return@withContext Pair(
                false, 
                "Daily manual log limit (6 hours) reached! You can only log up to $remainingMins more minutes today."
            )
        }

        // --- STEP 2: PREPARE RECORD WITH MANUAL_LOG MODE ---
        val approximatedStartMs = nowMs - durationMs
        val recordId = "manual_${nowMs}_${subjectTag.lowercase()}"

        val manualVaultRecord = LocalHistoryVault(
            record_id = recordId,
            date_string = dateStr,
            subject = subjectTag,
            task_title = taskTitle, // Raw title preserved; mode handles the display badge
            start_time_ms = approximatedStartMs,
            end_time_ms = nowMs,
            total_focus_ms = durationMs,
            duration_formatted = TimeEngine.formatDuration(durationMs),
            start_time_formatted = TimeEngine.formatTimestamp(approximatedStartMs),
            end_time_formatted = TimeEngine.formatTimestamp(nowMs),
            is_synced_to_firestore = 0
            // Note: If your entity has a 'mode' column, set: mode = "MANUAL_LOG"
        )

        // --- STEP 3: ATOMIC ROOM TRANSACTION & DIRECT-TO-VAULT ROUTING ---
        database.withTransaction {
            // Save to local SQLite Vault
            timerDao.archiveToVault(manualVaultRecord)

            // Enqueue Outbox payload with explicit "MANUAL_LOG" mode stamp
            val cloudPayload = gson.toJson(mapOf(
                "recordId" to recordId,
                "dateString" to dateStr,
                "subject" to subjectTag,
                "taskTitle" to taskTitle,
                "mode" to "MANUAL_LOG", // Explicitly replaces Pomodoro/Stopwatch
                "metrics" to mapOf(
                    "totalFocusMs" to durationMs,
                    "durationFormatted" to TimeEngine.formatDuration(durationMs),
                    "startTimeFormatted" to TimeEngine.formatTimestamp(approximatedStartMs),
                    "endTimeFormatted" to TimeEngine.formatTimestamp(nowMs)
                ),
                "loggedByDevice" to "android_mobile_apk",
                "isManualEntry" to true
            ))

            timerDao.enqueueOutboxMutation(
                OutboxMutation(
                    mutationId = "mut_manual_$nowMs",
                    createdAtMs = nowMs,
                    routingTarget = "FIRESTORE_DIRECT_VAULT",
                    actionType = "ARCHIVE_SESSION",
                    payloadJson = cloudPayload
                )
            )
        }

        return@withContext Pair(true, "Successfully logged ${durationMinutes}m of manual study time!")
    }
}
