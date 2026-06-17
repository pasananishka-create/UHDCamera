package com.uhdcam.app.camera

import android.content.Context
import android.util.Log
import android.view.Surface
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraController(
    private val context: Context,
    private val previewView: PreviewView
) {
    companion object {
        private const val TAG = "CameraController"
    }

    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var lifecycleOwner: LifecycleOwner? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    var onZoomChanged: ((Float) -> Unit)? = null

    fun startCamera(lifecycleOwner: LifecycleOwner) {
        this.lifecycleOwner = lifecycleOwner
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCamera()
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return
        val owner = lifecycleOwner ?: return
        provider.unbindAll()

        val preview = Preview.Builder()
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setTargetRotation(previewView.display?.rotation ?: Surface.ROTATION_0)
            .setFlashMode(ImageCapture.FLASH_MODE_AUTO)
            .build()

        try {
            camera = provider.bindToLifecycle(
                owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture
            )
            camera?.cameraInfo?.zoomState?.observe(owner) { state ->
                state?.let { onZoomChanged?.invoke(it.zoomRatio) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind camera", e)
        }
    }

    fun setZoom(ratio: Float) {
        try {
            camera?.cameraInfo?.zoomState?.value?.let { state ->
                val clamped = ratio.coerceIn(state.minZoomRatio, state.maxZoomRatio)
                camera?.cameraControl?.setZoomRatio(clamped)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Zoom failed", e)
        }
    }

    fun takePicture(onSaved: (File) -> Unit, onError: (String) -> Unit) {
        val capture = imageCapture
        if (capture == null) {
            onError("Camera not initialized")
            return
        }

        try {
            val extDir = context.getExternalFilesDir(null)
            if (extDir == null) {
                onError("External storage not available")
                return
            }
            val photoDir = File(extDir, "captures")
            if (!photoDir.exists() && !photoDir.mkdirs()) {
                onError("Cannot create capture directory")
                return
            }

            val filename = "RAW_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
            val file = File(photoDir, filename)

            val metadata = ImageCapture.Metadata().apply {
                isReversedHorizontal = false
                isReversedVertical = false
            }

            val outputOptions = ImageCapture.OutputFileOptions.Builder(file)
                .setMetadata(metadata)
                .build()

            capture.takePicture(outputOptions, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    onSaved(file)
                }
                override fun onError(exception: ImageCaptureException) {
                    onError(exception.message ?: "Unknown error")
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Take picture failed", e)
            onError(e.message ?: "Unknown error")
        }
    }

    fun reinitializeCapture() {
        bindCamera()
    }

    fun shutdown() {
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.w(TAG, "Shutdown unbind error", e)
        }
        cameraExecutor.shutdown()
    }
}
