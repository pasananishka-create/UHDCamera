package com.uhdcam.app.ui

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraController: CameraController
    private lateinit var srProcessor: SuperResolutionProcessor

    private var aiEnhanceEnabled = true
    private var isProcessing = false

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else {
                Toast.makeText(this, R.string.permission_camera, Toast.LENGTH_LONG).show()
                finish()
            }
        }

    private val storagePermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        srProcessor = SuperResolutionProcessor(this)
        cameraController = CameraController(this, binding.previewView)

        setupUI()
        checkPermissions()
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
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED -> startCamera()
            else -> cameraPermission.launch(Manifest.permission.CAMERA)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                != PackageManager.PERMISSION_GRANTED
            ) storagePermission.launch(arrayOf(Manifest.permission.READ_MEDIA_IMAGES))
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
        if (isProcessing) return
        isProcessing = true
        showProcessing(true)

        cameraController.takePicture(object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                val savedUri = output.savedUri ?: return
                if (aiEnhanceEnabled) {
                    lifecycleScope.launch {
                        enhanceAndSave(savedUri)
                    }
                } else {
                    onEnhanceComplete(savedUri.toString())
                }
            }
            override fun onError(exception: ImageCaptureException) {
                runOnUiThread {
                    isProcessing = false
                    showProcessing(false)
                    Snackbar.make(binding.root, "Capture failed: ${exception.message}", Snackbar.LENGTH_LONG).show()
                }
            }
        })
    }

    private suspend fun enhanceAndSave(uri: Uri) {
        withContext(Dispatchers.IO) {
            try {
                val inputStream = contentResolver.openInputStream(uri) ?: return@withContext
                val originalBitmap = BitmapFactory.decodeStream(inputStream)
                inputStream.close()

                val enhanced = srProcessor.enhance(originalBitmap)
                val resultUri = saveEnhancedPhoto(enhanced)
                withContext(Dispatchers.Main) {
                    onEnhanceComplete(resultUri)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isProcessing = false
                    showProcessing(false)
                    Snackbar.make(binding.root, "Enhancement failed: ${e.message}", Snackbar.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun saveEnhancedPhoto(bitmap: Bitmap): String {
        val filename = "UHD_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
        val file = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "UHDCamera/$filename"
        )
        file.parentFile?.mkdirs()
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

    private fun onEnhanceComplete(path: String) {
        isProcessing = false
        showProcessing(false)
        Snackbar.make(binding.root, R.string.save_success, Snackbar.LENGTH_SHORT).show()
        cameraController.reinitializeCapture()
    }

    private fun showProcessing(show: Boolean) {
        binding.processingOverlay.visibility = if (show) View.VISIBLE else View.GONE
        binding.processingIndicator.visibility = if (show) View.VISIBLE else View.GONE
        binding.bottomControls.alpha = if (show) 0.3f else 1f
        binding.captureButton.isEnabled = !show
        binding.zoomSlider.isEnabled = !show
    }
}
