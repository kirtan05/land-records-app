# Maps, Plan 2 of 2: the Android Maps feature

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Maps destination to the Land Records app: browse every eJamin map sheet by district → taluka → village, and for indexed Kheda/Anand villages open an interactive vector cadastre map with survey-number search, shared-edge adjoining detection and colour-marking.

**Architecture:** `ui/maps/geometry/` is pure Kotlin (no Compose, no Android, no Room) and is covered by plain JVM tests. `data/maps/` reads a bundled catalogue asset, fetches + checksum-verifies per-village indexes into `Documents/LandRecords/maps/`, and persists marks as a JSON manifest under `filesDir` — the same convention `CasesStore`/`VfScansStore` already use. `ui/maps/` renders on a Compose `Canvas`. **No Room entities and no database migration are added.**

**Tech Stack:** Kotlin 2.3.21, Compose + Material 3, Navigation Compose, `android.graphics.pdf.PdfRenderer` (already used elsewhere in the app), `org.json`, JUnit 4 for new JVM tests. No new runtime dependencies.

## Global Constraints

- Spec: `docs/specs/2026-08-10-maps-village-cadastre-design.md`. Design source of truth: `design_handoff_land_records_ui/README.md` (read it before Task 6).
- Build stack is pinned — **do not bump anything**: Gradle 9.5 · AGP 9.1.1 · Kotlin 2.3.21 · Room 2.8.4 · compileSdk 37 · minSdk 26. `material-icons-extended` stays at 1.7.8.
- Direction "Cadastre" at **comfy** density. Ochre accent `#B4531B` (dark `#E58A55`), **no elevation** — 1dp borders only. Use the existing `Land.colors.*`, `LandType.*`, `LandShape.*` tokens; never hardcode a hex.
- Every survey is drawn as a **parcel tile**: 1dp border, radius 12dp, 1dp dashed inset at 5dp (`Modifier.dashedInset`).
- Survey numbers, counts and all-caps labels use `LandType.surveyTile` / `metaMono` / `label` (IBM Plex Mono); headings and body use `LandType.screenTitle` / `body` / `meta` (Space Grotesk).
- App chrome respects the language setting via `Lr(R.string.x_gu, R.string.x_en)`. **Land data is never translated** — Gujarati numerals stay, with a Latin helper line.
- **Never invent land data.** Unknown metadata renders `—`. A survey number absent from the index is reported as absent, never guessed.
- Both themes are first-class. All motion collapses under reduced-motion.
- Build: `cd android && ./gradlew :app:assembleDebug`. Unit tests: `cd android && ./gradlew :app:testDebugUnitTest`.
- Every new string needs **both** `_gu` and `_en` entries in `res/values/strings.xml`.

## Prerequisite

Plan 1 (`docs/plans/2026-08-10-maps-1-pipeline.md`) must be complete, with `tools/ejamin/out/catalog.json`, `out/indexes/village-*.index.json` and `out/indexes/manifest.json` produced and the hand-verification in Plan 1 Task 10 Step 6 passed.

---

## File structure

| File | Responsibility |
|---|---|
| `app/src/test/java/.../SurveyNoTest.kt` etc. | New JVM test source set (does not exist yet) |
| `ui/maps/geometry/SurveyNo.kt` | Parse/normalise/compare survey numbers across scripts |
| `ui/maps/geometry/MapIndex.kt` | Index data classes + JSON parsing |
| `ui/maps/geometry/MapGeometry.kt` | bbox, point-in-polygon hit test, neighbour lookup, page→lat/long |
| `data/maps/MapCatalog.kt` | Read + query the bundled catalogue asset |
| `data/maps/MapIndexStore.kt` | Fetch, verify, cache indexes and sheet PDFs |
| `data/maps/MapMarksStore.kt` | Per-village marks manifest (`VfScansStore` analogue) |
| `ui/maps/MapsBrowseScreen.kt` + `MapsViewModel.kt` | District → taluka → village browse |
| `ui/maps/SheetScreen.kt` | Full-sheet PDF view / Drive fallback |
| `ui/maps/VillageMapScreen.kt` + `VillageMapViewModel.kt` | The interactive map |
| `ui/maps/ParcelCanvas.kt` | Pan/zoom canvas, parcel drawing, tap dispatch |
| `assets/maps/catalog.json` | Bundled catalogue from Plan 1 |

---

### Task 1: Create the test source set

The module has **no `src/test/` at all**. Everything downstream depends on this existing.

**Files:**
- Modify: `android/app/build.gradle.kts`
- Create: `android/app/src/test/java/com/landrecords/app/ui/maps/geometry/SurveyNoTest.kt`
- Create: `android/app/src/main/java/com/landrecords/app/ui/maps/geometry/SurveyNo.kt`

**Interfaces:**
- Produces: `object SurveyNo { fun normalise(raw: String): String; fun matches(a: String, b: String): Boolean; fun display(raw: String): String }`

- [ ] **Step 1: Add the test dependency**

In `android/app/build.gradle.kts`, inside `dependencies { }`, after the `debugImplementation` line:

```kotlin
    testImplementation("junit:junit:4.13.2")
```

- [ ] **Step 2: Write the failing test**

Create `android/app/src/test/java/com/landrecords/app/ui/maps/geometry/SurveyNoTest.kt`:

```kotlin
package com.landrecords.app.ui.maps.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SurveyNoTest {

    @Test fun `Gujarati numerals normalise to Latin`() {
        assertEquals("221", SurveyNo.normalise("૨૨૧"))
        assertEquals("74/P", SurveyNo.normalise("૭૪/પ"))
    }

    @Test fun `whitespace and case are irrelevant`() {
        assertEquals("74/P", SurveyNo.normalise(" 74 / p "))
    }

    @Test fun `matching works across scripts`() {
        assertTrue(SurveyNo.matches("૨૨૧", "221"))
        assertTrue(SurveyNo.matches("74/p", "74/P"))
        assertFalse(SurveyNo.matches("221", "221/P"))
    }

    @Test fun `display preserves what the user typed`() {
        assertEquals("૨૨૧", SurveyNo.display("૨૨૧"))
        assertEquals("221/P", SurveyNo.display(" 221/p "))
    }
}
```

- [ ] **Step 3: Run it and verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests '*SurveyNoTest*'`
Expected: FAIL — `Unresolved reference: SurveyNo`.

- [ ] **Step 4: Implement `SurveyNo.kt`**

Create `android/app/src/main/java/com/landrecords/app/ui/maps/geometry/SurveyNo.kt`:

```kotlin
package com.landrecords.app.ui.maps.geometry

/**
 * Survey-number identity for map lookups. The index stores Latin-normalised numbers (the pipeline's
 * `labels.mjs` guarantees that), but dad types in either script — so matching normalises both sides
 * while [display] keeps whatever he actually entered. Land data is never rewritten for him; the
 * Latin form exists only as a key.
 */
object SurveyNo {

    private const val GU_DIGITS = "૦૧૨૩૪૫૬૭૮૯"

    /** Latin-digit, upper-case, whitespace-free key. Gujarati 'પ' is the part marker → 'P'. */
    fun normalise(raw: String): String = buildString {
        for (ch in raw) {
            val i = GU_DIGITS.indexOf(ch)
            when {
                i >= 0 -> append(i)
                ch == 'પ' -> append('P')
                ch.isWhitespace() -> Unit
                else -> append(ch.uppercaseChar())
            }
        }
    }

    fun matches(a: String, b: String): Boolean = normalise(a) == normalise(b)

    /** What to show back: the user's own text, only trimmed and case-tidied. */
    fun display(raw: String): String = raw.trim().let { s ->
        if (s.any { it in GU_DIGITS }) s else s.uppercase().replace(" ", "")
    }
}
```

- [ ] **Step 5: Run the tests**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests '*SurveyNoTest*'`
Expected: PASS, 4 tests.

- [ ] **Step 6: Commit**

```bash
git add android/app/build.gradle.kts android/app/src/test android/app/src/main/java/com/landrecords/app/ui/maps
git commit -m "feat(maps): add JVM test source set and survey-number identity"
```

---

### Task 2: Index model and parsing

**Files:**
- Create: `android/app/src/main/java/com/landrecords/app/ui/maps/geometry/MapIndex.kt`
- Create: `android/app/src/test/java/com/landrecords/app/ui/maps/geometry/MapIndexTest.kt`
- Create: `android/app/src/test/resources/village-fixture.index.json`

**Interfaces:**
- Consumes: `SurveyNo` (Task 1).
- Produces:
  ```kotlin
  data class Parcel(val id: Int, val surveyNo: String?, val poly: List<FloatArray>, val adj: List<Int>)
  data class MapFeature(val kind: String, val label: String, val x: Float, val y: Float)
  data class MapIndex(
      val villageId: Long, val villageName: String,
      val districtName: String, val talukaName: String,
      val pageWidth: Float, val pageHeight: Float,
      val geo: FloatArray?,          // 6-element affine, or null when unregistered
      val parcels: List<Parcel>, val features: List<MapFeature>,
      val quality: String,           // "GOOD" | "LINK_ONLY"
  ) {
      fun findBySurveyNo(query: String): Parcel?
  }
  object MapIndexParser { fun parse(json: String): MapIndex }
  ```

- [ ] **Step 1: Create the fixture**

Copy a small real index from Plan 1 (pick the one with the fewest parcels so the fixture stays readable):

```bash
mkdir -p android/app/src/test/resources
node -e "const fs=require('fs');const m=require('./tools/ejamin/out/indexes/manifest.json');
const s=m.villages.filter(v=>v.quality==='GOOD').sort((a,b)=>a.parcels-b.parcels)[0];
fs.copyFileSync('tools/ejamin/out/indexes/'+s.file,'android/app/src/test/resources/village-fixture.index.json');
console.log(s.villageName, s.parcels, 'parcels');"
```

Note the printed parcel count — the test below asserts against it.

- [ ] **Step 2: Write the failing test**

Create `android/app/src/test/java/com/landrecords/app/ui/maps/geometry/MapIndexTest.kt`. Replace `EXPECTED_PARCELS` and `KNOWN_SURVEY` with values read from the fixture you just copied.

```kotlin
package com.landrecords.app.ui.maps.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapIndexTest {

    private val json =
        checkNotNull(javaClass.classLoader!!.getResourceAsStream("village-fixture.index.json"))
            .bufferedReader().readText()

    private val index = MapIndexParser.parse(json)

    @Test fun `parses the whole index`() {
        assertEquals(EXPECTED_PARCELS, index.parcels.size)
        assertTrue(index.pageWidth > 1000f)
        assertEquals("GOOD", index.quality)
        assertNotNull(index.geo)
        assertEquals(6, index.geo!!.size)
    }

    @Test fun `polygons survive parsing as coordinate pairs`() {
        val p = index.parcels.first { it.poly.isNotEmpty() }
        assertTrue(p.poly.all { it.size == 2 })
        assertTrue(p.poly.size >= 3)
    }

    @Test fun `findBySurveyNo matches across scripts and reports genuine absences`() {
        assertNotNull(index.findBySurveyNo(KNOWN_SURVEY))
        assertNotNull(index.findBySurveyNo(" ${KNOWN_SURVEY.lowercase()} "))
        assertNull(index.findBySurveyNo("999999"))
    }

    private companion object {
        const val EXPECTED_PARCELS = 0   // <-- set from the fixture
        const val KNOWN_SURVEY = ""      // <-- set from the fixture
    }
}
```

- [ ] **Step 3: Run it and verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests '*MapIndexTest*'`
Expected: FAIL — `Unresolved reference: MapIndexParser`.

- [ ] **Step 4: Implement `MapIndex.kt`**

```kotlin
package com.landrecords.app.ui.maps.geometry

import org.json.JSONObject

/** One cadastral plot: its outline in page space, its survey number, its shared-edge neighbours. */
data class Parcel(
    val id: Int,
    /** Latin-normalised, or null when no label fell inside this outline — never guessed. */
    val surveyNo: String?,
    val poly: List<FloatArray>,
    val adj: List<Int>,
)

/** A road, canal, neighbouring village name or sheet note, at its printed position. */
data class MapFeature(val kind: String, val label: String, val x: Float, val y: Float)

/**
 * A whole village sheet, as produced by `tools/ejamin/build-index.mjs`. Page space is PDF points
 * with the origin bottom-left, exactly as the sheet was drawn; [geo] converts that to lat/long.
 */
data class MapIndex(
    val villageId: Long,
    val villageName: String,
    val districtName: String,
    val talukaName: String,
    val pageWidth: Float,
    val pageHeight: Float,
    val geo: FloatArray?,
    val parcels: List<Parcel>,
    val features: List<MapFeature>,
    val quality: String,
) {
    private val bySurvey: Map<String, Parcel> =
        parcels.mapNotNull { p -> p.surveyNo?.let { SurveyNo.normalise(it) to p } }.toMap()

    /** The plot for a typed survey number, or null when this sheet genuinely does not carry it. */
    fun findBySurveyNo(query: String): Parcel? = bySurvey[SurveyNo.normalise(query)]

    fun neighbours(parcel: Parcel): List<Parcel> = parcel.adj.mapNotNull { parcels.getOrNull(it) }
}

object MapIndexParser {

    fun parse(json: String): MapIndex {
        val o = JSONObject(json)
        val size = o.getJSONArray("pageSize")

        val parcelsArr = o.getJSONArray("parcels")
        val parcels = ArrayList<Parcel>(parcelsArr.length())
        for (i in 0 until parcelsArr.length()) {
            val p = parcelsArr.getJSONObject(i)
            val polyArr = p.getJSONArray("poly")
            val poly = ArrayList<FloatArray>(polyArr.length())
            for (j in 0 until polyArr.length()) {
                val pt = polyArr.getJSONArray(j)
                poly.add(floatArrayOf(pt.getDouble(0).toFloat(), pt.getDouble(1).toFloat()))
            }
            val adjArr = p.getJSONArray("adj")
            parcels.add(
                Parcel(
                    id = p.getInt("id"),
                    surveyNo = if (p.isNull("surveyNo")) null else p.getString("surveyNo"),
                    poly = poly,
                    adj = List(adjArr.length()) { adjArr.getInt(it) },
                ),
            )
        }

        val featArr = o.optJSONArray("features")
        val features = ArrayList<MapFeature>(featArr?.length() ?: 0)
        for (i in 0 until (featArr?.length() ?: 0)) {
            val f = featArr!!.getJSONObject(i)
            features.add(
                MapFeature(
                    kind = f.getString("kind"),
                    label = f.getString("label"),
                    x = f.getDouble("x").toFloat(),
                    y = f.getDouble("y").toFloat(),
                ),
            )
        }

        val geoObj = o.optJSONObject("geo")
        val geo = geoObj?.getJSONArray("matrix")?.let { m ->
            FloatArray(6) { m.getDouble(it).toFloat() }
        }

        return MapIndex(
            villageId = o.getLong("villageId"),
            villageName = o.getString("villageName"),
            districtName = o.getString("districtName"),
            talukaName = o.getString("talukaName"),
            pageWidth = size.getDouble(0).toFloat(),
            pageHeight = size.getDouble(1).toFloat(),
            geo = geo,
            parcels = parcels,
            features = features,
            quality = o.optString("quality", "GOOD"),
        )
    }
}
```

- [ ] **Step 5: Run the tests**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests '*MapIndexTest*'`
Expected: PASS, 3 tests.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/com/landrecords/app/ui/maps/geometry/MapIndex.kt android/app/src/test
git commit -m "feat(maps): parse the village parcel index"
```

---

### Task 3: Map geometry — hit testing and camera fitting

**Files:**
- Create: `android/app/src/main/java/com/landrecords/app/ui/maps/geometry/MapGeometry.kt`
- Create: `android/app/src/test/java/com/landrecords/app/ui/maps/geometry/MapGeometryTest.kt`

**Interfaces:**
- Consumes: `Parcel`, `MapIndex` (Task 2).
- Produces:
  ```kotlin
  object MapGeometry {
      fun bounds(poly: List<FloatArray>): FloatArray            // [minX, minY, maxX, maxY]
      fun contains(poly: List<FloatArray>, x: Float, y: Float): Boolean
      fun parcelAt(index: MapIndex, x: Float, y: Float): Parcel?
      fun toLatLng(geo: FloatArray, x: Float, y: Float): DoubleArray  // [lat, lng]
  }
  ```

- [ ] **Step 1: Write the failing test**

```kotlin
package com.landrecords.app.ui.maps.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapGeometryTest {

    private fun square(x: Float, y: Float, s: Float = 10f) = listOf(
        floatArrayOf(x, y), floatArrayOf(x + s, y), floatArrayOf(x + s, y + s), floatArrayOf(x, y + s),
    )

    @Test fun `bounds of a square`() {
        val b = MapGeometry.bounds(square(0f, 0f))
        assertEquals(0f, b[0], 0.001f); assertEquals(0f, b[1], 0.001f)
        assertEquals(10f, b[2], 0.001f); assertEquals(10f, b[3], 0.001f)
    }

    @Test fun `contains handles inside outside and a concave notch`() {
        assertTrue(MapGeometry.contains(square(0f, 0f), 5f, 5f))
        assertFalse(MapGeometry.contains(square(0f, 0f), 15f, 5f))
        val l = listOf(
            floatArrayOf(0f, 0f), floatArrayOf(10f, 0f), floatArrayOf(10f, 4f),
            floatArrayOf(4f, 4f), floatArrayOf(4f, 10f), floatArrayOf(0f, 10f),
        )
        assertTrue(MapGeometry.contains(l, 2f, 8f))
        assertFalse(MapGeometry.contains(l, 8f, 8f))
    }

    @Test fun `parcelAt picks the smallest containing parcel and nothing in empty space`() {
        val index = MapIndex(
            villageId = 1, villageName = "T", districtName = "D", talukaName = "T",
            pageWidth = 100f, pageHeight = 100f, geo = null,
            parcels = listOf(
                Parcel(0, "1", square(0f, 0f, 50f), emptyList()),   // big enclosing block
                Parcel(1, "2", square(5f, 5f, 10f), emptyList()),   // the real plot inside it
            ),
            features = emptyList(), quality = "GOOD",
        )
        assertEquals("2", MapGeometry.parcelAt(index, 8f, 8f)?.surveyNo)
        assertNull(MapGeometry.parcelAt(index, 90f, 90f))
    }

    @Test fun `toLatLng applies the affine`() {
        val out = MapGeometry.toLatLng(floatArrayOf(0f, 0f, 22.5f, 0f, 0f, 72.5f), 100f, 100f)
        assertEquals(22.5, out[0], 0.0001)
        assertEquals(72.5, out[1], 0.0001)
    }
}
```

- [ ] **Step 2: Run it and verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests '*MapGeometryTest*'`
Expected: FAIL — `Unresolved reference: MapGeometry`.

- [ ] **Step 3: Implement `MapGeometry.kt`**

```kotlin
package com.landrecords.app.ui.maps.geometry

/**
 * Plane geometry on the sheet's page space. Mirrors `tools/ejamin/lib/geom.mjs` deliberately: the
 * pipeline decides adjacency, the app must agree about what "inside a parcel" means or a tap would
 * select a different plot than the one the index labelled.
 */
object MapGeometry {

    fun bounds(poly: List<FloatArray>): FloatArray {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (p in poly) {
            if (p[0] < minX) minX = p[0]; if (p[1] < minY) minY = p[1]
            if (p[0] > maxX) maxX = p[0]; if (p[1] > maxY) maxY = p[1]
        }
        return floatArrayOf(minX, minY, maxX, maxY)
    }

    private fun area(poly: List<FloatArray>): Float {
        var s = 0f
        var j = poly.size - 1
        for (i in poly.indices) {
            s += poly[j][0] * poly[i][1] - poly[i][0] * poly[j][1]
            j = i
        }
        return kotlin.math.abs(s) / 2f
    }

    /** Ray casting, identical in semantics to the pipeline's `contains`. */
    fun contains(poly: List<FloatArray>, x: Float, y: Float): Boolean {
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val (xi, yi) = poly[i][0] to poly[i][1]
            val (xj, yj) = poly[j][0] to poly[j][1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    /**
     * The plot under a tap. Smallest containing parcel wins, so tapping inside a block that is
     * itself drawn inside a larger enclosing outline selects the actual plot — the same rule the
     * pipeline uses when binding a survey label to a polygon.
     */
    fun parcelAt(index: MapIndex, x: Float, y: Float): Parcel? =
        index.parcels
            .filter { contains(it.poly, x, y) }
            .minByOrNull { area(it.poly) }

    /** Page point → [lat, lng] using the sheet's GeoPDF registration. */
    fun toLatLng(geo: FloatArray, x: Float, y: Float): DoubleArray = doubleArrayOf(
        (geo[0] * x + geo[1] * y + geo[2]).toDouble(),
        (geo[3] * x + geo[4] * y + geo[5]).toDouble(),
    )
}
```

- [ ] **Step 4: Run the tests**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests '*MapGeometryTest*'`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/landrecords/app/ui/maps/geometry/MapGeometry.kt android/app/src/test
git commit -m "feat(maps): parcel hit testing and lat/long projection"
```

---

### Task 4: The bundled catalogue

**Files:**
- Create: `android/app/src/main/assets/maps/catalog.json` (copied from Plan 1)
- Create: `android/app/src/main/java/com/landrecords/app/data/maps/MapCatalog.kt`
- Create: `android/app/src/test/java/com/landrecords/app/data/maps/MapCatalogTest.kt`
- Create: `android/app/src/test/resources/catalog-fixture.json`

**Interfaces:**
- Produces:
  ```kotlin
  enum class SheetType { VILLAGE_MAP, TP_MAP, GDSR, F_FORM, GDCR, DP }
  data class MapSheet(
      val type: SheetType, val districtId: Int, val districtName: String,
      val talukaId: Int?, val talukaName: String?,
      val villageId: Long?, val villageName: String?,
      val driveFileId: String, val viewUrl: String, val downloadUrl: String,
  )
  class MapCatalog(val sheets: List<MapSheet>) {
      fun districts(type: SheetType): List<String>
      fun talukas(type: SheetType, district: String): List<String>
      fun villages(type: SheetType, district: String, taluka: String): List<MapSheet>
      fun sheetsFor(villageName: String, district: String): List<MapSheet>
      companion object { fun parse(json: String): MapCatalog; suspend fun load(context: Context): MapCatalog }
  }
  ```

- [ ] **Step 1: Copy the catalogue and make the fixture**

```bash
mkdir -p android/app/src/main/assets/maps android/app/src/test/resources
cp tools/ejamin/out/catalog.json android/app/src/main/assets/maps/catalog.json
node -e "const c=require('./tools/ejamin/out/catalog.json');const fs=require('fs');
const keep=c.sheets.filter(s=>['Kheda','Anand'].includes(s.districtName)).slice(0,200);
fs.writeFileSync('android/app/src/test/resources/catalog-fixture.json',JSON.stringify({generatedAt:c.generatedAt,sheets:keep}));
console.log('fixture sheets', keep.length);"
ls -la android/app/src/main/assets/maps/catalog.json
```

Note the asset's size. If it exceeds ~2 MB, drop `viewUrl`/`downloadUrl` from the asset and derive them in `MapCatalog.parse` from `driveFileId` (the same two templates `drive.mjs` uses).

- [ ] **Step 2: Write the failing test**

```kotlin
package com.landrecords.app.data.maps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapCatalogTest {

    private val catalog = MapCatalog.parse(
        checkNotNull(javaClass.classLoader!!.getResourceAsStream("catalog-fixture.json"))
            .bufferedReader().readText(),
    )

    @Test fun `districts are unique and sorted`() {
        val d = catalog.districts(SheetType.VILLAGE_MAP)
        assertEquals(d.distinct(), d)
        assertEquals(d.sorted(), d)
    }

    @Test fun `the cascade narrows`() {
        val district = catalog.districts(SheetType.VILLAGE_MAP).first()
        val taluka = catalog.talukas(SheetType.VILLAGE_MAP, district).first()
        val villages = catalog.villages(SheetType.VILLAGE_MAP, district, taluka)
        assertTrue(villages.isNotEmpty())
        assertTrue(villages.all { it.districtName == district && it.talukaName == taluka })
    }

    @Test fun `every sheet carries a usable download URL`() {
        assertTrue(catalog.sheets.all { it.downloadUrl.startsWith("https://drive.google.com/uc?export=download&id=") })
    }
}
```

- [ ] **Step 3: Run it and verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests '*MapCatalogTest*'`
Expected: FAIL — `Unresolved reference: MapCatalog`.

- [ ] **Step 4: Implement `MapCatalog.kt`**

```kotlin
package com.landrecords.app.data.maps

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** The seven sheet kinds eJamin publishes. Unknown future kinds are skipped, never guessed. */
enum class SheetType { VILLAGE_MAP, TP_MAP, GDSR, F_FORM, GDCR, DP }

/**
 * One published sheet. [villageId] is the eJamin id and doubles as the deep-index key.
 * Ids are only ever meaningful WITHIN a [type] — eJamin numbers each tab's districts separately
 * (Kheda is 14 in one tab and 18 in another), so nothing here may be compared across types.
 */
data class MapSheet(
    val type: SheetType,
    val districtId: Int,
    val districtName: String,
    val talukaId: Int?,
    val talukaName: String?,
    val villageId: Long?,
    val villageName: String?,
    val driveFileId: String,
    val viewUrl: String,
    val downloadUrl: String,
)

/** The bundled catalogue, held in memory — it is small and every screen queries it. */
class MapCatalog(val sheets: List<MapSheet>) {

    fun districts(type: SheetType): List<String> =
        sheets.filter { it.type == type }.map { it.districtName }.distinct().sorted()

    fun talukas(type: SheetType, district: String): List<String> =
        sheets.filter { it.type == type && it.districtName == district }
            .mapNotNull { it.talukaName }.distinct().sorted()

    fun villages(type: SheetType, district: String, taluka: String): List<MapSheet> =
        sheets.filter { it.type == type && it.districtName == district && it.talukaName == taluka }
            .sortedBy { it.villageName ?: "" }

    /** Every sheet of any type published for one village — the "other sheets" list. */
    fun sheetsFor(villageName: String, district: String): List<MapSheet> =
        sheets.filter { it.districtName == district && it.villageName == villageName }

    companion object {
        fun parse(json: String): MapCatalog {
            val arr = JSONObject(json).getJSONArray("sheets")
            val out = ArrayList<MapSheet>(arr.length())
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                val type = runCatching { SheetType.valueOf(s.getString("type")) }.getOrNull() ?: continue
                out.add(
                    MapSheet(
                        type = type,
                        districtId = s.getInt("districtId"),
                        districtName = s.getString("districtName"),
                        talukaId = if (s.isNull("talukaId")) null else s.getInt("talukaId"),
                        talukaName = if (s.isNull("talukaName")) null else s.getString("talukaName"),
                        villageId = if (s.isNull("villageId")) null else s.getLong("villageId"),
                        villageName = if (s.isNull("villageName")) null else s.getString("villageName"),
                        driveFileId = s.getString("driveFileId"),
                        viewUrl = s.getString("viewUrl"),
                        downloadUrl = s.getString("downloadUrl"),
                    ),
                )
            }
            return MapCatalog(out)
        }

        @Volatile private var cached: MapCatalog? = null

        suspend fun load(context: Context): MapCatalog = cached ?: withContext(Dispatchers.IO) {
            val json = context.assets.open("maps/catalog.json").bufferedReader().use { it.readText() }
            parse(json).also { cached = it }
        }
    }
}
```

- [ ] **Step 5: Run the tests**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests '*MapCatalogTest*'`
Expected: PASS, 3 tests.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/assets/maps/catalog.json android/app/src/main/java/com/landrecords/app/data/maps android/app/src/test
git commit -m "feat(maps): bundle and query the eJamin sheet catalogue"
```

---

### Task 5: Index and sheet caching

**Files:**
- Create: `android/app/src/main/java/com/landrecords/app/data/maps/MapIndexStore.kt`

**Interfaces:**
- Consumes: `MapIndexParser` (Task 2), `MapSheet` (Task 4).
- Produces:
  ```kotlin
  object MapIndexStore {
      const val BASE_URL = "https://raw.githubusercontent.com/kirtan05/land-records-releases/main/maps/"
      fun dir(context: Context): File
      suspend fun cachedIndex(context: Context, villageId: Long): MapIndex?
      suspend fun fetchIndex(context: Context, villageId: Long, sha256: String?): MapIndex?
      suspend fun cachedPdf(context: Context, sheet: MapSheet): File?
      suspend fun downloadPdf(context: Context, sheet: MapSheet): File?
  }
  ```

- [ ] **Step 1: Implement `MapIndexStore.kt`**

There is no unit test here — it is all I/O against the network and the user-visible storage tree, exactly like `Updater.kt` and `OrderDownloader.kt`, which are likewise verified on device. Its correctness rules (verify then swap, delete on mismatch) are asserted in Task 6's on-device check.

```kotlin
package com.landrecords.app.data.maps

import android.content.Context
import com.landrecords.app.data.storage.LandRecordsStorage
import com.landrecords.app.ui.maps.geometry.MapIndex
import com.landrecords.app.ui.maps.geometry.MapIndexParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Fetches and caches the per-village parcel indexes and the original sheet PDFs.
 *
 * Both live under the visible Documents/LandRecords tree (maps/ and maps/sheets/) so dad can see
 * and back them up like everything else the app keeps. A verified index is permanent: once cached,
 * the village works with no network for good.
 */
object MapIndexStore {

    const val BASE_URL = "https://raw.githubusercontent.com/kirtan05/land-records-releases/main/maps/"

    fun dir(context: Context): File =
        File(LandRecordsStorage.root(context), "maps").apply { mkdirs() }

    private fun sheetsDir(context: Context): File = File(dir(context), "sheets").apply { mkdirs() }

    private fun indexFile(context: Context, villageId: Long) =
        File(dir(context), "village-$villageId.index.json")

    /** The cached index, or null. A cache that fails to parse is deleted rather than half-used. */
    suspend fun cachedIndex(context: Context, villageId: Long): MapIndex? = withContext(Dispatchers.IO) {
        val f = indexFile(context, villageId)
        if (!f.exists()) return@withContext null
        runCatching { MapIndexParser.parse(f.readText()) }.getOrElse {
            f.delete()
            null
        }
    }

    /**
     * Downloads and verifies one village index. [sha256] comes from the shipped manifest; a
     * mismatch throws the bytes away rather than caching a corrupt map — the caller then falls
     * back to the full sheet.
     */
    suspend fun fetchIndex(context: Context, villageId: Long, sha256: String?): MapIndex? =
        withContext(Dispatchers.IO) {
            val body = runCatching {
                (URL("$BASE_URL" + "village-$villageId.index.json").openConnection() as HttpURLConnection).run {
                    connectTimeout = 20_000
                    readTimeout = 30_000
                    if (responseCode != 200) return@run null
                    inputStream.bufferedReader().use { it.readText() }
                }
            }.getOrNull() ?: return@withContext null

            if (sha256 != null && sha256 != sha256Of(body)) {
                android.util.Log.w("LR", "MapIndexStore: checksum mismatch for village $villageId")
                return@withContext null
            }
            val index = runCatching { MapIndexParser.parse(body) }.getOrNull() ?: return@withContext null
            // Write only after the bytes are proven, so a cached file is always trustworthy.
            indexFile(context, villageId).writeText(body)
            index
        }

    private fun sha256Of(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    fun cachedPdfFile(context: Context, sheet: MapSheet) =
        File(sheetsDir(context), "${sheet.driveFileId}.pdf")

    suspend fun cachedPdf(context: Context, sheet: MapSheet): File? = withContext(Dispatchers.IO) {
        cachedPdfFile(context, sheet).takeIf { it.exists() && it.length() > 0 }
    }

    /** Downloads the original sheet. Returns null (never a partial file) when it did not arrive. */
    suspend fun downloadPdf(context: Context, sheet: MapSheet): File? = withContext(Dispatchers.IO) {
        val target = cachedPdfFile(context, sheet)
        if (target.exists() && target.length() > 0) return@withContext target
        val tmp = File(target.parentFile, "${target.name}.part")
        val ok = runCatching {
            (URL(sheet.downloadUrl).openConnection() as HttpURLConnection).run {
                instanceFollowRedirects = true
                connectTimeout = 20_000
                readTimeout = 120_000
                if (responseCode != 200) return@run false
                tmp.outputStream().use { out -> inputStream.use { it.copyTo(out) } }
                // Drive serves an HTML quota page with HTTP 200 — check the magic, not the status.
                tmp.inputStream().use { it.readNBytes(5) }.decodeToString().startsWith("%PDF")
            }
        }.getOrDefault(false)
        if (!ok) { tmp.delete(); return@withContext null }
        tmp.renameTo(target)
        target
    }
}
```

If `LandRecordsStorage.root(context)` is not the exact accessor name, use whatever that object already exposes for the `Documents/LandRecords` root — read `data/storage/LandRecordsStorage.kt` first and match it. Do not add a second storage root.

- [ ] **Step 2: Build**

Run: `cd android && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/java/com/landrecords/app/data/maps/MapIndexStore.kt
git commit -m "feat(maps): fetch, verify and cache indexes and sheet PDFs"
```

---

### Task 6: Marks manifest

**Files:**
- Create: `android/app/src/main/java/com/landrecords/app/data/maps/MapMarksStore.kt`
- Create: `android/app/src/test/java/com/landrecords/app/data/maps/MapMarksStoreTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  object MapMarksStore {
      enum class Source { AUTO, CONFIRMED, MANUAL }
      data class Mark(val surveyNo: String, val mark: String?, val note: String, val source: Source, val updatedAt: Long)
      fun toJson(marks: List<Mark>): String
      fun fromJson(json: String): List<Mark>
      suspend fun read(context: Context, villageId: Long): List<Mark>
      suspend fun set(context: Context, villageId: Long, mark: Mark)
      suspend fun clear(context: Context, villageId: Long, surveyNo: String)
  }
  ```

- [ ] **Step 1: Write the failing test**

The pure JSON codec is what carries the risk, so that is what the JVM test covers.

```kotlin
package com.landrecords.app.data.maps

import com.landrecords.app.data.maps.MapMarksStore.Mark
import com.landrecords.app.data.maps.MapMarksStore.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapMarksStoreTest {

    @Test fun `round trips every field`() {
        val marks = listOf(
            Mark("221/P", "red", "east boundary disputed", Source.MANUAL, 1_700_000_000_000L),
            Mark("222", null, "", Source.AUTO, 1_700_000_000_001L),
        )
        val back = MapMarksStore.fromJson(MapMarksStore.toJson(marks))
        assertEquals(marks, back)
    }

    @Test fun `a corrupt manifest yields no marks instead of throwing`() {
        assertTrue(MapMarksStore.fromJson("{ this is not json").isEmpty())
    }

    @Test fun `an unknown source falls back to MANUAL rather than dropping the mark`() {
        val json = """{"marks":[{"surveyNo":"5","mark":"blue","note":"","source":"WHAT","updatedAt":1}]}"""
        val out = MapMarksStore.fromJson(json)
        assertEquals(1, out.size)
        assertEquals(Source.MANUAL, out[0].source)
    }
}
```

- [ ] **Step 2: Run it and verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests '*MapMarksStoreTest*'`
Expected: FAIL — `Unresolved reference: MapMarksStore`.

- [ ] **Step 3: Implement `MapMarksStore.kt`**

```kotlin
package com.landrecords.app.data.maps

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Per-village map marks. The [com.landrecords.app.data.storage.VfScansStore] analogue: individually
 * marked map parcels live in a JSON manifest, not Room — Room holds only properties, surveys and
 * records, and adding a table for this would mean a migration for data that is a sidecar to a
 * downloaded sheet.
 *
 * Layout: filesDir/maps/<villageId>/marks.json
 *
 * [Mark.mark] is a [com.landrecords.app.ui.marked.MarkColor] id, so map marks reuse the existing
 * six-colour palette and the MarkDot control unchanged.
 */
object MapMarksStore {

    private const val MANIFEST = "marks.json"

    /** How a mark came to exist: suggested by the index, confirmed by dad, or drawn by him. */
    enum class Source { AUTO, CONFIRMED, MANUAL }

    data class Mark(
        val surveyNo: String,      // land data — stored exactly as the index spells it
        val mark: String?,         // MarkColor id, null = flagged but uncoloured
        val note: String,
        val source: Source,
        val updatedAt: Long,
    )

    private fun dir(context: Context, villageId: Long) =
        File(context.filesDir, "maps/$villageId")

    fun toJson(marks: List<Mark>): String {
        val arr = JSONArray()
        marks.forEach { m ->
            arr.put(
                JSONObject()
                    .put("surveyNo", m.surveyNo)
                    .put("mark", m.mark ?: JSONObject.NULL)
                    .put("note", m.note)
                    .put("source", m.source.name)
                    .put("updatedAt", m.updatedAt),
            )
        }
        return JSONObject().put("marks", arr).toString()
    }

    fun fromJson(json: String): List<Mark> = runCatching {
        val arr = JSONObject(json).getJSONArray("marks")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Mark(
                surveyNo = o.getString("surveyNo"),
                mark = if (o.isNull("mark")) null else o.getString("mark"),
                note = o.optString("note", ""),
                // An unrecognised source must not lose dad's mark — keep it, treat it as his own.
                source = runCatching { Source.valueOf(o.getString("source")) }.getOrDefault(Source.MANUAL),
                updatedAt = o.optLong("updatedAt", 0L),
            )
        }
    }.getOrDefault(emptyList())

    suspend fun read(context: Context, villageId: Long): List<Mark> = withContext(Dispatchers.IO) {
        val f = File(dir(context, villageId), MANIFEST)
        if (f.exists()) fromJson(f.readText()) else emptyList()
    }

    /** Upserts one parcel's mark by survey number. */
    suspend fun set(context: Context, villageId: Long, mark: Mark) = withContext(Dispatchers.IO) {
        val d = dir(context, villageId).apply { mkdirs() }
        val next = read(context, villageId).filterNot { it.surveyNo == mark.surveyNo } + mark
        File(d, MANIFEST).writeText(toJson(next))
    }

    suspend fun clear(context: Context, villageId: Long, surveyNo: String) = withContext(Dispatchers.IO) {
        val d = dir(context, villageId)
        if (!d.exists()) return@withContext
        File(d, MANIFEST).writeText(toJson(read(context, villageId).filterNot { it.surveyNo == surveyNo }))
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests '*MapMarksStoreTest*'`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/landrecords/app/data/maps/MapMarksStore.kt android/app/src/test
git commit -m "feat(maps): persist parcel marks as a per-village manifest"
```

---

### Task 7: Browse screen and navigation

**Files:**
- Create: `android/app/src/main/java/com/landrecords/app/ui/maps/MapsViewModel.kt`
- Create: `android/app/src/main/java/com/landrecords/app/ui/maps/MapsBrowseScreen.kt`
- Create: `android/app/src/main/java/com/landrecords/app/ui/maps/SheetScreen.kt`
- Modify: `android/app/src/main/java/com/landrecords/app/ui/nav/AppNav.kt`
- Modify: `android/app/src/main/java/com/landrecords/app/ui/library/LibraryScreen.kt` (add the entry point)
- Modify: `android/app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `MapCatalog`, `MapSheet`, `SheetType` (Task 4); `MapIndexStore` (Task 5).
- Produces: routes `Routes.MAPS`, `Routes.sheet(driveFileId)`, `Routes.villageMap(villageId)`.

- [ ] **Step 1: Add the strings**

Append to `res/values/strings.xml` (both languages, every one):

```xml
    <string name="maps_title_gu">નકશા</string>
    <string name="maps_title_en">Maps</string>
    <string name="maps_select_district_gu">જિલ્લો પસંદ કરો</string>
    <string name="maps_select_district_en">Select district</string>
    <string name="maps_select_taluka_gu">તાલુકો પસંદ કરો</string>
    <string name="maps_select_taluka_en">Select taluka</string>
    <string name="maps_open_sheet_gu">આખો નકશો ખોલો</string>
    <string name="maps_open_sheet_en">Open full sheet</string>
    <string name="maps_link_only_gu">ફક્ત નકશાની લિંક</string>
    <string name="maps_link_only_en">Sheet only</string>
    <string name="maps_interactive_gu">શોધી શકાય તેવો નકશો</string>
    <string name="maps_interactive_en">Searchable map</string>
    <string name="maps_downloading_gu">નકશો ઉતારી રહ્યા છીએ…</string>
    <string name="maps_downloading_en">Downloading sheet…</string>
    <string name="maps_offline_gu">નકશો હજી ઉતાર્યો નથી — ઇન્ટરનેટ જોઈશે</string>
    <string name="maps_offline_en">This sheet is not downloaded yet — needs internet</string>
```

- [ ] **Step 2: Write `MapsViewModel.kt`**

```kotlin
package com.landrecords.app.ui.maps

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.landrecords.app.data.maps.MapCatalog
import com.landrecords.app.data.maps.MapSheet
import com.landrecords.app.data.maps.SheetType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MapsUiState(
    val loading: Boolean = true,
    val districts: List<String> = emptyList(),
    val talukas: List<String> = emptyList(),
    val villages: List<MapSheet> = emptyList(),
    val district: String? = null,
    val taluka: String? = null,
    /** villageId → true when a searchable index exists for it. */
    val indexed: Set<Long> = emptySet(),
)

class MapsViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(MapsUiState())
    val state: StateFlow<MapsUiState> = _state.asStateFlow()
    private var catalog: MapCatalog? = null

    init {
        viewModelScope.launch {
            val c = MapCatalog.load(getApplication())
            catalog = c
            _state.value = MapsUiState(
                loading = false,
                districts = c.districts(SheetType.VILLAGE_MAP),
                indexed = IndexedVillages.load(getApplication()),
            )
        }
    }

    fun selectDistrict(name: String) {
        val c = catalog ?: return
        _state.value = _state.value.copy(
            district = name, taluka = null,
            talukas = c.talukas(SheetType.VILLAGE_MAP, name),
            villages = emptyList(),
        )
    }

    fun selectTaluka(name: String) {
        val c = catalog ?: return
        val d = _state.value.district ?: return
        _state.value = _state.value.copy(
            taluka = name,
            villages = c.villages(SheetType.VILLAGE_MAP, d, name),
        )
    }
}
```

- [ ] **Step 3: Add `IndexedVillages`**

Create it inside `MapsViewModel.kt` (it is three lines of policy, not its own file):

```kotlin
/**
 * Which villages have a searchable index. Read from the shipped manifest asset so the browse list
 * can be honest about coverage before anything is downloaded.
 */
object IndexedVillages {
    suspend fun load(context: android.content.Context): Set<Long> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val json = context.assets.open("maps/manifest.json").bufferedReader().use { it.readText() }
                val arr = org.json.JSONObject(json).getJSONArray("villages")
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.getJSONObject(i)
                    if (o.optString("quality") == "GOOD") o.getLong("villageId") else null
                }.toSet()
            }.getOrDefault(emptySet())
        }
}
```

Copy the manifest into assets:

```bash
cp tools/ejamin/out/indexes/manifest.json android/app/src/main/assets/maps/manifest.json
```

- [ ] **Step 4: Write `MapsBrowseScreen.kt`**

```kotlin
package com.landrecords.app.ui.maps

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.landrecords.app.R
import com.landrecords.app.data.maps.MapSheet
import com.landrecords.app.ui.components.dashedInset
import com.landrecords.app.ui.theme.Land
import com.landrecords.app.ui.theme.LandShape
import com.landrecords.app.ui.theme.LandType
import com.landrecords.app.ui.theme.Lr

/**
 * District → taluka → village browse over the eJamin catalogue. Villages with a searchable index
 * say so; the rest are shown plainly as sheet-only rather than dressed up as interactive.
 */
@Composable
fun MapsBrowseScreen(
    onOpenVillageMap: (MapSheet) -> Unit,
    onOpenSheet: (MapSheet) -> Unit,
    onBack: () -> Unit,
) {
    val vm: MapsViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()

    Column(
        Modifier.fillMaxSize().background(Land.colors.bg).padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        Text(Lr(R.string.maps_title_gu, R.string.maps_title_en), style = LandType.screenTitle, color = Land.colors.ink)
        Spacer(Modifier.height(12.dp))

        ChipRow(
            label = Lr(R.string.maps_select_district_gu, R.string.maps_select_district_en),
            options = state.districts, selected = state.district, onSelect = vm::selectDistrict,
        )
        if (state.district != null) {
            Spacer(Modifier.height(8.dp))
            ChipRow(
                label = Lr(R.string.maps_select_taluka_gu, R.string.maps_select_taluka_en),
                options = state.talukas, selected = state.taluka, onSelect = vm::selectTaluka,
            )
        }

        Spacer(Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(state.villages, key = { it.driveFileId }) { sheet ->
                val id = sheet.villageId
                val searchable = id != null && id in state.indexed
                VillageMapTile(
                    sheet = sheet,
                    searchable = searchable,
                    onClick = { if (searchable) onOpenVillageMap(sheet) else onOpenSheet(sheet) },
                )
            }
        }
    }
}

@Composable
private fun ChipRow(label: String, options: List<String>, selected: String?, onSelect: (String) -> Unit) {
    Column {
        Text(label, style = LandType.label, color = Land.colors.ink3)
        Spacer(Modifier.height(6.dp))
        LazyRowOfChips(options, selected, onSelect)
    }
}

@Composable
private fun LazyRowOfChips(options: List<String>, selected: String?, onSelect: (String) -> Unit) {
    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(options) { o ->
            val on = o == selected
            Text(
                o,
                style = LandType.meta,
                color = if (on) Land.colors.accent else Land.colors.ink2,
                modifier = Modifier
                    .clip(LandShape.tile)
                    .background(if (on) Land.colors.accentSoft else Land.colors.surface)
                    .border(1.dp, if (on) Land.colors.accent else Land.colors.line, LandShape.tile)
                    .clickable { onSelect(o) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}

/** A village drawn as a parcel tile: 1dp border, r12, dashed inset at 5dp. */
@Composable
private fun VillageMapTile(sheet: MapSheet, searchable: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(LandShape.tile)
            .background(Land.colors.surface)
            .border(1.dp, Land.colors.line, LandShape.tile)
            .dashedInset(Land.colors.hair)
            .clickable(onClick = onClick)
            .padding(15.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(sheet.villageName ?: "—", style = LandType.bodyStrong, color = Land.colors.ink)
            Spacer(Modifier.height(4.dp))
            Text(
                if (searchable) Lr(R.string.maps_interactive_gu, R.string.maps_interactive_en)
                else Lr(R.string.maps_link_only_gu, R.string.maps_link_only_en),
                style = LandType.label,
                color = if (searchable) Land.colors.accent else Land.colors.ink3,
            )
        }
    }
}
```

- [ ] **Step 5: Write `SheetScreen.kt`**

```kotlin
package com.landrecords.app.ui.maps

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.landrecords.app.R
import com.landrecords.app.data.maps.MapIndexStore
import com.landrecords.app.data.maps.MapSheet
import com.landrecords.app.ui.theme.Land
import com.landrecords.app.ui.theme.LandType
import com.landrecords.app.ui.theme.Lr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The authoritative document: the original A0 sheet, rendered from the cached PDF. This is the
 * fallback every other failure path lands on, so it must work with nothing but the file.
 */
@Composable
fun SheetScreen(sheet: MapSheet, onBack: () -> Unit) {
    val context = LocalContext.current
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(sheet.driveFileId) {
        val file = MapIndexStore.downloadPdf(context, sheet)
        if (file == null) { failed = true; return@LaunchedEffect }
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    PdfRenderer(pfd).use { r ->
                        r.openPage(0).use { page ->
                            // A0 at full scale would be ~14k px; 2400 wide is legible and safe.
                            val w = 2400
                            val h = (w.toFloat() * page.height / page.width).toInt()
                            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                                it.eraseColor(android.graphics.Color.WHITE)
                                page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            }
                        }
                    }
                }
            }.getOrNull()
        }
        if (bitmap == null) failed = true
    }

    Box(Modifier.fillMaxSize().background(Land.colors.bg), contentAlignment = Alignment.Center) {
        when {
            bitmap != null -> Image(bitmap!!.asImageBitmap(), contentDescription = sheet.villageName)
            failed -> Text(
                Lr(R.string.maps_offline_gu, R.string.maps_offline_en),
                style = LandType.meta, color = Land.colors.ink3,
            )
            else -> Text(
                Lr(R.string.maps_downloading_gu, R.string.maps_downloading_en),
                style = LandType.meta, color = Land.colors.ink3,
            )
        }
    }
}
```

- [ ] **Step 6: Wire navigation**

In `ui/nav/AppNav.kt`, add to `private object Routes`:

```kotlin
    const val MAPS = "maps"
    const val VILLAGE_MAP = "village_map/{villageId}/{driveFileId}"
    const val SHEET = "sheet/{driveFileId}"

    fun villageMap(villageId: Long, driveFileId: String) = "village_map/$villageId/$driveFileId"
    fun sheet(driveFileId: String) = "sheet/$driveFileId"
```

Add the destinations inside the `NavHost` block, following the existing `composable(...)` style. Resolve a `MapSheet` from its `driveFileId` via `MapCatalog.load(context).sheets.first { it.driveFileId == id }`, and add an `onMaps = { navController.navigate(Routes.MAPS) }` callback to `LibraryScreen` alongside the existing `onMarked`, surfaced with the same affordance the Marked entry point uses.

- [ ] **Step 7: Build and run on device**

```bash
cd android && ./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Open Maps, pick Anand → a taluka, tap a **sheet-only** village. Expected: the A0 sheet renders. Turn off wifi and re-open the same village: it still renders from cache. Then check the file landed in the visible tree:

```bash
adb shell ls -la /sdcard/Documents/LandRecords/maps/sheets/ | head
```

- [ ] **Step 8: Commit**

```bash
git add android/app/src/main/java/com/landrecords/app/ui/maps android/app/src/main/java/com/landrecords/app/ui/nav/AppNav.kt android/app/src/main/java/com/landrecords/app/ui/library/LibraryScreen.kt android/app/src/main/res/values/strings.xml android/app/src/main/assets/maps/manifest.json
git commit -m "feat(maps): browse every eJamin sheet and view the full A0 map"
```

**The feature is useful from here on.** Everything below adds the interactive map.

---

### Task 8: The parcel canvas

**Files:**
- Create: `android/app/src/main/java/com/landrecords/app/ui/maps/ParcelCanvas.kt`

**Interfaces:**
- Consumes: `MapIndex`, `Parcel`, `MapGeometry` (Tasks 2–3).
- Produces:
  ```kotlin
  data class Camera(val scale: Float, val offsetX: Float, val offsetY: Float)
  fun fitCamera(index: MapIndex, viewW: Float, viewH: Float): Camera
  fun fitToParcel(parcel: Parcel, viewW: Float, viewH: Float, pageH: Float): Camera
  @Composable fun ParcelCanvas(
      index: MapIndex, camera: Camera, onCamera: (Camera) -> Unit,
      selected: Parcel?, neighbours: Set<Int>, markColours: Map<Int, Color>,
      onTapParcel: (Parcel?) -> Unit, onLongPressParcel: (Parcel) -> Unit,
      modifier: Modifier = Modifier,
  )
  ```

- [ ] **Step 1: Implement `ParcelCanvas.kt`**

```kotlin
package com.landrecords.app.ui.maps

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import com.landrecords.app.ui.maps.geometry.MapGeometry
import com.landrecords.app.ui.maps.geometry.MapIndex
import com.landrecords.app.ui.maps.geometry.Parcel
import com.landrecords.app.ui.theme.Land

/** Page space → screen. PDF y grows upward, the canvas downward, so y is flipped once, here. */
data class Camera(val scale: Float, val offsetX: Float, val offsetY: Float)

fun fitCamera(index: MapIndex, viewW: Float, viewH: Float): Camera {
    val s = minOf(viewW / index.pageWidth, viewH / index.pageHeight)
    return Camera(s, (viewW - index.pageWidth * s) / 2f, (viewH - index.pageHeight * s) / 2f)
}

/** Frames one plot with a comfortable margin — the search result's landing camera. */
fun fitToParcel(parcel: Parcel, viewW: Float, viewH: Float, pageH: Float): Camera {
    val b = MapGeometry.bounds(parcel.poly)
    val w = (b[2] - b[0]).coerceAtLeast(1f)
    val h = (b[3] - b[1]).coerceAtLeast(1f)
    val s = (minOf(viewW / w, viewH / h) * 0.55f).coerceIn(0.05f, 40f)
    val cx = (b[0] + b[2]) / 2f
    val cy = pageH - (b[1] + b[3]) / 2f  // flipped, to match the canvas
    return Camera(s, viewW / 2f - cx * s, viewH / 2f - cy * s)
}

private fun Camera.toScreen(x: Float, y: Float, pageH: Float) =
    Offset(x * scale + offsetX, (pageH - y) * scale + offsetY)

private fun Camera.toPage(sx: Float, sy: Float, pageH: Float) =
    floatArrayOf((sx - offsetX) / scale, pageH - (sy - offsetY) / scale)

/**
 * The map itself. Draws the sheet as vectors so it stays sharp at any zoom and so a tap can select
 * a real plot. Nothing here invents geometry — every polygon comes from the index.
 */
@Composable
fun ParcelCanvas(
    index: MapIndex,
    camera: Camera,
    onCamera: (Camera) -> Unit,
    selected: Parcel?,
    neighbours: Set<Int>,
    markColours: Map<Int, Color>,
    onTapParcel: (Parcel?) -> Unit,
    onLongPressParcel: (Parcel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pageH = index.pageHeight
    val line = Land.colors.line
    val accent = Land.colors.accent
    val accentSoft = Land.colors.accentSoft

    Canvas(
        modifier
            .pointerInput(index.villageId) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val s = (camera.scale * zoom).coerceIn(0.02f, 60f)
                    onCamera(
                        Camera(
                            scale = s,
                            offsetX = camera.offsetX + pan.x,
                            offsetY = camera.offsetY + pan.y,
                        ),
                    )
                }
            }
            .pointerInput(index.villageId, camera) {
                detectTapGestures(
                    onTap = { o ->
                        val p = camera.toPage(o.x, o.y, pageH)
                        onTapParcel(MapGeometry.parcelAt(index, p[0], p[1]))
                    },
                    onLongPress = { o ->
                        val p = camera.toPage(o.x, o.y, pageH)
                        MapGeometry.parcelAt(index, p[0], p[1])?.let(onLongPressParcel)
                    },
                )
            },
    ) {
        fun pathOf(parcel: Parcel) = Path().apply {
            parcel.poly.forEachIndexed { i, pt ->
                val o = camera.toScreen(pt[0], pt[1], pageH)
                if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y)
            }
            close()
        }

        index.parcels.forEach { parcel ->
            val path = pathOf(parcel)
            markColours[parcel.id]?.let { drawPath(path, it.copy(alpha = 0.28f)) }
            drawPath(path, line, style = Stroke(width = 1f))
        }

        // Neighbours above the base sheet, the selection above everything.
        neighbours.forEach { id ->
            index.parcels.getOrNull(id)?.let {
                drawPath(pathOf(it), accent, style = Stroke(width = 2f))
            }
        }
        selected?.let {
            val path = pathOf(it)
            drawPath(path, accentSoft.copy(alpha = 0.55f))
            drawPath(path, accent, style = Stroke(width = 3f))
        }
    }
}
```

- [ ] **Step 2: Build**

Run: `cd android && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/java/com/landrecords/app/ui/maps/ParcelCanvas.kt
git commit -m "feat(maps): pan/zoom parcel canvas with tap selection"
```

---

### Task 9: The village map screen — search and adjoining

**Files:**
- Create: `android/app/src/main/java/com/landrecords/app/ui/maps/VillageMapViewModel.kt`
- Create: `android/app/src/main/java/com/landrecords/app/ui/maps/VillageMapScreen.kt`
- Modify: `android/app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: everything from Tasks 2–8.
- Produces: the `Routes.VILLAGE_MAP` destination's content.

- [ ] **Step 1: Add the strings**

```xml
    <string name="maps_search_hint_gu">સર્વે નંબર શોધો</string>
    <string name="maps_search_hint_en">Search survey number</string>
    <string name="maps_not_on_sheet_gu">આ નકશા પર આ સર્વે નંબર નથી</string>
    <string name="maps_not_on_sheet_en">That survey number is not on this sheet</string>
    <string name="maps_adjoining_gu">બાજુની મિલકતો</string>
    <string name="maps_adjoining_en">Adjoining</string>
    <string name="maps_confirm_gu">ખાતરી કરો</string>
    <string name="maps_confirm_en">Confirm</string>
    <string name="maps_confirmed_gu">ખાતરી કરેલ</string>
    <string name="maps_confirmed_en">Confirmed</string>
```

- [ ] **Step 2: Write `VillageMapViewModel.kt`**

```kotlin
package com.landrecords.app.ui.maps

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.landrecords.app.data.maps.MapIndexStore
import com.landrecords.app.data.maps.MapMarksStore
import com.landrecords.app.ui.maps.geometry.MapIndex
import com.landrecords.app.ui.maps.geometry.Parcel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class VillageMapUiState(
    val loading: Boolean = true,
    val index: MapIndex? = null,
    /** True when there is no usable index — the screen must offer the full sheet instead. */
    val unavailable: Boolean = false,
    val query: String = "",
    val notFound: Boolean = false,
    val selected: Parcel? = null,
    val neighbours: List<Parcel> = emptyList(),
    val marks: List<MapMarksStore.Mark> = emptyList(),
)

class VillageMapViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(VillageMapUiState())
    val state: StateFlow<VillageMapUiState> = _state.asStateFlow()
    private var villageId: Long = 0

    fun load(villageId: Long, sha256: String?) {
        this.villageId = villageId
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val index = MapIndexStore.cachedIndex(ctx, villageId)
                ?: MapIndexStore.fetchIndex(ctx, villageId, sha256)
            _state.value = _state.value.copy(
                loading = false,
                index = index,
                unavailable = index == null,
                marks = MapMarksStore.read(ctx, villageId),
            )
        }
    }

    /** Search never guesses: an absent number is reported absent, and the map does not move. */
    fun search(query: String) {
        val index = _state.value.index
        val hit = if (query.isBlank()) null else index?.findBySurveyNo(query)
        _state.value = _state.value.copy(
            query = query,
            notFound = query.isNotBlank() && hit == null,
            selected = hit ?: _state.value.selected,
            neighbours = hit?.let { index!!.neighbours(it) } ?: _state.value.neighbours,
        )
    }

    fun select(parcel: Parcel?) {
        val index = _state.value.index
        _state.value = _state.value.copy(
            selected = parcel,
            neighbours = parcel?.let { index?.neighbours(it) } ?: emptyList(),
            notFound = false,
        )
    }

    /** Promotes a suggested neighbour to a confirmed one, or colour-marks a plot outright. */
    fun mark(surveyNo: String, colour: String?, source: MapMarksStore.Source, note: String = "") {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            MapMarksStore.set(
                ctx, villageId,
                MapMarksStore.Mark(surveyNo, colour, note, source, System.currentTimeMillis()),
            )
            _state.value = _state.value.copy(marks = MapMarksStore.read(ctx, villageId))
        }
    }

    fun unmark(surveyNo: String) {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            MapMarksStore.clear(ctx, villageId, surveyNo)
            _state.value = _state.value.copy(marks = MapMarksStore.read(ctx, villageId))
        }
    }
}
```

- [ ] **Step 3: Write `VillageMapScreen.kt`**

Compose the pieces: a top bar with the search field (`LandType.metaMono`, `Lr(R.string.maps_search_hint_gu, ...)`) and an **Open full sheet** action; the `ParcelCanvas` filling the rest; and a bottom sheet listing `state.neighbours`.

Rules this screen must honour, each already available as a primitive:

- On a search hit, animate the camera with `animateFloatAsState` to `fitToParcel(...)`; when `LocalAccessibilityManager` reports reduced motion, or `Settings.Global.TRANSITION_ANIMATION_SCALE` is `0`, jump instead. Follow whatever `LandMotion` already does for this — read `ui/theme/Theme.kt` and reuse it rather than inventing a second reduced-motion check.
- On `state.notFound`, show `maps_not_on_sheet` in `Land.colors.ink3` and leave the camera where it is.
- The neighbour list shows each `surveyNo` in `LandType.metaMono` as land data, never translated, with a Latin helper line via `guToLatinDigits()` from `ui/components/Foundations.kt` when the string carries Gujarati digits.
- Each neighbour row carries a `MarkDot(mark = ..., onSet = { vm.mark(surveyNo, it, Source.CONFIRMED) })` — the identical control the record cards and case rows use.
- `state.unavailable` renders the `SheetScreen` content for this sheet instead of an error.
- Long-press on the canvas calls `vm.mark(parcel.surveyNo, colour, Source.MANUAL)` through the same `MarkDot` menu.
- `markColours` passed to `ParcelCanvas` maps parcel id → `MarkColor.from(mark.mark)?.swatch`, resolved by matching `mark.surveyNo` to `parcel.surveyNo`.

- [ ] **Step 4: Build and check both themes**

```bash
cd android && ./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On device, open an indexed Anand village. Verify in order:
1. The sheet draws as vectors and pans/zooms smoothly.
2. Searching a survey number you verified in Plan 1 Task 10 Step 6 frames that plot.
3. Its neighbour count matches what the index reported there.
4. Searching `999999` shows "not on this sheet" and does not move the map.
5. Switch the system to dark theme — borders, accent and labels all remain legible.
6. Switch the app language to `gu` and to `en` — chrome follows, survey numbers do not change.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/landrecords/app/ui/maps android/app/src/main/res/values/strings.xml
git commit -m "feat(maps): survey-number search and adjoining-parcel confirmation"
```

---

### Task 10: Publish the indexes

**Files:**
- Modify: the `kirtan05/land-records-releases` repo (a `maps/` directory)

- [ ] **Step 1: Publish**

Copy every `village-*.index.json` from `tools/ejamin/out/indexes/` into `maps/` in the releases repo, alongside `manifest.json`, and push. `MapIndexStore.BASE_URL` already points at `.../main/maps/`.

- [ ] **Step 2: Verify a cold fetch on device**

Clear the app's map cache and open an indexed village with wifi on:

```bash
adb shell rm -rf /sdcard/Documents/LandRecords/maps
```

Expected: the index downloads, the map renders. Then turn wifi off and re-open it — expected: it still renders, from cache.

- [ ] **Step 3: Verify checksum enforcement**

Corrupt one cached index on device and re-open the village:

```bash
adb shell "echo broken > /sdcard/Documents/LandRecords/maps/village-<ID>.index.json"
```

Expected: the app does not crash and does not draw a broken map — it refetches, or falls back to the full sheet.

---

### Task 11: Release

**Files:**
- Modify: `android/app/build.gradle.kts` (`versionCode`, `versionName`)
- Modify: `kirtan05/land-records-releases` `update.json`

- [ ] **Step 1: Run everything**

```bash
node --test tools/ejamin/test/
cd android && ./gradlew :app:testDebugUnitTest && ./gradlew :app:assembleDebug
```

Expected: all pipeline tests pass, all JVM unit tests pass, BUILD SUCCESSFUL. Do not proceed on any failure.

- [ ] **Step 2: Bump the version**

In `android/app/build.gradle.kts`: `versionCode = 19`, `versionName = "0.9.0"` — a new feature, so the minor version moves.

- [ ] **Step 3: Commit and publish**

```bash
git add android/app/build.gradle.kts
git commit -m "Release v0.9.0: Maps — eJamin sheets, searchable Kheda/Anand cadastre, adjoining + marking"
```

Then publish the slim update APK and update `update.json` following the existing release pipeline.

---

## Self-review notes

Checked against the spec:

- §3.3 components → Tasks 1–6 (`geometry/`, `MapCatalog`, `MapIndexStore`, `MapMarksStore`).
- §4.1 browse, parcel tiles, honest link-only rows → Task 7.
- §4.2 canvas, search, adjoining, manual marking, open full sheet → Tasks 7–9.
- §5 error handling → Task 5 (verify-then-write, `.part` files, `%PDF` magic check), Task 7 (offline copy), Task 9 (`unavailable`, `notFound`), Task 10 (cold-fetch and corruption checks).
- §6 testing → Task 1 creates the source set; `SurveyNo`, `MapIndex`, `MapGeometry`, `MapCatalog` and `MapMarksStore` each carry JVM tests. Room migration tests are **not** present because the design no longer adds Room tables.
- §7 build order → Tasks 4–9 follow it; the feature ships useful at Task 7.

Known deliberate gap: `MapIndexStore` and the Compose screens have no automated tests, verified on device instead. That matches how `Updater.kt`, `OrderDownloader.kt` and every existing screen in this app are handled — adding an instrumentation harness for Maps alone would be new infrastructure the spec did not ask for.
