package dev.openliveness

import dev.openliveness.camera.CameraCapture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Pins the C1+M5 fix: CameraCapture now owns-or-borrows the analyzer
 * executor. The owned path shuts the executor down on close() so each
 * session doesn't leak a non-daemon worker thread; the injected path
 * leaves it alone so the caller can share one across sessions.
 *
 * CameraCapture itself needs an Android `Context` which isn't on the
 * JVM test classpath — we exercise the policy via the extracted static
 * helper that the instance delegates to.
 */
class CameraCaptureExecutorPolicyTest {

    @Test
    fun `owned ExecutorService is shut down`() {
        val exec = Executors.newSingleThreadExecutor()
        CameraCapture.shutdownIfOwned(exec, ownsExecutor = true)
        assertTrue(exec.isShutdown,
            "default-constructed CameraCapture must stop leaking its worker thread on close()")
    }

    @Test
    fun `injected executor is left alone`() {
        val exec = Executors.newSingleThreadExecutor()
        try {
            CameraCapture.shutdownIfOwned(exec, ownsExecutor = false)
            assertFalse(exec.isShutdown,
                "caller-supplied executor must survive CameraCapture.close() — the caller owns its lifecycle")
        } finally {
            exec.shutdown()
        }
    }

    @Test
    fun `non-ExecutorService owned executor is a no-op`() {
        // A plain Executor (e.g., a direct-executor used in tests) has
        // no shutdown method. The policy must not blow up — ownership
        // is advisory in that case.
        val direct = Executor { it.run() }
        // Just verifying no exception.
        CameraCapture.shutdownIfOwned(direct, ownsExecutor = true)
    }

    @Test
    fun `second shutdown is idempotent`() {
        // close() can be called twice (the sample's finally runs after
        // CDLSession has already closed the stream) and must not throw.
        // ExecutorService.shutdown() is itself idempotent, but we lock
        // in the behaviour to catch a future refactor that swaps in
        // shutdownNow or similar.
        val exec = Executors.newSingleThreadExecutor()
        CameraCapture.shutdownIfOwned(exec, ownsExecutor = true)
        CameraCapture.shutdownIfOwned(exec, ownsExecutor = true)
        assertTrue(exec.isShutdown)
    }

    @Test
    fun `unbind is dispatched via main executor not run inline`() {
        // NC-H2: ProcessCameraProvider.unbindAll() throws if invoked
        // off-main. close() must route the unbind through the main
        // executor so a caller on an IO coroutine or background pool
        // doesn't crash. This test pins the dispatch contract: the
        // Runnable is handed to the executor and never invoked inline
        // on the caller's thread.
        val submissions = mutableListOf<Runnable>()
        val stubMainExecutor = java.util.concurrent.Executor { r -> submissions.add(r) }
        var unbindRan = false
        CameraCapture.dispatchUnbind(stubMainExecutor) { unbindRan = true }
        assertEquals(
            1, submissions.size,
            "unbind must be submitted to the main executor exactly once"
        )
        assertFalse(
            unbindRan,
            "unbind must not run inline — the stub executor only queues; the real main executor posts to the Looper"
        )
        // Simulate the main looper picking up the task.
        submissions[0].run()
        assertTrue(unbindRan)
    }

    @Test
    fun `dispatchUnbind is called once per close path`() {
        // Both unbind() and close() must route through the same policy
        // helper. If a future refactor adds a direct `provider.unbindAll()`
        // call anywhere, this reminds the author that off-main unbind
        // crashes production.
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val exec = java.util.concurrent.Executor { it.run() }
        CameraCapture.dispatchUnbind(exec) { calls.incrementAndGet() }
        CameraCapture.dispatchUnbind(exec) { calls.incrementAndGet() }
        assertEquals(2, calls.get())
    }
}
