package com.uhdcam.app.ui

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.uhdcam.app.R
import com.uhdcam.app.ai.SuperResolutionProcessor
import com.uhdcam.app.camera.CameraController
import com.uhdcam.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraController: CameraController
    private lateinit var srProcessor: SuperResolutionProcessor

    private var aiEnhanceEnabled = true
    private var isProcessing = false
    private var permissionsGranted = false

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                permissionsGranted = true
                startCamera()
            } else {
                Toast.makeText(this, R.string.permission_camera, Toast.LENGTH_LONG).show()
                finish()
            }
        }

    private val storagePermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
            Log.d(TAG, "Storage permissions granted")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        srProcessor = SuperResolutionProcessor(this)
        cameraController = CameraController(this, binding.previewView)

        setupUI()
        checkPermissions()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraController.shutdown()
    }

    private fun setupUI() {
        binding.captureButton.setOnClickListener { capturePhoto() }
        binding.aiToggleButton.setOnClickListener { toggleAI() }
        binding.galleryButton.setOnClickListener {
            GalleryActivity.open(this)
        }

        binding.processingOverlay.visibility = View.GONE
        binding.processingIndicator.visibility = View.GONE

        updateAIButtonState()

        binding.zoomSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val zoom = 1f + (progress / 100f) * 9f
                cameraController.setZoom(zoom)
                binding.zoomLabel.text = getString(R.string.zoom, String.format("%.1f", zoom))
                binding.zoomLabel.visibility = if (progress > 0) View.VISIBLE else View.GONE
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            permissionsGranted = true
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }

        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                != PackageManager.PERMISSION_GRANTED
            ) {
                storagePermission.launch(arrayOf(Manifest.permission.READ_MEDIA_IMAGES))
            }
        } else if (Build.VERSION.SDK_INT <= 28) {
            storagePermission.launch(arrayOf(
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ))
        }
    }

    private fun startCamera() {
        cameraController.startCamera(this)
        cameraController.onZoomChanged = { zoom ->
            val progress = ((zoom - 1f) / 9f * 100f).toInt().coerceIn(0, 100)
            binding.zoomSlider.progress = progress
            binding.zoomLabel.text = getString(R.string.zoom, String.format("%.1f", zoom))
            binding.zoomLabel.visibility = if (progress > 0) View.VISIBLE else View.GONE
        }
    }

    private fun toggleAI() {
        aiEnhanceEnabled = !aiEnhanceEnabled
        updateAIButtonState()
        binding.aiStatusCard.visibility = if (aiEnhanceEnabled) View.VISIBLE else View.GONE
        binding.aiStatusText.text = if (aiEnhanceEnabled)
            getString(R.string.enhance_mode_ai) else getString(R.string.enhance_mode_off)
    }

    private fun updateAIButtonState() {
        binding.aiToggleButton.setImageResource(
            if (aiEnhanceEnabled) R.drawable.ic_ai_active else R.drawable.ic_ai
        )
        binding.aiToggleButton.imageTintList = ContextCompat.getColorStateList(
            this, if (aiEnhanceEnabled) R.color.accent else android.R.color.white
        )
    }

    private fun capturePhoto() {
        if (isProcessing || !permissionsGranted) return
        isProcessing = true
        showProcessing(true)

        cameraController.takePicture(
            onSaved = { file ->
                lifecycleScope.launch {
                    if (!isActive) return@launch
                    if (aiEnhanceEnabled) {
                        enhanceAndSave(file)
                    } else {
                        saveRawToGallery(file)
                        onEnhanceComplete(file.name)
                    }
                }
            },
            onError = { message ->
                onMainThread {
                    resetAfterError("Capture failed: $message")
                }
            }
        )
    }

    private suspend fun enhanceAndSave(file: File) {
        try {
            val resultPath = withContext(Dispatchers.IO) {
                if (!isActive) return@withContext null

                val bitmap = try {
                    val opts = BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }
                    BitmapFactory.decodeFile(file.absolutePath, opts)
                    val sampleSize = estimateSampleSize(opts.outWidth, opts.outHeight)
                    BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                        inMutable = false
                    }.let { options ->
                        FileInputStream(file).use { stream ->
                            BitmapFactory.decodeStream(stream, null, options)
                        }
                    }
                } catch (e: OutOfMemoryError) {
                    Log.w(TAG, "OOM decoding, trying with higher sample", e)
                    BitmapFactory.Options().apply {
                        inSampleSize = estimateSampleSize(file) * 2
                    }.let { options ->
                        FileInputStream(file).use { stream ->
                            BitmapFactory.decodeStream(stream, null, options)
                        }
                    }
                } ?: return@withContext null

                if (!isActive) { bitmap.recycle(); return@withContext null }

                val enhanced = srProcessor.enhance(bitmap)
                if (enhanced !== bitmap) bitmap.recycle()

                val path = saveEnhancedPhoto(enhanced)
                enhanced.recycle()
                path
            }
            if (resultPath != null) {
                onMainThread { onEnhanceComplete(resultPath) }
            } else {
                onMainThread { resetAfterError("Processing failed") }
            }
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM during enhancement", e)
            System.gc()
            onMainThread { resetAfterError("Image too large, try again") }
        } catch (e: Exception) {
            Log.e(TAG, "Enhancement failed", e)
            onMainThread { resetAfterError("Enhancement failed: ${e.message}") }
        }
    }

    private fun estimateSampleSize(w: Int, h: Int): Int {
        if (w <= 0 || h <= 0) return 1
        val mp = (w.toLong() * h) / 1_000_000
        return when {
            mp > 24 -> 4
            mp > 12 -> 2
            else -> 1
        }
    }

    private fun estimateSampleSize(file: File): Int {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            estimateSampleSize(opts.outWidth, opts.outHeight)
        } catch (_: Exception) { 1 }
    }

    private fun saveEnhancedPhoto(bitmap: Bitmap): String? {
        val filename = "UHD_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
        return if (Build.VERSION.SDK_INT >= 29) {
            saveViaMediaStore(bitmap, filename)
        } else {
            saveViaFile(bitmap, filename)
        }
    }

    private fun saveViaMediaStore(bitmap: Bitmap, filename: String): String? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/UHDCamera")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null
        try {
            contentResolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 98, out)
                    .also { if (!it) Log.w(TAG, "Compress failed") }
            } ?: Log.w(TAG, "Failed to open output stream")
        } finally {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        }
        return filename
    }

    private fun saveViaFile(bitmap: Bitmap, filename: String): String? {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "UHDCamera"
        )
        if (!dir.exists() && !dir.mkdirs()) return null
        val file = File(dir, filename)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 98, out)
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATA, file.absolutePath)
        }
        contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        return file.absolutePath
    }

    private fun saveRawToGallery(file: File) {
        try {
            val filename = "RAW_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/UHDCamera")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { out ->
                    FileInputStream(file).use { source -> source.copyTo(out) }
                }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save raw capture", e)
        }
    }

    private fun onEnhanceComplete(path: String) {
        if (isFinishing) return
        isProcessing = false
        showProcessing(false)
        Snackbar.make(binding.root, R.string.save_success, Snackbar.LENGTH_SHORT).show()
        cameraController.reinitializeCapture()
    }

    private fun resetAfterError(message: String) {
        if (isFinishing) return
        isProcessing = false
        showProcessing(false)
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    private fun showProcessing(show: Boolean) {
        binding.processingOverlay.visibility = if (show) View.VISIBLE else View.GONE
        binding.processingIndicator.visibility = if (show) View.VISIBLE else View.GONE
        binding.bottomControls.alpha = if (show) 0.3f else 1f
        binding.captureButton.isEnabled = !show
        binding.zoomSlider.isEnabled = !show
    }

    private fun onMainThread(action: () -> Unit) {
        if (isFinishing) return
        runOnUiThread {
            if (!isFinishing) action()
        }
    }
}
