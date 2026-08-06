package com.landrecords.app.data.db

import androidx.room.TypeConverter
import com.landrecords.app.data.model.RecordType

class Converters {
    @TypeConverter
    fun recordTypeToString(type: RecordType): String = type.name

    @TypeConverter
    fun stringToRecordType(value: String): RecordType = RecordType.valueOf(value)
}
