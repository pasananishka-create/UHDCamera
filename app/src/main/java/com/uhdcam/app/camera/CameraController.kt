package com.uhdcam.app.camera

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    var onZoomChanged: ((Float) -> Unit)? = null

    fun startCamera(lifecycleOwner: LifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCamera(lifecycleOwner)
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindCamera(lifecycleOwner: LifecycleOwner) {
        val provider = cameraProvider ?: return
        provider.unbindAll()

        val preview = Preview.Builder()
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setTargetRotation(previewView.display?.rotation ?: android.view.Surface.ROTATION_0)
            .setFlashMode(ImageCapture.FLASH_MODE_AUTO)
            .build()

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            camera = provider.bindToLifecycle(
                lifecycleOwner, cameraSelector, preview, imageCapture
            )
            camera?.cameraInfo?.zoomState?.observe(lifecycleOwner) { state ->
                onZoomChanged?.invoke(state.zoomRatio)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun setZoom(ratio: Float) {
        camera?.cameraInfo?.zoomState?.value?.let { state ->
            val clamped = ratio.coerceIn(state.minZoomRatio, state.maxZoomRatio)
            camera?.cameraControl?.setZoomRatio(clamped)
        }
    }

    fun takePicture(onSaved: ImageCapture.OnImageSavedCallback) {
        val imageCapture = imageCapture ?: return

        val photoDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "UHDCamera"
        )
        photoDir.mkdirs()

        val filename = "RAW_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
        val file = File(photoDir, filename)

        val outputOptions = ImageCapture.OutputFileOptions.Builder(file)
            .setMetadata(ImageCapture.Metadata().apply {
                isReversedHorizontal = false
                isReversedVertical = false
            })
            .build()

        imageCapture.takePicture(outputOptions, cameraExecutor, onSaved)
    }

    fun reinitializeCapture() {
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setTargetRotation(previewView.display?.rotation ?: android.view.Surface.ROTATION_0)
            .setFlashMode(ImageCapture.FLASH_MODE_AUTO)
            .build()
    }

    fun shutdown() {
        cameraExecutor.shutdown()
    }
}
