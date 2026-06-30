package com.medtroniclabs.microcoaching.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.medtroniclabs.microcoaching.data.db.entity.ChwQuizQuestionStateEntity

@Dao
interface ChwQuizQuestionStateDao {

    /** Synced baseline quiz states for a CHW (the on-device engine layers replay on top). */
    @Query("SELECT * FROM chw_quiz_question_state WHERE chw_id = :chwId")
    suspend fun getAllForChw(chwId: String): List<ChwQuizQuestionStateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(states: List<ChwQuizQuestionStateEntity>)

    @Query("DELETE FROM chw_quiz_question_state WHERE chw_id = :chwId")
    suspend fun deleteForChw(chwId: String)

    @Query("SELECT COUNT(*) FROM chw_quiz_question_state")
    suspend fun count(): Int
}
