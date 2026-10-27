package dev.openliveness.camera

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * CameraX front-camera capture + preview binding. Enforces the front
 * camera (per spec: `front_camera_confirmed` is always true) and exposes
 * the configured session so the orchestrator can attach the MediaPipe
 * analyzer.
 *
 * **Lifecycle:** [AutoCloseable]. Call [close] when the session is
 * finished — it unbinds the CameraX use cases (preview + analysis) and
 * shuts down the analyzer executor when owned by this instance. Not
 * calling [close] leaks a non-daemon worker thread per instance and
 * leaves the front-camera LED on until the next bind replaces the
 * provider's state.
 */
class CameraCapture private constructor(
    private val context: Context,
    private val analyzerExecutor: Executor,
    private val ownsExecutor: Boolean,
) : AutoCloseable {

    /** Default: own the analyzer executor and shut it down on [close]. */
    constructor(context: Context) : this(
        context = context,
        analyzerExecutor = Executors.newSingleThreadExecutor(),
        ownsExecutor = true,
    )

    /**
     * Inject an executor the caller manages (shared across sessions,
     * test harness, battery-tuned pool, etc.). The caller is responsible
     * for shutting it down; [close] here only unbinds the camera.
     */
    constructor(context: Context, analyzerExecutor: Executor) : this(
        context = context,
        analyzerExecutor = analyzerExecutor,
        ownsExecutor = false,
    )

    var frontCameraConfirmed: Boolean = false
        private set

    private var provider: ProcessCameraProvider? = null

    /**
     * Bind preview + optional ImageAnalysis to the lifecycle. When
     * [analyzer] is non-null, the analysis use case is configured with
     * STRATEGY_KEEP_ONLY_LATEST so a slow MediaPipe pass drops stale
     * frames at the camera boundary rather than backlogging into the
     * stream's channel. Pass `MediaPipeLandmarkStream` here directly —
     * it implements `ImageAnalysis.Analyzer`.
     *
     * The analyzer runs on the executor passed to the constructor (a
     * single-thread executor by default) so frames arrive in capture
     * order — MediaPipe LIVE_STREAM rejects out-of-order timestamps.
     */
    suspend fun bind(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        analyzer: ImageAnalysis.Analyzer? = null,
    ) {
        val p = awaitProvider()
        provider = p
        p.unbindAll()

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val useCases = mutableListOf<androidx.camera.core.UseCase>(preview)
        if (analyzer != null) {
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(analyzerExecutor, analyzer) }
            useCases.add(analysis)
        }
        p.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_FRONT_CAMERA,
            *useCases.toTypedArray()
        )
        frontCameraConfirmed = true
    }

    /**
     * Unbind all CameraX use cases from the current lifecycle. Idempotent.
     *
     * [androidx.camera.lifecycle.ProcessCameraProvider.unbindAll] must run
     * on the main thread — calling it off-main throws
     * `java.lang.IllegalStateException: Not in application's main thread`.
     * This method is safe to call from any thread: the unbind is routed
     * through [androidx.core.content.ContextCompat.getMainExecutor] so a
     * caller on an IO coroutine or background pool doesn't crash. The
     * unbind is therefore asynchronous — callers that need to observe
     * completion should drive it from the main dispatcher directly.
     */
    fun unbind() {
        val p = provider ?: return
        dispatchUnbind(ContextCompat.getMainExecutor(context)) { p.unbindAll() }
    }

    /**
     * Unbind + shut down the owned executor. Idempotent and safe to call
     * from any thread (unbind is routed to the main executor; see
     * [unbind]). After [close] the instance should not be reused; a
     * subsequent [bind] on an owned-executor instance would submit to a
     * shutdown executor and fail with RejectedExecutionException.
     * Construct a fresh instance for a new session.
     */
    override fun close() {
        val p = provider
        provider = null
        if (p != null) {
            dispatchUnbind(ContextCompat.getMainExecutor(context)) { p.unbindAll() }
        }
        shutdownIfOwned(analyzerExecutor, ownsExecutor)
    }

    private suspend fun awaitProvider(): ProcessCameraProvider = suspendCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try { cont.resume(future.get()) }
            catch (e: Throwable) { cont.resumeWithException(e) }
        }, ContextCompat.getMainExecutor(context))
    }

    companion object {
        /**
         * Shutdown policy, extracted so a JVM unit test can drive both
         * branches without an Android `Context`. Owned ExecutorServices
         * are shut down; non-owned executors (injected by the caller)
         * and non-ExecutorService executors (e.g., direct executors in
         * tests) are left alone.
         */
        @JvmStatic
        internal fun shutdownIfOwned(executor: Executor, ownsExecutor: Boolean) {
            if (ownsExecutor && executor is ExecutorService) {
                executor.shutdown()
            }
        }

        /**
         * Dispatch policy for the camera unbind, extracted so a JVM
         * unit test can verify we never run the Runnable inline on the
         * caller's thread. `ProcessCameraProvider.unbindAll()` requires
         * the main thread and throws otherwise; routing through the
         * main executor is how we make [close] safe to call from an IO
         * coroutine or a background pool. See NC-H2 in the review.
         */
        @JvmStatic
        internal fun dispatchUnbind(mainExecutor: Executor, unbindAction: Runnable) {
            mainExecutor.execute(unbindAction)
        }
    }
}
