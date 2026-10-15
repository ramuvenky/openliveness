package dev.openliveness.camera

import android.annotation.SuppressLint
import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import dev.openliveness.QRPayload
import dev.openliveness.parseQRPayload
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * CameraX + ML Kit QR scanner. Resolves with the first well-formed CDL
 * QRPayload it sees, then unbinds the camera so the host can switch into
 * the front-camera liveness capture.
 */
class QRScanner(private val context: Context) {

    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
    )
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    suspend fun start(lifecycleOwner: LifecycleOwner, previewView: PreviewView): QRPayload {
        val provider = awaitProvider()
        provider.unbindAll()

        return suspendCoroutine { cont ->
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            var delivered = false
            analysis.setAnalyzer(analysisExecutor) analyzer@{ imageProxy ->
                if (delivered) { imageProxy.close(); return@analyzer }
                @SuppressLint("UnsafeOptInUsageError")
                val mediaImage = imageProxy.image
                if (mediaImage == null) { imageProxy.close(); return@analyzer }
                val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                scanner.process(input)
                    .addOnSuccessListener { barcodes ->
                        if (delivered) return@addOnSuccessListener
                        for (b in barcodes) {
                            val raw = b.rawValue ?: continue
                            val payload = runCatching { parseQRPayload(raw) }.getOrNull() ?: continue
                            delivered = true
                            provider.unbindAll()
                            cont.resume(payload)
                            return@addOnSuccessListener
                        }
                    }
                    .addOnFailureListener { e ->
                        if (!delivered) {
                            delivered = true
                            cont.resumeWithException(e)
                        }
                    }
                    .addOnCompleteListener { imageProxy.close() }
            }

            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis
            )
        }
    }

    private suspend fun awaitProvider(): ProcessCameraProvider = suspendCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try { cont.resume(future.get()) }
            catch (e: Throwable) { cont.resumeWithException(e) }
        }, ContextCompat.getMainExecutor(context))
    }
}
