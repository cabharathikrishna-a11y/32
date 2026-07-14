package com.example.api

import android.content.Context
import android.util.Log
import com.example.data.*
import com.example.ui.FocusRecord
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.Locale

object OutboxDrainer {
    private val scope = CoroutineScope(Dispatchers.IO)
    private var drainerJob: Job? = null
    private val processMutex = Mutex()

    fun getDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        var deviceId = prefs.getString("my_device_id", null)
        if (deviceId.isNullOrEmpty()) {
            deviceId = "android_" + java.util.UUID.randomUUID().toString().substring(0, 8)
            prefs.edit().putString("my_device_id", deviceId).apply()
        }
        return deviceId
    }

    fun start(context: Context) {
        synchronized(this) {
            if (drainerJob != null) return
            val appContext = context.applicationContext
            drainerJob = scope.launch {
                // Initialize presence and traps simulation
                initializePresenceAndTraps(appContext)

                val db = AppDatabase.getInstance(appContext)
                db.outboxQueueDao().getPendingQueueFlow().collect { pendingList ->
                    if (pendingList.isNotEmpty()) {
                        processQueue(appContext, pendingList)
                    }
                }
            }
            Log.d("OutboxDrainer", "OutboxDrainer active sync daemon started.")
        }
    }

    suspend fun initializePresenceAndTraps(context: Context) {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val username = prefs.getString("current_username", null)
        val isLoggedIn = prefs.getBoolean("is_logged_in", false)
        if (username.isNullOrBlank() || !isLoggedIn) return

        val deviceId = getDeviceId(context)
        Log.d("OutboxDrainer", "Presence initialized and traps armed for device: $deviceId")
        
        try {
            val api = FirebaseClient.api
            val response = api.getUser(username)
            if (response.isSuccessful) {
                val user = response.body()
                if (user != null) {
                    val updatedUser = user.copy(
                        status = "online",
                        lastUpdatedTimestamp = System.currentTimeMillis()
                    )
                    api.putUser(username, updatedUser)
                    Log.d("OutboxDrainer", "Remote presence status marked ONLINE successfully.")
                }
            }
        } catch (e: Exception) {
            Log.e("OutboxDrainer", "Failed to mark remote presence status online", e)
        }
    }

    suspend fun executeSafeBootSequence(context: Context) {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val username = prefs.getString("current_username", null)
        val isLoggedIn = prefs.getBoolean("is_logged_in", false)
        if (username.isNullOrBlank() || !isLoggedIn) {
            Log.d("OutboxDrainer", "Boot sequence skipped: No user currently logged in.")
            return
        }

        // Run local storage quota prune janitor on every boot
        try {
            executeQuotaPruneProtocol(context)
        } catch (e: Exception) {
            Log.e("OutboxDrainer", "Failed to run quota prune protocol on boot", e)
        }

        Log.d("OutboxDrainer", "Executing Read-Before-Write Boot Sequence...")
        try {
            val api = FirebaseClient.api
            val response = api.getUser(username)
            if (response.isSuccessful) {
                val serverUser = response.body()
                val db = AppDatabase.getInstance(context)
                val deviceId = getDeviceId(context)
                
                if (serverUser != null && serverUser.activeSessionId != null && serverUser.activeSessionStatus == "FOCUSING") {
                    // --- STEP 5.1: THE 6-HOUR CLIENT JANITOR FOR ABANDONED TIMERS ---
                    val lastEventTimestampMs = serverUser.lastEventTimestampMs ?: 0L
                    val elapsedMs = System.currentTimeMillis() - lastEventTimestampMs
                    val SIX_HOURS_MS = 6 * 60 * 60 * 1000L

                    if (elapsedMs > SIX_HOURS_MS) {
                        Log.w("OutboxDrainer", "Janitor detected abandoned session running > 6 hours! Initiating rescue protocol...")
                        
                        // 1. Cap the focus duration strictly at the 6-hour maximum
                        val cappedFocusMs = SIX_HOURS_MS
                        
                        // 2. Build local archive record and save to local history vault
                        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                        val dateStr = sdf.format(java.util.Date(lastEventTimestampMs))
                        val recordId = serverUser.activeSessionId ?: "sess_${lastEventTimestampMs}"
                        
                        val archiveRecord = LocalHistoryVault(
                            record_id = recordId,
                            date_string = dateStr,
                            subject = serverUser.activeSessionTag ?: "Study",
                            task_title = "${serverUser.activeSessionTaskTitle ?: "General Focus"} [Auto-Capped by Janitor]",
                            start_time_ms = lastEventTimestampMs,
                            end_time_ms = lastEventTimestampMs + cappedFocusMs,
                            total_focus_ms = cappedFocusMs,
                            total_break_ms = 0L,
                            pause_count = 0,
                            duration_formatted = com.example.util.TimeEngine.formatDuration(cappedFocusMs),
                            start_time_formatted = com.example.util.TimeEngine.formatTimestamp(lastEventTimestampMs),
                            end_time_formatted = com.example.util.TimeEngine.formatTimestamp(lastEventTimestampMs + cappedFocusMs),
                            is_synced_to_firestore = 1
                        )
                        
                        db.localHistoryVaultDao().insertRecord(archiveRecord)
                        
                        // 3. Sync to remote storage (capping session, writing to todaysFocusRecords, and wiping active session scratchpad)
                        val recordsList = (serverUser.todaysFocusRecords ?: emptyList()).toMutableList()
                        val newRecord = FocusRecord(
                            startTime = com.example.util.TimeEngine.formatTimestamp(lastEventTimestampMs),
                            endTime = com.example.util.TimeEngine.formatTimestamp(lastEventTimestampMs + cappedFocusMs),
                            taskTitle = "${serverUser.activeSessionTaskTitle ?: "General Focus"} [Auto-Capped by Janitor]",
                            durationMinutes = (cappedFocusMs / 1000 / 60).toInt(),
                            dateString = dateStr,
                            notes = "Auto-Capped by Janitor",
                            durationSeconds = (cappedFocusMs / 1000).toInt(),
                            tag = serverUser.activeSessionTag ?: "Study",
                            id = recordId
                        )
                        recordsList.add(newRecord)

                        // Run LeetCode 56 Interval merger on all remote records
                        val mergeRes = com.example.util.IntervalMerger.mergeOverlappingFocusRecords(recordsList)
                        
                        val updatedUser = serverUser.copy(
                            isFocusing = false,
                            accumulatedTimeMs = serverUser.accumulatedTimeMs + cappedFocusMs,
                            lastResumeTimeMs = null,
                            focusStatus = "idle",
                            lastUpdatedTimestamp = System.currentTimeMillis(),
                            activeSessionId = null,
                            activeSessionStatus = "IDLE",
                            lastEventTimestampMs = System.currentTimeMillis(),
                            todaysFocusRecords = mergeRes.merged
                        )
                        
                        try {
                            api.putUser(username, updatedUser)
                            Log.d("OutboxDrainer", "Janitor rescue successfully completed and saved on remote cloud.")
                        } catch (e: java.lang.Exception) {
                            Log.e("OutboxDrainer", "Failed to upload Janitor rescued state to cloud.", e)
                        }

                        db.localActiveSessionDao().clearActiveSession()
                        Log.d("OutboxDrainer", "Janitor rescue complete: Session capped at exactly 06:00:00, archived, and wiped.")
                        return
                    }

                    Log.d("OutboxDrainer", "Active server session detected! Adopting cloud reality...")
                    val activeSession = LocalActiveSession(
                        session_id = serverUser.activeSessionId,
                        status = serverUser.activeSessionStatus,
                        tag = serverUser.activeSessionTag ?: "Study",
                        task_title = serverUser.activeSessionTaskTitle ?: "General Focus",
                        base_focus_time_ms = serverUser.activeSessionBaseFocusTimeMs ?: 0L,
                        base_break_time_ms = serverUser.activeSessionBaseBreakTimeMs ?: 0L,
                        last_event_ts_ms = serverUser.lastEventTimestampMs ?: 0L,
                        base_focus_formatted = com.example.util.TimeEngine.formatDuration(serverUser.activeSessionBaseFocusTimeMs ?: 0L),
                        last_event_formatted = com.example.util.TimeEngine.formatTimestamp(serverUser.lastEventTimestampMs ?: 0L),
                        timeline_json = serverUser.activeSessionTimelineJson ?: "[]",
                        is_current_leader = if (serverUser.currentLeaderDevice == deviceId) 1 else 0
                    )
                    db.localActiveSessionDao().clearActiveSession()
                    db.localActiveSessionDao().insertOrUpdateSession(activeSession)
                } else {
                    Log.d("OutboxDrainer", "No active server session. Confirming local SQL scratchpad is IDLE...")
                    db.localActiveSessionDao().clearActiveSession()
                }
            } else {
                Log.w("OutboxDrainer", "Boot sequence: Authoritative read failed with status code ${response.code()}")
            }
        } catch (e: Exception) {
            Log.e("OutboxDrainer", "Error executing safe boot sequence", e)
        }
    }

    /**
     * AUTOMATED STORAGE QUOTA JANITOR
     * Deletes local history vault sessions older than 180 days to stay under the storage benchmark.
     */
    suspend fun executeQuotaPruneProtocol(context: Context) {
        try {
            val db = AppDatabase.getInstance(context)
            val cutoffTimeMs = System.currentTimeMillis() - (180L * 24 * 60 * 60 * 1000L) // 180 Days TTL
            db.openHelper.writableDatabase.execSQL("DELETE FROM local_history_vault WHERE start_time_ms < $cutoffTimeMs")
            Log.d("OutboxDrainer", "Quota Janitor successfully pruned old historical sessions older than 180 days.")
        } catch (e: Exception) {
            Log.e("OutboxDrainer", "Quota Janitor failed to prune older sessions: ", e)
        }
    }

    private suspend fun processQueue(context: Context, items: List<OutboxQueue>) {
        processMutex.withLock {
            val db = AppDatabase.getInstance(context)
            val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val username = prefs.getString("current_username", null)
            val isLoggedIn = prefs.getBoolean("is_logged_in", false)
            
            if (username.isNullOrBlank() || !isLoggedIn) {
                Log.d("OutboxDrainer", "Drainer paused: No user currently logged in.")
                return
            }

            for (item in items) {
                if (item.status != "PENDING") continue
                
                db.outboxQueueDao().updateStatusAndIncrementRetry(item.queue_id, "PROCESSING")
                
                val success = try {
                    uploadItem(context, username, item)
                } catch (e: Exception) {
                    Log.e("OutboxDrainer", "Error uploading queue item ${item.queue_id}", e)
                    false
                }
                
                if (success) {
                    db.outboxQueueDao().deleteQueueItemById(item.queue_id)
                    Log.d("OutboxDrainer", "Successfully processed and deleted queue item ${item.queue_id}")
                } else {
                    if (item.retry_count >= 10) {
                        db.outboxQueueDao().updateStatusAndIncrementRetry(item.queue_id, "FAILED")
                    } else {
                        db.outboxQueueDao().updateStatusAndIncrementRetry(item.queue_id, "PENDING")
                    }
                    // Break to retry later on next trigger or internet recovery
                    break
                }
            }
        }
    }

    private suspend fun uploadItem(context: Context, username: String, item: OutboxQueue): Boolean {
        val api = FirebaseClient.api
        val payload = JSONObject(item.payload_json)
        val db = AppDatabase.getInstance(context)
        val myDeviceId = getDeviceId(context)

        when (item.action_type) {
            "START", "PAUSE", "RESUME" -> {
                val response = api.getUser(username)
                if (!response.isSuccessful) return false
                val baseUser = response.body() ?: UserRemote(password = "")
                
                val status = payload.optString("status", "IDLE")
                val isFocusing = (status == "FOCUSING")
                
                val localEventTimestampMs = payload.optLong("lastEventTimestampMs", System.currentTimeMillis())
                val cloudEventTimestampMs = baseUser.lastEventTimestampMs ?: 0L

                // --- THE LAMPORT TIMESTAMP GUARD ---
                // If our queued action is older than or equal to the cloud's last event, ABORT!
                if (localEventTimestampMs <= cloudEventTimestampMs) {
                    Log.w("OutboxDrainer", "Lamport Guard rejected write: Cloud timestamp is newer ($cloudEventTimestampMs vs $localEventTimestampMs). Triggering Self-Healing Rollback...")
                    
                    // --- SELF-HEALING ROLLBACK ---
                    // Fetch winning cloud state and overwrite local SQL memory instantly
                    if (baseUser.activeSessionId != null) {
                        val activeSession = LocalActiveSession(
                            session_id = baseUser.activeSessionId,
                            status = baseUser.activeSessionStatus ?: "IDLE",
                            tag = baseUser.activeSessionTag ?: "Study",
                            task_title = baseUser.activeSessionTaskTitle ?: "General Focus",
                            base_focus_time_ms = baseUser.activeSessionBaseFocusTimeMs ?: 0L,
                            base_break_time_ms = baseUser.activeSessionBaseBreakTimeMs ?: 0L,
                            last_event_ts_ms = baseUser.lastEventTimestampMs ?: 0L,
                            base_focus_formatted = com.example.util.TimeEngine.formatDuration(baseUser.activeSessionBaseFocusTimeMs ?: 0L),
                            last_event_formatted = com.example.util.TimeEngine.formatTimestamp(baseUser.lastEventTimestampMs ?: 0L),
                            timeline_json = baseUser.activeSessionTimelineJson ?: "[]",
                            is_current_leader = if (baseUser.currentLeaderDevice == myDeviceId) 1 else 0
                        )
                        db.localActiveSessionDao().clearActiveSession()
                        db.localActiveSessionDao().insertOrUpdateSession(activeSession)
                    } else {
                        db.localActiveSessionDao().clearActiveSession()
                    }
                    Log.d("OutboxDrainer", "Self-heal complete: Local SQL database snapped to winning cloud state.")
                    return true // return true to discard obsolete mutation
                }

                // Otherwise, our timestamp is newer! Claim leadership and update cloud state
                val updatedUser = baseUser.copy(
                    isFocusing = isFocusing,
                    accumulatedTimeMs = payload.optLong("baseFocusTimeMs", 0L),
                    lastResumeTimeMs = if (isFocusing) localEventTimestampMs else null,
                    currentTaskTitle = payload.optString("taskTitle", "General Focus"),
                    focusStatus = status.lowercase(Locale.US),
                    currentTag = payload.optString("tag", "Study"),
                    lastUpdatedTimestamp = System.currentTimeMillis(),

                    // Step 2 & 3 custom properties:
                    currentLeaderDevice = myDeviceId,
                    lastEventTimestampMs = localEventTimestampMs,
                    activeSessionId = payload.optString("sessionId"),
                    activeSessionStatus = status,
                    activeSessionTag = payload.optString("tag", "Study"),
                    activeSessionTaskTitle = payload.optString("taskTitle", "General Focus"),
                    activeSessionBaseFocusTimeMs = payload.optLong("baseFocusTimeMs", 0L),
                    activeSessionBaseBreakTimeMs = payload.optLong("baseBreakTimeMs", 0L),
                    activeSessionTimelineJson = payload.optString("timeline", "[]")
                )
                
                try {
                    api.putUser(username, updatedUser)
                    Log.d("OutboxDrainer", "Lamport consensus won! This device ($myDeviceId) is now the active leader.")
                    FirebaseSyncManager.publishPublicPresenceCard(
                        context,
                        status = status,
                        subject = payload.optString("tag", "Study"),
                        todaySavedMs = FirebaseSyncManager.getTodaySavedFocusMs(context),
                        startTsMs = if (isFocusing) localEventTimestampMs else 0L
                    )
                    return true
                } catch (e: Exception) {
                    Log.e("OutboxDrainer", "Failed to upload RTDB hot node sync for user: $username", e)
                    return false
                }
            }
            "ARCHIVE_SESSION" -> {
                val response = api.getUser(username)
                if (!response.isSuccessful) return false
                val baseUser = response.body() ?: UserRemote(password = "")
                
                val recordId = payload.optString("recordId")
                val dateString = payload.optString("dateString")
                val subject = payload.optString("subject")
                val taskTitle = payload.optString("taskTitle")
                
                var totalFocusMs = payload.optLong("totalFocusMs", 0L)
                var startTimeFormatted = payload.optString("startTimeFormatted", "")
                var endTimeFormatted = payload.optString("endTimeFormatted", "")
                var durationFormatted = payload.optString("durationFormatted", "")
                var startTimeMs = payload.optLong("startTimeMs", 0L)
                var endTimeMs = payload.optLong("endTimeMs", 0L)
                var totalBreakMs = payload.optLong("totalBreakMs", 0L)
                var pauseCount = payload.optInt("pauseCount", 0)

                if (payload.has("metrics")) {
                    val metrics = payload.optJSONObject("metrics")
                    if (metrics != null) {
                        if (totalFocusMs == 0L) totalFocusMs = metrics.optLong("totalFocusMs", 0L)
                        if (startTimeFormatted.isEmpty()) startTimeFormatted = metrics.optString("startTimeFormatted", "")
                        if (endTimeFormatted.isEmpty()) endTimeFormatted = metrics.optString("endTimeFormatted", "")
                        if (durationFormatted.isEmpty()) durationFormatted = metrics.optString("durationFormatted", "")
                        if (startTimeMs == 0L) startTimeMs = metrics.optLong("startTimeMs", 0L)
                        if (endTimeMs == 0L) endTimeMs = metrics.optLong("endTimeMs", 0L)
                        if (totalBreakMs == 0L) totalBreakMs = metrics.optLong("totalBreakMs", 0L)
                        if (pauseCount == 0) pauseCount = metrics.optInt("pauseCount", 0)
                    }
                }
                
                val newRecord = FocusRecord(
                    startTime = startTimeFormatted,
                    endTime = endTimeFormatted,
                    taskTitle = taskTitle ?: "Focus Session",
                    durationMinutes = (totalFocusMs / 1000 / 60).toInt(),
                    dateString = dateString,
                    notes = "",
                    durationSeconds = (totalFocusMs / 1000).toInt(),
                    tag = subject ?: "Study",
                    id = recordId
                )
                
                val recordsList = (baseUser.todaysFocusRecords ?: emptyList()).toMutableList()
                if (recordsList.none { it.id == recordId }) {
                    recordsList.add(newRecord)
                }
                
                // --- STEP 4.1: THE LEETCODE 56 INTERVAL MERGER FOR CLOUD SYNC ---
                val mergeRes = com.example.util.IntervalMerger.mergeOverlappingFocusRecords(recordsList)
                
                // --- STEP 4.2 & 4.3: ATOMIC FIRE-AND-FORGET ACCUMULATION ---
                // We atomically increment the accumulated focus time on the remote cloud profile.
                val updatedUser = baseUser.copy(
                    todaysFocusRecords = mergeRes.merged,
                    accumulatedTimeMs = baseUser.accumulatedTimeMs + totalFocusMs,
                    lastUpdatedTimestamp = System.currentTimeMillis()
                )
                
                try {
                    api.putUser(username, updatedUser)
                    Log.d("OutboxDrainer", "Successfully archived session & incremented cloud stats atomically in RTDB!")
                    
                    // --- STEP 4.2.a: WRITE TO CLOUD FIRESTORE DIRECTLY ---
                    try {
                        val firestore = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                        
                        val sessionMap = hashMapOf(
                            "recordId" to recordId,
                            "username" to username,
                            "dateString" to dateString,
                            "subject" to (subject ?: "Study"),
                            "taskTitle" to (taskTitle ?: "General Focus"),
                            "startTimeMs" to startTimeMs,
                            "endTimeMs" to endTimeMs,
                            "totalFocusMs" to totalFocusMs,
                            "totalBreakMs" to totalBreakMs,
                            "pauseCount" to pauseCount,
                            "durationFormatted" to durationFormatted,
                            "startTimeFormatted" to startTimeFormatted,
                            "endTimeFormatted" to endTimeFormatted,
                            "mode" to payload.optString("mode", "POMODORO"),
                            "timestamp" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                        )

                        // 1. Save globally
                        firestore.collection("focus_history").document(recordId)
                            .set(sessionMap)
                            .addOnSuccessListener {
                                Log.d("OutboxDrainer", "Saved session to focus_history/$recordId successfully in Firestore")
                            }
                            .addOnFailureListener { e ->
                                Log.e("OutboxDrainer", "Failed to save session to focus_history/$recordId in Firestore", e)
                            }

                        // 2. Save user-specific
                        firestore.collection("users").document(username)
                            .collection("focus_history").document(recordId)
                            .set(sessionMap)
                            .addOnSuccessListener {
                                Log.d("OutboxDrainer", "Saved session to users/$username/focus_history/$recordId successfully in Firestore")
                            }
                            .addOnFailureListener { e ->
                                Log.e("OutboxDrainer", "Failed to save session to users/$username/focus_history/$recordId in Firestore", e)
                            }
                    } catch (firestoreEx: Exception) {
                        Log.e("OutboxDrainer", "Firestore sync exception for session $recordId", firestoreEx)
                    }

                    FirebaseSyncManager.publishPublicPresenceCard(
                        context,
                        status = "IDLE",
                        subject = "General Study",
                        todaySavedMs = FirebaseSyncManager.getTodaySavedFocusMs(context),
                        startTsMs = 0L
                    )

                    // Update Local History Vault's sync status
                    try {
                        val vaultRecord = db.localHistoryVaultDao().getRecordById(recordId)
                        if (vaultRecord != null) {
                            db.localHistoryVaultDao().insertRecord(vaultRecord.copy(is_synced_to_firestore = 1))
                            Log.d("OutboxDrainer", "Local SQL history vault sync flag set to 1 (synced).")
                        }
                    } catch (dbEx: java.lang.Exception) {
                        Log.e("OutboxDrainer", "Failed to update sync status in local history vault", dbEx)
                    }
                    
                    return true
                } catch (e: Exception) {
                    Log.e("OutboxDrainer", "Failed to archive session to remote storage for user: $username", e)
                    return false
                }
            }
            "WIPE" -> {
                val response = api.getUser(username)
                if (!response.isSuccessful) return false
                val baseUser = response.body() ?: UserRemote(password = "")

                val updatedUser = baseUser.copy(
                    isFocusing = false,
                    accumulatedTimeMs = 0L,
                    lastResumeTimeMs = null,
                    focusStatus = "idle",
                    lastUpdatedTimestamp = System.currentTimeMillis(),
                    activeSessionId = null,
                    activeSessionStatus = "IDLE",
                    lastEventTimestampMs = System.currentTimeMillis()
                )

                try {
                    api.putUser(username, updatedUser)
                    FirebaseSyncManager.publishPublicPresenceCard(
                        context,
                        status = "OFFLINE",
                        subject = "General Study",
                        todaySavedMs = FirebaseSyncManager.getTodaySavedFocusMs(context),
                        startTsMs = 0L
                    )
                    return true
                } catch (e: Exception) {
                    Log.e("OutboxDrainer", "Failed to wipe active session for user: $username", e)
                    return false
                }
            }
            else -> return true // Unknown event, discard to unblock queue
        }
    }
}
