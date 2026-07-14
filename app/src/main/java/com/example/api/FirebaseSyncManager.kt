package com.example.api

import android.content.Context
import com.example.ui.FocusRecord
import com.example.util.FocusTimerManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.*

object FirebaseSyncManager {
    
    private val _friendsLiveStatus = MutableStateFlow<Map<String, UserRemote>>(emptyMap())
    val friendsLiveStatus = _friendsLiveStatus.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var listenJob: Job? = null
    private var heartbeatJob: Job? = null

    fun getTodaySavedFocusMs(context: Context): Long {
        return try {
            val records = FocusTimerManager.loadFocusRecords(context)
            val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val seconds = records.sumOf {
                FocusTimerManager.getOverlapSecondsForDate(it, todayStr)
            }
            seconds * 1000L
        } catch (e: Exception) {
            android.util.Log.e("FirebaseSyncManager", "Failed to compute today's saved focus ms", e)
            0L
        }
    }

    fun publishPublicPresenceCard(context: Context, status: String, subject: String, todaySavedMs: Long, startTsMs: Long) {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val username = prefs.getString("current_username", null)
        val isLoggedIn = prefs.getBoolean("is_logged_in", false)
        if (username.isNullOrBlank() || !isLoggedIn) return

        scope.launch {
            try {
                val card = PublicPresence(
                    status = status,
                    subject = subject,
                    todaySavedFocusMs = todaySavedMs,
                    todaySavedFormatted = com.example.util.TimeEngine.formatDuration(todaySavedMs),
                    lastStartTimestampMs = if (status == "FOCUSING") startTsMs else 0L,
                    lastUpdatedFormatted = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()),
                    online = (status != "OFFLINE")
                )
                FirebaseClient.api.putPublicPresence(username, card)
                android.util.Log.d("FirebaseSyncManager", "Published public presence for $username: [$status] studying $subject")
            } catch (e: Exception) {
                android.util.Log.e("FirebaseSyncManager", "Failed to publish public presence card", e)
            }
        }
    }

    fun listenToFriends(context: Context, friendUsernames: List<String>) {
        listenJob?.cancel()
        listenJob = scope.launch {
            while (isActive) {
                try {
                    val response = FirebaseClient.api.getPublicPresenceList()
                    if (response.isSuccessful) {
                        val rawPresences = response.body() ?: emptyMap()
                        val mappedFriends = mutableMapOf<String, UserRemote>()
                        
                        friendUsernames.forEach { friend ->
                            val presence = rawPresences[friend] ?: PublicPresence(
                                status = "OFFLINE",
                                subject = "General Study",
                                todaySavedFocusMs = 0L,
                                lastStartTimestampMs = 0L,
                                online = false
                            )
                            
                            val isFocusing = (presence.status == "FOCUSING")
                            val userRemote = UserRemote(
                                password = "stub",
                                name = friend.replaceFirstChar { it.uppercase() },
                                nickname = friend.replaceFirstChar { it.uppercase() },
                                emoji = "👨‍💻",
                                isFocusing = isFocusing,
                                accumulatedTimeMs = presence.todaySavedFocusMs ?: 0L,
                                lastResumeTimeMs = if (isFocusing) (presence.lastStartTimestampMs ?: 0L) else null,
                                currentTaskTitle = presence.subject ?: "General Study",
                                todaysFocusRecords = emptyList(), // Avoid double counting
                                lastUpdatedTimestamp = System.currentTimeMillis(),
                                focusStatus = presence.status ?: "idle",
                                currentTag = presence.subject ?: "Study",
                                isGoogleUser = true,
                                status = presence.status ?: "idle"
                            )
                            mappedFriends[friend] = userRemote
                        }
                        _friendsLiveStatus.value = mappedFriends
                    }
                } catch (e: Exception) {
                    android.util.Log.e("FirebaseSyncManager", "Failed to fetch public presence map", e)
                }
                delay(5000) // Poll every 5 seconds
            }
        }
    }

    fun pushMyStatus(context: Context, myUsername: String, isFocusing: Boolean, currentTask: String) {
        val status = if (isFocusing) "FOCUSING" else "IDLE"
        val todaySavedMs = getTodaySavedFocusMs(context)
        val nowMs = System.currentTimeMillis()
        publishPublicPresenceCard(context, status, currentTask, todaySavedMs, nowMs)
    }

    fun startPublicHeartbeatLoop(context: Context) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(60000) // fires every 60 seconds
                try {
                    val db = com.example.data.AppDatabase.getInstance(context)
                    val activeSessionDao = db.localActiveSessionDao()
                    val activeSession = activeSessionDao.getActiveSession()
                    
                    if (activeSession != null && activeSession.status == "FOCUSING") {
                        val nowMs = System.currentTimeMillis()
                        val elapsedDelta = nowMs - activeSession.last_event_ts_ms
                        
                        if (elapsedDelta >= 60000L) {
                            android.util.Log.d("FirebaseSyncManager", "Heartbeat pulse: Absorbing 60s unsaved delta into public base time...")
                            
                            val newSavedTotal = activeSession.base_focus_time_ms + elapsedDelta
                            val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                            val username = prefs.getString("current_username", null)
                            val isLoggedIn = prefs.getBoolean("is_logged_in", false)
                            
                            if (!username.isNullOrBlank() && isLoggedIn) {
                                val todaySavedMs = getTodaySavedFocusMs(context) + elapsedDelta
                                
                                val card = PublicPresence(
                                    status = "FOCUSING",
                                    subject = activeSession.tag,
                                    todaySavedFocusMs = todaySavedMs,
                                    todaySavedFormatted = com.example.util.TimeEngine.formatDuration(todaySavedMs),
                                    lastStartTimestampMs = nowMs,
                                    lastUpdatedFormatted = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()),
                                    online = true
                                )
                                FirebaseClient.api.putPublicPresence(username, card)
                                
                                val updatedSession = activeSession.copy(
                                    base_focus_time_ms = newSavedTotal,
                                    last_event_ts_ms = nowMs
                                )
                                activeSessionDao.insertOrUpdateSession(updatedSession)
                                android.util.Log.d("FirebaseSyncManager", "Heartbeat pulse: SQL and Cloud presence successfully synchronized.")
                            }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("FirebaseSyncManager", "Error in public heartbeat pulse", e)
                }
            }
        }
    }

    fun stopPublicHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    fun stopListening(context: Context) {
        listenJob?.cancel()
        listenJob = null
    }
}
