package com.example.abhayhealth.feature.medicines

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Room Data Access Object for persistent medicine intake history logs.
 */
@Dao
interface MedicineIntakeLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertOrUpdate(log: MedicineIntakeLogEntity): Long

    @Query("SELECT * FROM medicine_intake_logs WHERE accountUid = :accountUid ORDER BY takenAtTimestamp DESC")
    fun observeLogsByAccount(accountUid: String): Flow<List<MedicineIntakeLogEntity>>

    @Query("SELECT * FROM medicine_intake_logs WHERE accountUid = :accountUid ORDER BY takenAtTimestamp DESC")
    fun getLogsByAccountSync(accountUid: String): List<MedicineIntakeLogEntity>

    @Query("SELECT * FROM medicine_intake_logs WHERE id = :id AND accountUid = :accountUid LIMIT 1")
    fun getLogByIdSync(id: String, accountUid: String): MedicineIntakeLogEntity?

    @Query("DELETE FROM medicine_intake_logs WHERE id = :id AND accountUid = :accountUid")
    fun deleteLog(id: String, accountUid: String): Int

    @Query("DELETE FROM medicine_intake_logs WHERE accountUid = :accountUid")
    fun deleteAllByAccount(accountUid: String): Int

    @Query("DELETE FROM medicine_intake_logs")
    fun deleteAllLogs(): Int
}
