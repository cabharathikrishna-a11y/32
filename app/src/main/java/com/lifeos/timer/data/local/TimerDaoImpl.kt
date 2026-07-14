package com.lifeos.timer.data.local

import com.example.data.AppDatabase
import com.example.data.LocalHistoryVault
import com.example.data.OutboxQueue

class TimerDaoImpl(
    private val db: AppDatabase
) : TimerDao {
    override suspend fun getTodayManualFocusTimeMs(date: String): Long {
        return db.localHistoryVaultDao().getTodayManualFocusTimeMs(date)
    }

    override suspend fun archiveToVault(record: LocalHistoryVault) {
        db.localHistoryVaultDao().insertRecord(record)
    }

    override suspend fun enqueueOutboxMutation(mutation: OutboxMutation) {
        val outboxItem = OutboxQueue(
            mutation_id = mutation.mutationId,
            created_at_ms = mutation.createdAtMs,
            routing_target = mutation.routingTarget,
            action_type = mutation.actionType,
            payload_json = mutation.payloadJson
        )
        db.outboxQueueDao().insertQueueItem(outboxItem)
    }
}
