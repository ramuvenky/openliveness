package dev.openliveness.camera

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * CameraX front-camera capture + preview binding. Week 1 enforces the
 * front camera (per spec: front_camera_confirmed is always true) and
 * exposes the configured session so Phase 3 can attach the MediaPipe
 * analyzer.
 */
class CameraCapture(private val context: Context) {

    var frontCameraConfirmed: Boolean = false
        private set

    suspend fun bind(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        val provider = awaitProvider()
        provider.unbindAll()

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        provider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_FRONT_CAMERA,
            preview
        )
        frontCameraConfirmed = true
    }

    private suspend fun awaitProvider(): ProcessCameraProvider = suspendCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try { cont.resume(future.get()) }
            catch (e: Throwable) { cont.resumeWithException(e) }
        }, ContextCompat.getMainExecutor(context))
    }
}
