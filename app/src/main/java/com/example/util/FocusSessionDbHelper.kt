package com.example.util

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.example.data.*
import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FocusSessionDbHelper {
    private val scope = CoroutineScope(Dispatchers.IO)

    /**
     * Start Focus (Law) handler
     */
    fun handleStartFocus(context: Context, tag: String = "Study", taskTitle: String = "General Focus", mode: String = "POMODORO") {
        scope.launch {
            val nowMs = System.currentTimeMillis()
            val nowFormatted = TimeEngine.formatTimestamp(nowMs)
            val sessionId = "sess_$nowMs"
            
            val startEvent = JSONObject().apply {
                put("id", "-Nx_$nowMs")
                put("event", "start")
                put("tsMs", nowMs)
                put("tsFormatted", nowFormatted)
            }
            val timelineJson = JSONArray().put(startEvent).toString()

            val activeSession = LocalActiveSession(
                session_id = sessionId,
                status = "FOCUSING",
                mode = mode,
                tag = tag,
                task_title = taskTitle,
                base_focus_time_ms = 0L,
                base_break_time_ms = 0L,
                last_event_ts_ms = nowMs,
                base_focus_formatted = "00:00:00",
                last_event_formatted = nowFormatted,
                timeline_json = timelineJson,
                is_current_leader = 1
            )

            val payload = JSONObject().apply {
                put("sessionId", sessionId)
                put("status", "FOCUSING")
                put("tag", tag)
                put("taskTitle", taskTitle)
                put("baseFocusTimeMs", 0L)
                put("baseFocusTimeFormatted", "00:00:00")
                put("lastEventTimestampMs", nowMs)
                put("lastEventFormatted", nowFormatted)
                
                val timelineObj = JSONObject()
                timelineObj.put("-Nx_$nowMs", startEvent)
                put("timeline", timelineObj)
            }.toString()

            val outboxItem = OutboxQueue(
                mutation_id = "mut_$nowMs",
                created_at_ms = nowMs,
                routing_target = "RTDB_LIVE_SYNC",
                action_type = "START",
                payload_json = payload,
                status = "PENDING"
            )

            try {
                val db = AppDatabase.getInstance(context)
                db.withTransaction {
                    // Clean any old active sessions
                    db.openHelper.writableDatabase.execSQL("DELETE FROM local_active_session")
                    
                    // Insert active session
                    db.localActiveSessionDao().insertOrUpdateSession(activeSession)
                    
                    // Queue for outbox
                    db.outboxQueueDao().insertQueueItem(outboxItem)
                }
                Log.d("FocusSessionDbHelper", "handleStartFocus completed successfully for sessionId: $sessionId")
            } catch (e: Exception) {
                Log.e("FocusSessionDbHelper", "Error during handleStartFocus transaction", e)
            }
        }
    }

    /**
     * Pause Focus handler
     */
    fun handlePauseFocus(context: Context) {
        scope.launch {
            try {
                val db = AppDatabase.getInstance(context)
                val currentSession = db.localActiveSessionDao().getActiveSession() ?: return@launch
                if (currentSession.status != "FOCUSING") return@launch

                val nowMs = System.currentTimeMillis()
                val nowFormatted = TimeEngine.formatTimestamp(nowMs)
                
                val liveFocusMs = TimeEngine.calculateLiveElapsedMs(
                    currentSession.base_focus_time_ms,
                    currentSession.last_event_ts_ms,
                    currentSession.status
                )

                val pauseEvent = JSONObject().apply {
                    put("id", "-Nx_$nowMs")
                    put("event", "pause")
                    put("tsMs", nowMs)
                    put("tsFormatted", nowFormatted)
                }

                val timeline = try {
                    JSONArray(currentSession.timeline_json)
                } catch (e: Exception) {
                    JSONArray()
                }
                timeline.put(pauseEvent)

                val updatedSession = currentSession.copy(
                    status = "PAUSED",
                    base_focus_time_ms = liveFocusMs,
                    last_event_ts_ms = nowMs,
                    base_focus_formatted = TimeEngine.formatDuration(liveFocusMs),
                    last_event_formatted = nowFormatted,
                    timeline_json = timeline.toString()
                )

                val payload = JSONObject().apply {
                    put("sessionId", currentSession.session_id)
                    put("status", "PAUSED")
                    put("tag", currentSession.tag)
                    put("taskTitle", currentSession.task_title)
                    put("baseFocusTimeMs", liveFocusMs)
                    put("baseFocusTimeFormatted", TimeEngine.formatDuration(liveFocusMs))
                    put("lastEventTimestampMs", nowMs)
                    put("lastEventFormatted", nowFormatted)
                    
                    val timelineObj = JSONObject()
                    for (i in 0 until timeline.length()) {
                        val item = timeline.getJSONObject(i)
                        timelineObj.put(item.getString("id"), item)
                    }
                    put("timeline", timelineObj)
                }.toString()

                val outboxItem = OutboxQueue(
                    mutation_id = "mut_$nowMs",
                    created_at_ms = nowMs,
                    routing_target = "RTDB_LIVE_SYNC",
                    action_type = "PAUSE",
                    payload_json = payload,
                    status = "PENDING"
                )

                db.withTransaction {
                    db.localActiveSessionDao().insertOrUpdateSession(updatedSession)
                    db.outboxQueueDao().insertQueueItem(outboxItem)
                }
                Log.d("FocusSessionDbHelper", "handlePauseFocus completed successfully for sessionId: ${currentSession.session_id}")
            } catch (e: Exception) {
                Log.e("FocusSessionDbHelper", "Error during handlePauseFocus transaction", e)
            }
        }
    }

    /**
     * Resume Focus handler
     */
    fun handleResumeFocus(context: Context) {
        scope.launch {
            try {
                val db = AppDatabase.getInstance(context)
                val currentSession = db.localActiveSessionDao().getActiveSession() ?: return@launch
                if (currentSession.status != "PAUSED") return@launch

                val nowMs = System.currentTimeMillis()
                val nowFormatted = TimeEngine.formatTimestamp(nowMs)

                val resumeEvent = JSONObject().apply {
                    put("id", "-Nx_$nowMs")
                    put("event", "resume")
                    put("tsMs", nowMs)
                    put("tsFormatted", nowFormatted)
                }

                val timeline = try {
                    JSONArray(currentSession.timeline_json)
                } catch (e: Exception) {
                    JSONArray()
                }
                timeline.put(resumeEvent)

                val updatedSession = currentSession.copy(
                    status = "FOCUSING",
                    last_event_ts_ms = nowMs,
                    last_event_formatted = nowFormatted,
                    timeline_json = timeline.toString()
                )

                val payload = JSONObject().apply {
                    put("sessionId", currentSession.session_id)
                    put("status", "FOCUSING")
                    put("tag", currentSession.tag)
                    put("taskTitle", currentSession.task_title)
                    put("baseFocusTimeMs", currentSession.base_focus_time_ms)
                    put("baseFocusTimeFormatted", currentSession.base_focus_formatted)
                    put("lastEventTimestampMs", nowMs)
                    put("lastEventFormatted", nowFormatted)
                    
                    val timelineObj = JSONObject()
                    for (i in 0 until timeline.length()) {
                        val item = timeline.getJSONObject(i)
                        timelineObj.put(item.getString("id"), item)
                    }
                    put("timeline", timelineObj)
                }.toString()

                val outboxItem = OutboxQueue(
                    mutation_id = "mut_$nowMs",
                    created_at_ms = nowMs,
                    routing_target = "RTDB_LIVE_SYNC",
                    action_type = "RESUME",
                    payload_json = payload,
                    status = "PENDING"
                )

                db.withTransaction {
                    db.localActiveSessionDao().insertOrUpdateSession(updatedSession)
                    db.outboxQueueDao().insertQueueItem(outboxItem)
                }
                Log.d("FocusSessionDbHelper", "handleResumeFocus completed successfully for sessionId: ${currentSession.session_id}")
            } catch (e: Exception) {
                Log.e("FocusSessionDbHelper", "Error during handleResumeFocus transaction", e)
            }
        }
    }

    /**
     * End Focus handler (including 10-Second Short-Circuit Guard in Step 1.4)
     */
    fun handleEndSession(context: Context, onWiped: () -> Unit, onArchived: (LocalHistoryVault) -> Unit) {
        scope.launch {
            try {
                val db = AppDatabase.getInstance(context)
                val currentSession = db.localActiveSessionDao().getActiveSession() ?: return@launch
                
                val nowMs = System.currentTimeMillis()
                val finalFocusMs = TimeEngine.calculateLiveElapsedMs(
                    currentSession.base_focus_time_ms,
                    currentSession.last_event_ts_ms,
                    currentSession.status
                )

                val MINIMUM_VALID_MS = 10 * 1000L // 10 seconds

                if (finalFocusMs < MINIMUM_VALID_MS) {
                    Log.w("FocusSessionDbHelper", "Session ignored: Only ${finalFocusMs}ms elapsed (under 10s threshold).")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Session too short to save! (< 10s)", Toast.LENGTH_SHORT).show()
                    }
                    
                    // Queue an RTDB wipe payload
                    val wipePayload = JSONObject().apply {
                        put("sessionId", currentSession.session_id)
                        put("status", "IDLE")
                    }.toString()

                    val outboxWipe = OutboxQueue(
                        mutation_id = "mut_wipe_$nowMs",
                        created_at_ms = nowMs,
                        routing_target = "RTDB_LIVE_SYNC",
                        action_type = "WIPE",
                        payload_json = wipePayload,
                        status = "PENDING"
                    )

                    db.withTransaction {
                        db.openHelper.writableDatabase.execSQL("DELETE FROM local_active_session")
                        db.outboxQueueDao().insertQueueItem(outboxWipe)
                    }

                    withContext(Dispatchers.Main) {
                        onWiped()
                    }
                    return@launch
                }

                // If valid (>= 10 seconds), archive!
                val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(nowMs))
                val startTimeMs = try {
                    currentSession.session_id.substringAfter("sess_").toLong()
                } catch (e: Exception) {
                    nowMs - finalFocusMs
                }

                // Count pauses
                var pauseCount = 0
                try {
                    val timeline = JSONArray(currentSession.timeline_json)
                    for (i in 0 until timeline.length()) {
                        if (timeline.getJSONObject(i).optString("event") == "pause") {
                            pauseCount++
                        }
                    }
                } catch (e: Exception) {
                    // Ignore
                }

                val archiveRecord = LocalHistoryVault(
                    record_id = currentSession.session_id,
                    date_string = dateStr,
                    subject = currentSession.tag,
                    task_title = currentSession.task_title,
                    start_time_ms = startTimeMs,
                    end_time_ms = nowMs,
                    total_focus_ms = finalFocusMs,
                    total_break_ms = currentSession.base_break_time_ms,
                    pause_count = pauseCount,
                    duration_formatted = TimeEngine.formatDuration(finalFocusMs),
                    start_time_formatted = TimeEngine.formatTimestamp(startTimeMs),
                    end_time_formatted = TimeEngine.formatTimestamp(nowMs),
                    is_synced_to_firestore = 0
                )

                // Outbox Archive Payload (direct vault or live sync)
                val archivePayload = JSONObject().apply {
                    put("recordId", currentSession.session_id)
                    put("dateString", dateStr)
                    put("subject", currentSession.tag)
                    put("taskTitle", currentSession.task_title)
                    put("startTimeMs", startTimeMs)
                    put("endTimeMs", nowMs)
                    put("totalFocusMs", finalFocusMs)
                    put("totalBreakMs", currentSession.base_break_time_ms)
                    put("pauseCount", pauseCount)
                    put("durationFormatted", TimeEngine.formatDuration(finalFocusMs))
                    put("startTimeFormatted", TimeEngine.formatTimestamp(startTimeMs))
                    put("endTimeFormatted", TimeEngine.formatTimestamp(nowMs))
                }.toString()

                val outboxArchive = OutboxQueue(
                    mutation_id = "mut_archive_${currentSession.session_id}",
                    created_at_ms = nowMs,
                    routing_target = "FIRESTORE_DIRECT_VAULT",
                    action_type = "ARCHIVE_SESSION",
                    payload_json = archivePayload,
                    status = "PENDING"
                )

                val outboxWipe = OutboxQueue(
                    mutation_id = "mut_wipe_$nowMs",
                    created_at_ms = nowMs,
                    routing_target = "RTDB_LIVE_SYNC",
                    action_type = "WIPE",
                    payload_json = JSONObject().apply {
                        put("sessionId", currentSession.session_id)
                        put("status", "IDLE")
                    }.toString(),
                    status = "PENDING"
                )

                db.withTransaction {
                    // Wipe active session scratchpad
                    db.openHelper.writableDatabase.execSQL("DELETE FROM local_active_session")
                    // Insert into local history vault
                    db.localHistoryVaultDao().insertRecord(archiveRecord)
                    // Queue outbox items
                    db.outboxQueueDao().insertQueueItem(outboxArchive)
                    db.outboxQueueDao().insertQueueItem(outboxWipe)
                }

                withContext(Dispatchers.Main) {
                    onArchived(archiveRecord)
                }
            } catch (e: Exception) {
                Log.e("FocusSessionDbHelper", "Error during handleEndSession transaction", e)
            }
        }
    }
}
