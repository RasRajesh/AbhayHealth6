package com.example.abhayhealth.feature.medicines

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Room Entity storing persistent timestamped records of every medicine dose taken or skipped,
 * synced with Cloud Firestore (`/users/{userId}/medicine_intake_logs/{logId}`) for historical
 * adherence tracking and doctor-shareable clinical PDF summaries.
 */
@Entity(tableName = "medicine_intake_logs")
data class MedicineIntakeLogEntity(
    @PrimaryKey
    val id: String = "intake_${UUID.randomUUID().toString().replace("-", "_")}",
    val accountUid: String,
    val medicationId: String,
    val medicationName: String,
    val dosage: String,
    val timeSlot: String,
    val takenAtTimestamp: Long = System.currentTimeMillis(),
    val status: String = STATUS_TAKEN,
    val notes: String = ""
) {
    companion object {
        const val STATUS_TAKEN = "TAKEN"
        const val STATUS_SKIPPED = "SKIPPED"
    }
}
