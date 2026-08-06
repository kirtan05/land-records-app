package com.landrecords.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PropertyDao {
    @Query("SELECT * FROM properties ORDER BY district, taluka, village")
    fun observeAll(): Flow<List<PropertyEntity>>

    @Query("SELECT * FROM properties WHERE id = :id")
    suspend fun byId(id: Long): PropertyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(property: PropertyEntity): Long

    @Query("SELECT COUNT(*) FROM properties")
    suspend fun count(): Int
}

@Dao
interface SurveyDao {
    @Query("SELECT * FROM surveys WHERE propertyId = :propertyId ORDER BY normalized")
    fun observeForProperty(propertyId: Long): Flow<List<SurveyEntity>>

    @Query("SELECT * FROM surveys WHERE id = :id")
    fun observeById(id: Long): Flow<SurveyEntity?>

    @Query("SELECT * FROM surveys WHERE surveyNo LIKE '%' || :q || '%' ORDER BY normalized")
    fun search(q: String): Flow<List<SurveyEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(survey: SurveyEntity): Long
}

@Dao
interface RecordDao {
    @Query("SELECT * FROM records WHERE surveyId = :surveyId ORDER BY type")
    fun observeForSurvey(surveyId: Long): Flow<List<RecordEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: RecordEntity): Long

    @Update
    suspend fun update(record: RecordEntity)

    @Transaction
    @Query("SELECT COUNT(*) FROM records WHERE surveyId = :surveyId")
    suspend fun countForSurvey(surveyId: Long): Int
}
