package dev.openliveness.layers

import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Layer 4 texture analysis — LBP + Fourier. See Spec §1404-1462 and the
 * Python reference at `core/algorithms/layer4_texture.py` for the
 * authoritative math. This file is the Kotlin mirror; the iOS side is
 * `Sources/OpenLiveness/Layers/Layer4Texture.swift`. Any threshold or
 * ring-geometry change needs the sibling edit in both languages, since
 * the server re-runs the same math from `layer_scores`.
 *
 * **Fourier branch** (this file): deterministic ring-energy ratio FFT.
 * Fully portable, so we run it directly in Kotlin — no TFLite model.
 *
 * **LBP branch**: computes a uniform LBP (P=8, R=1) histogram and runs
 * it through a bundled TFLite classifier ([TextureClassifier]). The
 * histogram port is parity-tested against skimage via the
 * `layer4_lbp.json` golden fixture — a drift there would feed the
 * trained Dense weights a different feature vector and silently shift
 * `lbpScore` away from the server's re-run.
 *
 * **No-classifier mode**: constructing with `classifier = null` keeps
 * `lbpScore` at a neutral 0.85 baseline — convenient for unit tests
 * that don't want the native TFLite dep. Production always wires a
 * loaded [TextureClassifier].
 */
class Layer4Texture(
    private val classifier: TextureClassifier? = null,
    /** Executor the sink uses to run [analyzeFrame] off the caller's
     *  thread. Default is a single dedicated worker owned by this
     *  instance and shut down in [close]. The camera analyzer thread must
     *  not run the O(N⁴)=80⁴ DFT inline — a benchmarked ~3.8s stall on
     *  Snapdragon 8 Gen 2 pegged the whole MediaPipe pipeline at 0.26 Hz
     *  (every analyze call kept CameraX from delivering the next frame).
     *  Tests pass [DIRECT_EXECUTOR] to keep the call synchronous. */
    executor: java.util.concurrent.Executor? = null,
) : FaceCropSink, AutoCloseable {

    private val ownedExecutor: ExecutorService? =
        if (executor == null) Executors.newSingleThreadExecutor { r ->
            Thread(r, "openliveness-layer4").apply { isDaemon = true }
        } else null
    private val executor: java.util.concurrent.Executor = executor ?: ownedExecutor!!

    /** True while a frame is being processed on [executor]. A new
     *  [onFrame] arriving during that window is dropped. Rate-limiting
     *  at the sink is already 1 Hz (see [MediaPipeLandmarkStream.
     *  FACE_CROP_INTERVAL_MS]); the drop here guards the pathological
     *  case where the analyzer-thread rate-limit is defeated — e.g. the
     *  analyzer itself was blocked and each arriving frame's timestamp
     *  looks arbitrarily stale. */
    private val inFlight = AtomicBoolean(false)

    /** Counts down the first time an inference finishes on [executor].
     *  [awaitFirstInference] waits on this so [dev.openliveness.CDLSession]
     *  can block briefly for Layer 4 to catch up before reading scores
     *  for the attestation — otherwise a sub-DFT-duration session ships
     *  the 0.85 baseline instead of a real texture score, which is a
     *  spoof-defeating gap (a static photo would ride the baseline
     *  straight through the relay's threshold). */
    private val firstInferenceDone = CountDownLatch(1)

    /** Set true the first time [onFrame] actually submits work to
     *  [executor]. [awaitFirstInference] skips the latch wait when this
     *  is false — there's nothing in flight, so the scores will stay at
     *  the baseline regardless of how long we block. Prevents a session
     *  that never produced a face (user cancelled early, camera failed)
     *  from eating the full timeout for nothing. */
    private val everSubmittedFrame = AtomicBoolean(false)

    /** Live-skin probability from the LBP + classifier pipeline. Stays
     *  at the 0.85 neutral stub while [classifier] is null OR until
     *  the first [analyzeFrame] call lands a real inference. `@Volatile`
     *  because [analyzeFrame] now runs on [executor]'s worker while
     *  [CDLSession] reads this field from its coroutine at attestation
     *  time — we need publication-ordering, not a torn-read-safe
     *  AtomicInteger bit-cast. */
    @Volatile
    var lbpScore = 0.85f
        internal set

    /** 1.0 = no screen artefacts (live); 0.0 = strong artefacts (replay).
     *  Updated by [analyzeFrame]. See [lbpScore] for the `@Volatile`
     *  rationale. */
    @Volatile
    var fourierScore = 0.85f
        internal set

    /**
     * Analyze a single grayscale face crop (0..255 uint8 pixels, row-
     * major) and update [fourierScore] + [lbpScore]. The crop MUST be
     * square; the caller is responsible for resizing to the 80×80 shape
     * the Python reference uses — passing a different size is still
     * numerically defined but the ring geometry shifts proportionally
     * AND the trained LBP model expects an 80² input distribution, so
     * parity with the server breaks either way.
     *
     * This method is pure (modulo the field writes). It is safe to call
     * from the camera analyzer thread **as long as** the owned
     * [TextureClassifier] isn't shared with another thread —
     * `Interpreter.run` is not reentrant.
     */
    fun analyzeFrame(gray: FloatArray, size: Int) {
        require(gray.size == size * size) {
            "gray buffer must be size*size (${size * size}), got ${gray.size}"
        }
        val f = fourierScoreFor(gray, size)
        fourierScore = f
        val lbp = classifier?.let {
            val hist = lbpHistogramFor(gray, size)
            val s = it.score(hist)
            lbpScore = s
            Pair(s, hist)
        }
        if (debugLogEnabled) logInputStats(gray, size, f, lbp)
    }

    /** Toggle for one-line per-inference log that reports gray-crop stats,
     *  LBP histogram's non-zero bin mass, and the two scores. Flip to true
     *  in a debug build (or via a reflective test hook) to diagnose "why
     *  is lbp 0.01?" on-device without rebuilding the TFLite model.
     *  Defaults to true during this triage; flip to false before release. */
    @Volatile
    var debugLogEnabled: Boolean = true

    private fun logInputStats(gray: FloatArray, size: Int, fourier: Float, lbp: Pair<Float, FloatArray>?) {
        var min = Float.POSITIVE_INFINITY; var max = Float.NEGATIVE_INFINITY
        var sum = 0.0; var sumSq = 0.0
        for (v in gray) {
            if (v < min) min = v
            if (v > max) max = v
            sum += v
            sumSq += v * v
        }
        val n = gray.size
        val mean = sum / n
        val variance = (sumSq / n) - (mean * mean)
        val std = kotlin.math.sqrt(kotlin.math.max(0.0, variance))
        val histDesc = lbp?.second?.let { h ->
            // bins 0..9 are the uniform-LBP buckets; 10..58 are structural
            // zeros. Print the populated prefix only.
            h.take(10).joinToString(",") { "%.2f".format(it) }
        } ?: "no-classifier"
        val lbpStr = lbp?.first?.let { "%.3f".format(it) } ?: "none"
        Log.d(LOG_TAG,
            "layer4 analyze: gray size=${size}x${size} min=${"%.1f".format(min)} max=${"%.1f".format(max)} " +
            "mean=${"%.1f".format(mean)} std=${"%.1f".format(std)} " +
            "lbp=$lbpStr fourier=${"%.3f".format(fourier)} hist[0..9]=$histDesc"
        )
    }

    /**
     * [FaceCropSink] entry point — called on the camera analyzer thread.
     * Posts [analyzeFrame] to [executor] and returns immediately so the
     * ~seconds of DFT + LBP work doesn't block MediaPipe frame delivery.
     * Drops the frame when a prior one is still processing; the sink is
     * rate-limited to ~1 Hz upstream anyway, so a dropped frame just
     * means the current session's score reflects a slightly older crop.
     *
     * [FaceCropExtractor.extractGrayFaceCrop] allocates a fresh
     * FloatArray per call, so the handoff does not alias any upstream
     * reusable buffer.
     */
    override fun onFrame(gray: FloatArray, size: Int) {
        if (!inFlight.compareAndSet(false, true)) return
        everSubmittedFrame.set(true)
        try {
            executor.execute {
                try {
                    analyzeFrame(gray, size)
                    // Count down AFTER a successful inference, inside the
                    // try so an analyzeFrame throw doesn't falsely signal
                    // "ready" to [awaitFirstInference]. CountDownLatch is
                    // idempotent on repeated countDown(), so subsequent
                    // inferences are no-ops here.
                    firstInferenceDone.countDown()
                } catch (t: Throwable) {
                    // An uncaught throw here would reach the worker
                    // thread's UncaughtExceptionHandler — which on Android
                    // is KillApplicationHandler, i.e. the entire process
                    // dies. The specific throw we're defending against is
                    // IllegalStateException("Interpreter has already been
                    // closed") when a session teardown closes the
                    // TFLite interpreter while this worker is mid-DFT:
                    // [close] waits [DRAIN_TIMEOUT_MS] for an in-flight
                    // DFT to finish, but a wedged frame (OOM, pathological
                    // input) could exceed that and let the enclosing
                    // factory's classifier.close() race us. Catching
                    // Throwable is deliberate — a dropped frame is
                    // strictly better than a process crash, and the
                    // session's attestation will ship whatever score was
                    // last published.
                    Log.w(LOG_TAG, "layer4 inference threw; dropping frame", t)
                } finally {
                    inFlight.set(false)
                }
            }
        } catch (t: Throwable) {
            // RejectedExecutionException if the executor was already shut
            // down (teardown race between stream close and sink close).
            // Clear the gate so a future onFrame on a reopened sink (test
            // flow) doesn't get stuck. Nothing to log — an executor only
            // rejects after close().
            inFlight.set(false)
        }
    }

    /**
     * Block the calling thread until the first inference finishes, or
     * [timeoutMs] elapses. Returns `true` on a real completion, `false`
     * on timeout (scores still at baseline) OR on an unexpected
     * interrupt. Called from [dev.openliveness.CDLSession] right before
     * the attestation snapshot so the texture scores reflect the user's
     * actual face rather than the 0.85 neutral.
     *
     * When no frame has ever been submitted (user never showed a face,
     * stream closed before MediaPipe produced landmarks), returns `true`
     * immediately — there's nothing to wait for, and blocking the full
     * timeout for a dead session just adds UX latency for no gain.
     */
    fun awaitFirstInference(timeoutMs: Long): Boolean {
        if (!everSubmittedFrame.get()) return true
        return try {
            firstInferenceDone.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            // Preserve interrupt status for the caller's higher-level
            // cancellation (CDLSession's coroutine scope); return false
            // because we don't know whether the inference completed.
            Thread.currentThread().interrupt()
            false
        }
    }

    /**
     * Shut down the owned worker executor. Idempotent. A caller-injected
     * executor is left alone (same policy as [dev.openliveness.camera.
     * CameraCapture]). Blocks long enough to drain a full in-flight
     * LBP+DFT pass so the caller can safely close the shared
     * [TextureClassifier] afterward — a premature return would let the
     * worker call `Interpreter.run` on a freed native handle, which
     * TFLite surfaces as a FATAL IllegalStateException on the worker
     * thread (and Android's default UncaughtExceptionHandler then kills
     * the process).
     *
     * Does NOT call `shutdownNow()`. TFLite's native inference ignores
     * Java thread interrupts; sending one just sets the interrupt
     * status on a thread that's about to finish anyway, with no benefit.
     * The worker catches Throwable around [analyzeFrame] as a last-
     * resort guard in case this timeout is still exceeded.
     */
    override fun close() {
        val e = ownedExecutor ?: return
        e.shutdown()
        try {
            e.awaitTermination(DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        private const val LOG_TAG = "OpenLiveness"

        /** `awaitTermination` budget in [close]. Must comfortably exceed
         *  one full DFT+LBP pass (~3.8s on Snapdragon 8 Gen 2) so the
         *  worker drains before a batteries-included factory
         *  closes the shared [TextureClassifier] on the next line. Set
         *  generously — close() blocks the teardown thread, but a
         *  teardown always runs on a scope that's already been cancelled,
         *  so an extra second of wait is cheaper than a process crash. */
        private const val DRAIN_TIMEOUT_MS: Long = 10_000

        /** Synchronous same-thread executor for tests that want
         *  [onFrame] to behave exactly like [analyzeFrame]. Production
         *  code must not use this — it defeats the whole point of the
         *  worker offload. */
        @JvmField
        val DIRECT_EXECUTOR: java.util.concurrent.Executor =
            java.util.concurrent.Executor { it.run() }

        /**
         * Uniform LBP (P=8, R=1) 59-bin L1-normalized histogram.
         *
         * Mirrors `core/algorithms/layer4_texture.py::lbp_histogram`
         * which calls `skimage.feature.local_binary_pattern` with
         * `method='uniform'`. The output values of that call are integer
         * in [0, 9] (0..P for uniform patterns = popcount of the code,
         * P+1 for non-uniform), so bins 10..58 are structurally zero.
         * We still return a 59-element vector because the trained TFLite
         * Dense layer expects that exact feature size — see Phase 5
         * Step 3 export comments.
         *
         * **Parity notes vs. skimage**:
         *  - Neighbor offsets: `dr = -sin(2π·p/8)`, `dc = cos(2π·p/8)`
         *    for p ∈ 0..7. Cardinal points (p = 0, 2, 4, 6) land on
         *    integer offsets; diagonals use bilinear interpolation.
         *  - Out-of-bounds samples (border pixels) use 0.0, matching
         *    skimage's `_bilinear_interpolation(…, 'C', 0.)`.
         *  - Comparison: neighbor ≥ center → bit 1 (sign-equivalent to
         *    skimage's `signed_texture[p] = texture[p] >= image[r,c]`).
         *  - Transitions ≤ 2 → uniform; the LBP value is the popcount
         *    of the 8-bit code. Otherwise LBP value = P + 1 = 9.
         *
         * The `layer4_lbp.json` golden fixture replays this against the
         * skimage reference on 3 seeded 80×80 images — if any of the
         * three disagrees, the fixture test flips red before TFLite
         * inference drift sneaks into a release.
         */
        @JvmStatic
        fun lbpHistogramFor(gray: FloatArray, size: Int): FloatArray {
            require(gray.size == size * size) {
                "gray buffer must be size*size (${size * size}), got ${gray.size}"
            }
            val hist = FloatArray(TextureClassifier.FEATURE_SIZE)
            var total = 0
            for (r in 0 until size) {
                for (c in 0 until size) {
                    val center = gray[r * size + c]
                    var code = 0
                    for (p in 0 until 8) {
                        val angle = 2.0 * Math.PI * p / 8.0
                        val dr = -sin(angle)
                        val dc = cos(angle)
                        val rr = r + dr
                        val cc = c + dc
                        val sample = bilinearSample(gray, size, rr, cc)
                        if (sample >= center) code = code or (1 shl p)
                    }
                    val lbpValue = uniformLbpValue(code)
                    hist[lbpValue] += 1f
                    total += 1
                }
            }
            if (total > 0) {
                val inv = 1f / total
                for (i in hist.indices) hist[i] *= inv
            }
            return hist
        }

        /** Bilinear sample of `gray[rr, cc]` with out-of-bounds → 0.0.
         *  Matches skimage's `_bilinear_interpolation(…, mode='C',
         *  cval=0.0)`. Snaps to a direct lookup when the offset is
         *  exactly integer (floor == ceil); this is the common case for
         *  p ∈ {0, 2, 4, 6} and avoids an unnecessary 4-way blend. */
        private fun bilinearSample(gray: FloatArray, size: Int, rr: Double, cc: Double): Float {
            val minR = floor(rr).toInt()
            val minC = floor(cc).toInt()
            val maxR = if (rr == minR.toDouble()) minR else minR + 1
            val maxC = if (cc == minC.toDouble()) minC else minC + 1
            val dr = rr - minR
            val dc = cc - minC

            val tl = pixelAt(gray, size, minR, minC)
            val tr = pixelAt(gray, size, minR, maxC)
            val bl = pixelAt(gray, size, maxR, minC)
            val br = pixelAt(gray, size, maxR, maxC)

            val top = tl * (1.0 - dc) + tr * dc
            val bot = bl * (1.0 - dc) + br * dc
            return (top * (1.0 - dr) + bot * dr).toFloat()
        }

        private fun pixelAt(gray: FloatArray, size: Int, r: Int, c: Int): Double {
            if (r < 0 || r >= size || c < 0 || c >= size) return 0.0
            return gray[r * size + c].toDouble()
        }

        /** Rotation-invariant uniform mapping (skimage `method='U'`):
         *  if the circular bit sequence has ≤ 2 transitions, the output
         *  is the popcount of the 8-bit code; otherwise P + 1 (= 9). */
        private fun uniformLbpValue(code: Int): Int {
            // Count transitions in the circular 8-bit sequence.
            var transitions = 0
            var prev = code and 1
            for (i in 1 until 8) {
                val curr = (code shr i) and 1
                if (curr != prev) transitions++
                prev = curr
            }
            // Wrap: bit 7 → bit 0.
            if ((code and 1) != ((code shr 7) and 1)) transitions++
            if (transitions > 2) return 9  // P + 1 = non-uniform bucket
            // Popcount of the 8-bit code.
            return Integer.bitCount(code and 0xFF)
        }

        /**
         * Pure Fourier score computation, exposed for unit tests and
         * for the golden-fixture replay harness. Reads `size × size`
         * grayscale pixels in [0, 255] and returns a score in [0, 1].
         */
        @JvmStatic
        fun fourierScoreFor(gray: FloatArray, size: Int): Float {
            val magnitude = logMagnitudeSpectrum(gray, size)
            val hfEnergy = ringMean(magnitude, size, innerFracDenom = 3, outerFracDenom = 4)
            val lfEnergy = ringMean(magnitude, size, innerFracDenom = 4, outerFracDenom = 8)
            val expected = lfEnergy / 4f
            val artifactRatio = hfEnergy / max(expected, 1e-10f)
            // Same clamp-at-zero shape as the Python ref: a quieter-than-
            // organic ring must not push the score above 1.0.
            return 1f / (1f + max(artifactRatio - 1f, 0f) * 2f)
        }

        /** 2D DFT (O(N⁴) direct form) → `log(|X| + 1)`. 80×80 runs in
         *  ~30ms on a mid-range phone; invoked at most a few times per
         *  session so the simplicity vs. an FFT is worth it. Returns
         *  values indexed as `[row * size + col]`, DC centered at
         *  `(size/2, size/2)`. */
        private fun logMagnitudeSpectrum(gray: FloatArray, size: Int): FloatArray {
            val n = size
            val out = FloatArray(n * n)
            val twoPiOverN = 2.0 * Math.PI / n
            for (u in 0 until n) {
                for (v in 0 until n) {
                    var re = 0.0
                    var im = 0.0
                    for (y in 0 until n) {
                        for (x in 0 until n) {
                            val angle = -twoPiOverN * (u * y + v * x)
                            val px = gray[y * n + x]
                            re += px * cos(angle)
                            im += px * sin(angle)
                        }
                    }
                    val mag = sqrt(re * re + im * im)
                    // Center DC via fftshift index: (u + n/2) % n, (v + n/2) % n.
                    val su = (u + n / 2) % n
                    val sv = (v + n / 2) % n
                    out[su * n + sv] = ln(mag + 1.0).toFloat()
                }
            }
            return out
        }

        /** Mean of `magnitude` over the annulus `size/outer < d < size/inner`
         *  from image center. Matches `_ring_mask` in the Python ref,
         *  including the "keyed off rows (not min(rows, cols))" choice —
         *  the ring geometry only matches on a square crop. */
        private fun ringMean(
            magnitude: FloatArray,
            size: Int,
            innerFracDenom: Int,
            outerFracDenom: Int
        ): Float {
            val cr = size / 2
            val cc = size / 2
            val inner = size.toFloat() / outerFracDenom
            val outer = size.toFloat() / innerFracDenom
            val innerSq = inner * inner
            val outerSq = outer * outer
            var sum = 0.0
            var count = 0
            for (y in 0 until size) {
                val dy = (y - cr).toFloat()
                val dySq = dy * dy
                for (x in 0 until size) {
                    val dx = (x - cc).toFloat()
                    val dSq = dySq + dx * dx
                    if (dSq > innerSq && dSq < outerSq) {
                        sum += magnitude[y * size + x]
                        count++
                    }
                }
            }
            if (count == 0) return 0f
            return (sum / count).toFloat()
        }
    }
}
