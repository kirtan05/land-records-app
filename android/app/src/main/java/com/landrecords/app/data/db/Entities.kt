package com.landrecords.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.landrecords.app.data.model.RecordType

/**
 * A saved property: a location (State ▸ District ▸ Taluka ▸ Village) that owns a list of surveys.
 * Gujarati names are stored alongside the Latin ones so the UI can show both without a lookup table.
 */
@Entity(tableName = "properties")
data class PropertyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val state: String,
    val district: String,
    val taluka: String,
    val village: String,
    val districtGu: String = "",
    val talukaGu: String = "",
    val villageGu: String = "",
)

/** One survey number under a property, with the at-a-glance metadata shown on its card. */
@Entity(
    tableName = "surveys",
    indices = [Index("propertyId"), Index("normalized")],
)
data class SurveyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val propertyId: Long,
    /** Human survey number, e.g. "221/p" or "845/અ". */
    val surveyNo: String,
    /** Normalized token used for storage folder + matching, e.g. "221_P". */
    val normalized: String,
    val area: String = "",
    val assessment: String = "",
    val tenure: String = "",
    val landUse: String = "",
    /** "As of" date string from the source page, if known. */
    val asOf: String = "",
)

/** A concrete document set of one [RecordType] filed under a survey. */
@Entity(
    tableName = "records",
    indices = [Index("surveyId")],
)
data class RecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val surveyId: Long,
    val type: RecordType,
    /** Number of documents/pages captured for this record. */
    val docCount: Int = 0,
    val asOf: String = "",
    /** Absolute path to the rendered PDF in the visible library tree, if fetched. */
    val pdfPath: String? = null,
    /** Path to the hidden .source/ (raw HTML + JSON + scans) for re-render/export. */
    val sourcePath: String? = null,
    /** Epoch millis when last fetched; null = not fetched yet (shows "Get record"). */
    val fetchedAt: Long? = null,
)
