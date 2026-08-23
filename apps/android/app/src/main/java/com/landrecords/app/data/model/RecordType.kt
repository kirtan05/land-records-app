package com.landrecords.app.data.model

/**
 * The four kinds of document a survey can hold. Order here is the display order in Survey detail.
 * Labels are bilingual — Gujarati is primary (the data's language), English is the helper line.
 */
enum class RecordType(
    val englishLabel: String,
    val gujaratiLabel: String,
    /** AnyRoR "record type" cascade value, where applicable (see docs/anyror). */
    val anyrorRecordValue: String?,
) {
    INTEGRATED("Integrated Survey Record", "સંકલિત સર્વે રેકોર્ડ", "8"),
    VF712("Old Scanned VF-7/12", "જૂનું સ્કેન થયેલ ૭/૧૨", "11"),
    DEEDS("Registered Deeds", "નોંધાયેલ દસ્તાવેજ", null),
    IRCMS("Land Cases (iRCMS)", "જમીન કેસ (iRCMS)", null),
}
