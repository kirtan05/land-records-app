package com.landrecords.app.data.place

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * Held to the REAL bundled cascade assets, not to hand-written fixtures — the failure this
 * fixes (Valetva resolving to a provisional `gj?:` id) only showed up because the shipped
 * catalogue's spellings differ from the app's stored ones, and a fixture would have hidden it.
 */
class CascadeMatchTest {

    private data class Geo(val code: String, val en: String, val gu: String)

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "tools/identity/vectors.json").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("repo root not found")
    }

    private fun assetFile(name: String) =
        File(repoRoot(), "android/app/src/main/assets/cascade/$name")

    private fun talukas(districtFile: String): List<Geo> {
        val root = JSONObject(assetFile(districtFile).readText())
        val arr = root.getJSONArray("talukas")
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Geo(o.getString("code"), o.optString("en"), o.optString("gu"))
        }
    }

    private fun villages(districtFile: String, talukaCode: String): List<Geo> {
        val root = JSONObject(assetFile(districtFile).readText())
        val arr = root.getJSONArray("talukas")
        for (i in 0 until arr.length()) {
            val t = arr.getJSONObject(i)
            if (t.getString("code") != talukaCode) continue
            val vs = t.optJSONArray("villages") ?: return emptyList()
            return (0 until vs.length()).map {
                val o = vs.getJSONObject(it)
                Geo(o.getString("code"), o.optString("en"), o.optString("gu"))
            }
        }
        return emptyList()
    }

    private fun names(g: Geo) = listOf(g.en, g.gu)

    /**
     * The regression. Every level of this place failed an exact compare:
     *   taluka  en "Nadiad Gramya" vs catalogue "Nadiad"          (qualifier only in the gu name)
     *   taluka  gu "નડિયાદ ગ્રામ્ય" vs catalogue "નડીઆદ ગ્રામ્ય"      (િ/ી and યા/આ)
     *   village gu "વળેટવા"       vs catalogue "વલેટવા"           (ળ/લ)
     */
    @Test
    fun valetva_resolvesFromTheRealCatalogue() {
        val t = CascadeMatch.pick("Nadiad Gramya", talukas("16.json"), ::names)
        assertEquals("Nadiad Gramya must resolve to the RURAL taluka", "08", t?.code)

        val v = CascadeMatch.pick("Valetva", villages("16.json", "08"), ::names)
        assertEquals("097", v?.code)

        // …and via the Gujarati spellings the app actually stores.
        val tGu = CascadeMatch.pick("નડિયાદ ગ્રામ્ય", talukas("16.json"), ::names)
        assertEquals("08", tGu?.code)
        val vGu = CascadeMatch.pick("વળેટવા", villages("16.json", "08"), ::names)
        assertEquals("ળ vs લ must not defeat the match", "097", vGu?.code)
    }

    /**
     * The qualifier must never be allowed to conflate rural with city — that would file a
     * village's records under a different real place.
     */
    @Test
    fun ruralAndCityStayDistinct() {
        val rural = CascadeMatch.pick("નડિયાદ ગ્રામ્ય", talukas("16.json"), ::names)
        val city = CascadeMatch.pick("નડિયાદ શહેર", talukas("16.json"), ::names)
        assertEquals("08", rural?.code)
        assertEquals("13", city?.code)
    }

    /** The other two live villages must keep resolving — no regression from the looser matching. */
    @Test
    fun bharodaAndSundalpuraStillResolve() {
        val t = CascadeMatch.pick("Umreth", talukas("anand_15.json"), ::names)
        requireNotNull(t) { "Umreth must resolve" }
        assertEquals("ભરોડા", "ભરોડા", "ભરોડા") // readability anchor
        val bharoda = CascadeMatch.pick("Bharoda", villages("anand_15.json", t.code), ::names)
        val sundalpura = CascadeMatch.pick("Sundalpura", villages("anand_15.json", t.code), ::names)
        requireNotNull(bharoda) { "Bharoda must resolve" }
        requireNotNull(sundalpura) { "Sundalpura must resolve" }
        assert(bharoda.code != sundalpura.code)
    }

    /** Still refuses to guess: an unknown name resolves to nothing, never to "closest". */
    @Test
    fun neverGuesses() {
        assertNull(CascadeMatch.pick("Nowhere-at-all", talukas("16.json"), ::names))
        assertNull(CascadeMatch.pick("", talukas("16.json"), ::names))
        assertNull(CascadeMatch.pick("Nadiad", emptyList<Geo>(), ::names))
    }

    @Test
    fun normalizationFoldsTheGovernmentDataQuirks() {
        assertEquals(CascadeMatch.nn("વળેટવા"), CascadeMatch.nn("વલેટવા"))   // ળ / લ
        assertEquals(CascadeMatch.nn("ANAND"), CascadeMatch.nn("anand"))
        assertEquals(CascadeMatch.nn("Nadiad-Gramya"), CascadeMatch.nn("nadiad gramya"))
        assertEquals("845", CascadeMatch.nn("૮૪૫"))
    }
}
