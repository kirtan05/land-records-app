package com.landrecords.app.captcha

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Accuracy over EVERY labelled sample, broken down by cohort (cohorts.csv):
 *   train / val / test — from tools/captcha/anyror_cnn_real.split.json
 *   new                — labelled AFTER training, never seen by the model
 * `test` and `new` are the honest numbers; `train` is inflated by construction.
 */
@RunWith(AndroidJUnit4::class)
class AnyRorSolverAccuracyTest {

    private class Tally {
        var n = 0; var exact = 0; var digitHits = 0; var digitTotal = 0
        fun line(name: String, note: String = "") = if (n == 0) "  %-6s n=0\n".format(name) else
            "  %-6s n=%-5d exact=%6.2f%%  perdigit=%6.2f%% %s\n".format(
                name, n, 100.0 * exact / n, 100.0 * digitHits / digitTotal, note,
            )
    }

    @Test(timeout = 45 * 60 * 1000L)
    fun measureAccuracyOnTestSplit() {
        val instr = InstrumentationRegistry.getInstrumentation()
        val testAssets = instr.context.assets            // androidTest assets
        val appContext = instr.targetContext             // holds the model asset

        val labels = testAssets.open("$DIR/labels.csv").bufferedReader().readLines()
            .drop(1).filter { it.isNotBlank() }
            .map { it.split(",")[0] to it.split(",")[1].trim() }

        val cohorts = testAssets.open("$DIR/cohorts.csv").bufferedReader().readLines()
            .drop(1).filter { it.isNotBlank() }
            .associate { it.split(",")[0] to it.split(",")[1].trim() }

        val solver = CaptchaCnn(appContext)
        val lines = StringBuilder()
        val mismatches = StringBuilder()

        val byCohort = linkedMapOf(
            "test" to Tally(), "new" to Tally(), "val" to Tally(), "train" to Tally(),
        )
        val all = Tally()
        var exact = 0
        var digitHits = 0
        var digitTotal = 0
        var totalNs = 0L

        // one warm-up so the first sample doesn't carry session lazy-init cost
        testAssets.open("$DIR/${labels[0].first}").use { solver.solve(it.readBytes()) }

        for ((file, expected) in labels) {
            val png = testAssets.open("$DIR/$file").use { it.readBytes() }
            val t0 = System.nanoTime()
            val (got, conf) = solver.solve(png)
            totalNs += System.nanoTime() - t0

            val hits = (0 until minOf(got.length, expected.length)).count { got[it] == expected[it] }
            val ok = got == expected
            if (ok) exact++
            digitHits += hits
            digitTotal += expected.length

            val cohort = cohorts[file] ?: "new"
            for (t in listOf(byCohort.getOrPut(cohort) { Tally() }, all)) {
                t.n++; if (ok) t.exact++; t.digitHits += hits; t.digitTotal += expected.length
            }
            if (!ok) {
                mismatches.append("  [$cohort] $file expected=$expected got=$got conf=%.4f\n".format(conf))
            }
        }
        solver.close()

        val n = labels.size
        val meanMs = totalNs / 1e6 / n
        lines.append("AnyRoR CNN on-device accuracy (ALL labelled samples, n=$n)\n")
        lines.append("by cohort (test + new are the honest numbers):\n")
        lines.append(byCohort["test"]!!.line("test", "<- held-out at training"))
        lines.append(byCohort["new"]!!.line("new", "<- labelled AFTER training, never seen"))
        lines.append(byCohort["val"]!!.line("val", "<- held out, but used for model selection"))
        lines.append(byCohort["train"]!!.line("train", "<- TRAINED ON: INFLATED, not an honest number"))
        lines.append(all.line("ALL", "<- mixes trained + unseen; read the cohorts"))
        lines.append("mean solve  : %.2f ms\n".format(meanMs))
        lines.append("mismatches  : ${n - exact}\n")
        lines.append(mismatches)

        val out = File(appContext.getExternalFilesDir(null), "captcha_accuracy.txt")
        out.writeText(lines.toString())

        lines.toString().lines().forEach { Log.i(TAG, it) }
        println(lines.toString())

        assertTrue("no samples staged", n > 0)
        // Assert on the UNSEEN cohorts only (test + new) — asserting on the mix would let a
        // regression hide behind the memorised training samples. 95% is a floor that only a
        // real regression can breach.
        val unseenN = byCohort["test"]!!.n + byCohort["new"]!!.n
        val unseenExact = byCohort["test"]!!.exact + byCohort["new"]!!.exact
        assertTrue("no unseen samples staged", unseenN > 0)
        assertTrue(
            "unseen (test+new) exact-match fell to %.2f%% (expected ≥95%%)".format(100.0 * unseenExact / unseenN),
            unseenExact >= (unseenN * 95) / 100,
        )
    }

    /**
     * Numerical parity against the Python reference (`tools/captcha/export_weights.py`
     * → `forward_numpy`), whose logits are baked into `expected_logits.bin`.
     *
     * The Kotlin pass was verified once against ONNX Runtime on-device (max |logit diff|
     * 1.26e-05, 0 prediction disagreements), but ORT was then removed to save 21 MB of APK.
     * This keeps that equivalence checkable from the tree forever, without the dependency:
     * accuracy alone would not catch a subtle arithmetic drift that still rounds to the
     * same digits on these 150 samples.
     *
     * The asset stores the ALREADY-PREPROCESSED 160x64 grayscale tensor, not the PNG, so this
     * isolates the network arithmetic. Feeding PNGs instead compares Skia's bilinear resize
     * against OpenCV's INTER_AREA: that diverges by ~1.65 in logit space — harmless (identical
     * predictions on all 150 samples) but useless as an arithmetic check.
     */
    @Test
    fun matchesPythonReferenceLogits() {
        val instr = InstrumentationRegistry.getInstrumentation()
        val blob = instr.context.assets.open("$DIR/expected_logits.bin").use { it.readBytes() }
        val bb = java.nio.ByteBuffer.wrap(blob).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(6).also { bb.get(it) }
        // NB: the file's 6-byte header is a 5-char tag + a version byte; compare them separately
        // (an earlier version embedded a raw 0x01 in this string literal - invisible, and it
        // silently pinned the test to format v1).
        assertTrue("bad magic in expected_logits.bin", String(magic, 0, 5) == "LRLGT")
        assertTrue("expected_logits.bin is format v${magic[5]}, test expects v2", magic[5].toInt() == 2)

        val cnn = CaptchaCnn(instr.targetContext)
        var maxDiff = 0.0f
        var worst = ""
        val count = bb.int
        repeat(count) {
            val name = ByteArray(bb.int).also { bb.get(it) }.let { String(it) }
            val px = ByteArray(64 * 160).also { bb.get(it) }
            val expected = Array(6) { FloatArray(10) { bb.float } }
            val got = cnn.forward(FloatArray(px.size) { (px[it].toInt() and 0xFF) / 255f })
            for (d in 0 until 6) for (k in 0 until 10) {
                val diff = kotlin.math.abs(expected[d][k] - got[d][k])
                if (diff > maxDiff) { maxDiff = diff; worst = name }
            }
        }
        cnn.close()

        Log.i(TAG, "logit parity vs python reference: n=$count max|diff|=$maxDiff (worst $worst)")
        println("logit parity: n=$count max|diff|=$maxDiff (worst $worst)")
        assertTrue("Kotlin logits diverged from the Python reference: max|diff|=$maxDiff at $worst",
            maxDiff < 1e-3f)
    }

    companion object {
        const val TAG = "CaptchaAccuracy"
        const val DIR = "captcha_samples"
    }
}
