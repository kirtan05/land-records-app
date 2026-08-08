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
    @Query("SELECT * FROM surveys ORDER BY propertyId, normalized")
    fun observeAll(): Flow<List<SurveyEntity>>

    @Query("SELECT * FROM surveys WHERE propertyId = :propertyId ORDER BY normalized")
    fun observeForProperty(propertyId: Long): Flow<List<SurveyEntity>>

    @Query("SELECT * FROM surveys WHERE id = :id")
    fun observeById(id: Long): Flow<SurveyEntity?>

    @Query("SELECT * FROM surveys WHERE id = :id")
    suspend fun byIdOnce(id: Long): SurveyEntity?

    @Query("SELECT * FROM surveys WHERE surveyNo LIKE '%' || :q || '%' ORDER BY normalized")
    fun search(q: String): Flow<List<SurveyEntity>>

    /**
     * Resolve a survey by its full place (district + taluka + village) + human survey number —
     * used to link seeded records. Scoping to district+taluka (not just village name) keeps the
     * LIMIT 1 lookup deterministic when two properties share a village name across places.
     */
    @Query(
        "SELECT s.* FROM surveys s JOIN properties p ON s.propertyId = p.id " +
            "WHERE p.district = :district AND p.taluka = :taluka AND p.village = :village " +
            "AND s.surveyNo = :surveyNo LIMIT 1",
    )
    suspend fun findByVillageAndNo(
        district: String,
        taluka: String,
        village: String,
        surveyNo: String,
    ): SurveyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(survey: SurveyEntity): Long
}

@Dao
interface RecordDao {
    @Query("SELECT * FROM records ORDER BY surveyId")
    fun observeAll(): Flow<List<RecordEntity>>

    @Query("SELECT * FROM records WHERE surveyId = :surveyId ORDER BY type")
    fun observeForSurvey(surveyId: Long): Flow<List<RecordEntity>>

    // Match by the stored converter value (RecordType.name) to avoid a converter on a query param.
    @Query("SELECT * FROM records WHERE surveyId = :surveyId AND type = :typeName LIMIT 1")
    suspend fun find(surveyId: Long, typeName: String): RecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: RecordEntity): Long

    @Update
    suspend fun update(record: RecordEntity)

    @Transaction
    @Query("SELECT COUNT(*) FROM records WHERE surveyId = :surveyId")
    suspend fun countForSurvey(surveyId: Long): Int

    /** Set (or clear, with null) the export colour mark on one record. */
    @Query("UPDATE records SET mark = :mark WHERE id = :id")
    suspend fun setMark(id: Long, mark: String?)

    /** Every marked record that actually has a PDF, joined to its survey + village, grouped by colour. */
    @Query(
        "SELECT r.id AS recordId, r.type AS type, r.mark AS mark, r.pdfPath AS pdfPath, " +
            "s.surveyNo AS surveyNo, p.village AS villageEn, p.villageGu AS villageGu " +
            "FROM records r " +
            "JOIN surveys s ON r.surveyId = s.id " +
            "JOIN properties p ON s.propertyId = p.id " +
            "WHERE r.mark IS NOT NULL AND r.pdfPath IS NOT NULL " +
            "ORDER BY r.mark, p.village, s.normalized",
    )
    fun observeMarked(): Flow<List<MarkedRecordRow>>
}
