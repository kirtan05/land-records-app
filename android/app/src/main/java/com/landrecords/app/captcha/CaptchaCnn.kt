package com.landrecords.app.captcha

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure-Kotlin forward pass of the AnyRoR captcha CNN — no native libs, no ONNX Runtime.
 *
 * Weights come from `tools/captcha/export_weights.py` (magic "LRCNN"), with
 * BatchNorm already folded into each conv, so the pass is exactly:
 *
 *   conv3x3/pad1 → ReLU → maxpool2   [1→32]   64x160 → 32x80
 *   conv3x3/pad1 → ReLU → maxpool2   [32→64]  32x80  → 16x40
 *   conv3x3/pad1 → ReLU → maxpool2   [64→128] 16x40  → 8x20
 *   conv3x3/pad1 → ReLU              [128→128]
 *   adaptive avg pool → 2x5 (clean 4x4 means) → flatten 1280
 *   Linear(1280→384) → ReLU → 6 × Linear(384→10)
 *
 * Preprocessing matches training: Rec.601 luma on the ORIGINAL pixels, then a
 * bilinear (filter = true) resize to 160x64, then /255.
 */
class CaptchaCnn(context: Context) : Closeable {

    private class Conv(val cout: Int, val cin: Int, val w: FloatArray, val b: FloatArray)

    private val convs: Array<Conv>
    private val fcW: FloatArray      // [384][1280] row-major
    private val fcB: FloatArray
    private val fcOut: Int
    private val fcIn: Int
    private val headW: Array<FloatArray>
    private val headB: Array<FloatArray>
    private val nClasses: Int

    init {
        val blob = context.assets.open(WEIGHTS_ASSET).use { it.readBytes() }
        require(blob.size > 22) { "weights blob truncated" }
        for (i in MAGIC.indices) {
            require(blob[i] == MAGIC[i]) { "bad weights magic" }
        }
        val bb = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        bb.position(MAGIC.size)

        val nConv = bb.int
        val shapes = Array(nConv) { IntArray(4) { bb.int } }
        fcOut = bb.int
        fcIn = bb.int
        val nHeads = bb.int
        nClasses = bb.int

        val fb = bb.asFloatBuffer()
        fun take(n: Int): FloatArray {
            val a = FloatArray(n)
            fb.get(a)
            return a
        }

        convs = Array(nConv) { i ->
            val s = shapes[i]
            require(s[2] == 3 && s[3] == 3) { "only 3x3 convs supported" }
            val w = take(s[0] * s[1] * 9)
            val b = take(s[0])
            Conv(s[0], s[1], w, b)
        }
        require(nConv == 4) { "expected 4 convs, got $nConv" }
        fcW = take(fcOut * fcIn)
        fcB = take(fcOut)
        require(nHeads == DIGITS) { "expected $DIGITS heads, got $nHeads" }
        // heads are stored as w,b pairs, in order
        val hw = Array(nHeads) { FloatArray(0) }
        val hb = Array(nHeads) { FloatArray(0) }
        for (h in 0 until nHeads) {
            hw[h] = take(nClasses * fcOut)
            hb[h] = take(nClasses)
        }
        headW = hw
        headB = hb
        require(fb.remaining() == 0) { "weights blob has ${fb.remaining()} trailing floats" }
    }

    /** @return the 6 digits and the joint confidence (product of the per-digit maxima). */
    fun solve(png: ByteArray): Pair<String, Float> {
        val logits = logits(png)
        val sb = StringBuilder(DIGITS)
        var conf = 1f
        for (d in 0 until DIGITS) {
            val probs = softmax(logits[d])
            var best = 0
            for (i in probs.indices) if (probs[i] > probs[best]) best = i
            sb.append(best)
            conf *= probs[best]
        }
        return sb.toString() to conf
    }

    /** Raw per-head logits, [6][10]. */
    fun logits(png: ByteArray): Array<FloatArray> {
        val src = BitmapFactory.decodeByteArray(png, 0, png.size)
            ?: throw IllegalArgumentException("not a decodable image")
        return forward(preprocess(src))
    }

    /** @param x 1x64x160 in [0,1], row-major. */
    fun forward(x: FloatArray): Array<FloatArray> {
        var h = H
        var w = W
        var cur = x

        // block 1..3: conv → relu → pool
        for (i in 0..2) {
            cur = conv3x3(cur, convs[i], h, w)
            reluInPlace(cur)
            cur = maxPool2(cur, convs[i].cout, h, w)
            h /= 2; w /= 2
        }
        // block 4: conv → relu (no pool)
        cur = conv3x3(cur, convs[3], h, w)
        reluInPlace(cur)

        val c = convs[3].cout
        val feat = adaptiveAvg2x5(cur, c, h, w)   // c*2*5 = 1280

        val hid = FloatArray(fcOut)
        for (o in 0 until fcOut) {
            var acc = fcB[o]
            val base = o * fcIn
            for (i in 0 until fcIn) acc += fcW[base + i] * feat[i]
            hid[o] = if (acc > 0f) acc else 0f
        }

        return Array(DIGITS) { d ->
            val wgt = headW[d]
            val bias = headB[d]
            FloatArray(nClasses) { k ->
                var acc = bias[k]
                val base = k * fcOut
                for (i in 0 until fcOut) acc += wgt[base + i] * hid[i]
                acc
            }
        }
    }

    // ---- layers -----------------------------------------------------------

    private fun conv3x3(x: FloatArray, c: Conv, h: Int, w: Int): FloatArray {
        val out = FloatArray(c.cout * h * w)
        val plane = h * w
        for (co in 0 until c.cout) {
            val ob = co * plane
            val bias = c.b[co]
            java.util.Arrays.fill(out, ob, ob + plane, bias)
            for (ci in 0 until c.cin) {
                val ib = ci * plane
                val kb = (co * c.cin + ci) * 9
                for (ky in 0..2) {
                    for (kx in 0..2) {
                        val kv = c.w[kb + ky * 3 + kx]
                        if (kv == 0f) continue
                        // output (y,x) reads input (y+ky-1, x+kx-1)
                        val dy = ky - 1
                        val dx = kx - 1
                        val y0 = if (dy < 0) 1 else 0
                        val y1 = if (dy > 0) h - 1 else h
                        val x0 = if (dx < 0) 1 else 0
                        val x1 = if (dx > 0) w - 1 else w
                        for (y in y0 until y1) {
                            var oi = ob + y * w + x0
                            var ii = ib + (y + dy) * w + (x0 + dx)
                            var xx = x0
                            while (xx < x1) {
                                out[oi] += kv * x[ii]
                                oi++; ii++; xx++
                            }
                        }
                    }
                }
            }
        }
        return out
    }

    private fun reluInPlace(a: FloatArray) {
        for (i in a.indices) if (a[i] < 0f) a[i] = 0f
    }

    private fun maxPool2(x: FloatArray, c: Int, h: Int, w: Int): FloatArray {
        val oh = h / 2
        val ow = w / 2
        val out = FloatArray(c * oh * ow)
        var oi = 0
        for (ch in 0 until c) {
            val base = ch * h * w
            for (y in 0 until oh) {
                val r0 = base + (2 * y) * w
                val r1 = r0 + w
                for (xx in 0 until ow) {
                    val i0 = r0 + 2 * xx
                    val i1 = r1 + 2 * xx
                    var m = x[i0]
                    if (x[i0 + 1] > m) m = x[i0 + 1]
                    if (x[i1] > m) m = x[i1]
                    if (x[i1 + 1] > m) m = x[i1 + 1]
                    out[oi++] = m
                }
            }
        }
        return out
    }

    /** Adaptive average pool to 2x5; h and w must divide evenly (8x20 → 4x4 cells). */
    private fun adaptiveAvg2x5(x: FloatArray, c: Int, h: Int, w: Int): FloatArray {
        val bh = h / 2
        val bw = w / 5
        require(bh * 2 == h && bw * 5 == w) { "adaptive pool needs even blocks ($h x $w)" }
        val inv = 1f / (bh * bw)
        val out = FloatArray(c * 10)
        var oi = 0
        for (ch in 0 until c) {
            val base = ch * h * w
            for (ry in 0 until 2) {
                for (rx in 0 until 5) {
                    var acc = 0f
                    for (y in ry * bh until (ry + 1) * bh) {
                        var i = base + y * w + rx * bw
                        for (k in 0 until bw) acc += x[i + k]
                    }
                    out[oi++] = acc * inv
                }
            }
        }
        return out
    }

    // ---- preprocessing (identical to the ORT solver) -----------------------

    fun preprocess(src: Bitmap): FloatArray {
        val gray = toGray(src)
        val scaled = if (gray.width == W && gray.height == H) gray
        else Bitmap.createScaledBitmap(gray, W, H, /* filter = */ true)
        val px = IntArray(W * H)
        scaled.getPixels(px, 0, W, 0, 0, W, H)
        val out = FloatArray(W * H)
        for (i in px.indices) out[i] = (px[i] and 0xFF) / 255f
        return out
    }

    private fun toGray(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val p = px[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val y = (0.299f * r + 0.587f * g + 0.114f * b + 0.5f).toInt().coerceIn(0, 255)
            px[i] = (0xFF shl 24) or (y shl 16) or (y shl 8) or y
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun softmax(v: FloatArray): FloatArray {
        var max = v[0]
        for (x in v) if (x > max) max = x
        var sum = 0f
        val out = FloatArray(v.size)
        for (i in v.indices) {
            out[i] = Math.exp((v[i] - max).toDouble()).toFloat()
            sum += out[i]
        }
        for (i in out.indices) out[i] /= sum
        return out
    }

    override fun close() {}

    companion object {
        const val WEIGHTS_ASSET = "anyror_cnn_real.weights"
        const val W = 160
        const val H = 64
        const val DIGITS = 6
        private val MAGIC = byteArrayOf('L'.code.toByte(), 'R'.code.toByte(), 'C'.code.toByte(),
            'N'.code.toByte(), 'N'.code.toByte(), 1)
    }
}
