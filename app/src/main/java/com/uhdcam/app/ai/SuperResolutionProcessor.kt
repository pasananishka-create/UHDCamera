package com.uhdcam.app.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import androidx.annotation.WorkerThread
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class SuperResolutionProcessor(private val context: Context) {

    private var tfliteModel: InterpreterWrapper? = null
    private val imageEnhancer = ImageEnhancer()

    private val modelScaleFactor: Int = 2

    companion object {
        private const val TAG = "SuperResolution"
        private const val MODEL_FILENAME = "sr_model_2x.tflite"
        private const val MAX_INPUT_SIZE = 1024
        private const val ENHANCED_TARGET_MP = 12 
        private const val BLOCK_SIZE = 256 
    }

    @WorkerThread
    fun enhance(bitmap: Bitmap): Bitmap {
        val startTime = System.nanoTime()
        Log.d(TAG, "Enhancing bitmap: ${bitmap.width}x${bitmap.height}")
        loadModelIfNeeded()

        var enhanced: Bitmap

        if (tfliteModel != null && tfliteModel!!.isValid) {
            enhanced = runTFLiteSuperResolution(bitmap)
        } else {
            enhanced = runClassicalUpscale(bitmap)
        }

        enhanced = imageEnhancer.enhanceDetails(enhanced)

        val elapsed = (System.nanoTime() - startTime) / 1_000_000
        Log.d(TAG, "Enhancement completed in ${elapsed}ms, result: ${enhanced.width}x${enhanced.height}")
        return enhanced
    }

    @WorkerThread
    fun enhanceWithBurst(frames: List<Bitmap>): Bitmap {
        if (frames.isEmpty()) throw IllegalArgumentException("No frames")
        if (frames.size == 1) return enhance(frames[0])

        val merged = mergeFrames(frames)
        return enhance(merged)
    }

    private fun mergeFrames(frames: List<Bitmap>): Bitmap {
        val w = frames[0].width
        val h = frames[0].height
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint().apply { alpha = (255 / frames.size).coerceAtMost(255) }

        for (frame in frames) {
            canvas.drawBitmap(frame, 0f, 0f, paint)
        }
        return result
    }

    private fun loadModelIfNeeded() {
        if (tfliteModel != null) return

        try {
            val modelFile = File(context.getExternalFilesDir("models"), MODEL_FILENAME)
            if (modelFile.exists()) {
                val byteBuffer = loadModelFile(modelFile)
                tfliteModel = InterpreterWrapper(byteBuffer)
                Log.d(TAG, "TFLite model loaded from ${modelFile.absolutePath}")
            } else {
                val assetStream = context.assets.open(MODEL_FILENAME)
                val bytes = assetStream.readBytes()
                assetStream.close()
                val buffer = ByteBuffer.allocateDirect(bytes.size).apply {
                    put(bytes)
                    rewind()
                }
                tfliteModel = InterpreterWrapper(buffer)
                Log.d(TAG, "TFLite model loaded from assets")
            }

            val inputSize = tfliteModel?.inputSize ?: 0
            if (inputSize > 0) {
                Log.d(TAG, "Model input size: $inputSize")
            }
        } catch (e: Exception) {
            Log.w(TAG, "No TFLite model found, using classical pipeline: ${e.message}")
            tfliteModel = InterpreterWrapper(null)
        }
    }

    private fun loadModelFile(file: File): ByteBuffer {
        val inputStream = FileInputStream(file)
        val channel = inputStream.channel
        val buffer = channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, file.length())
        channel.close()
        inputStream.close()
        return buffer
    }

    private fun runTFLiteSuperResolution(bitmap: Bitmap): Bitmap {
        val model = tfliteModel ?: return runClassicalUpscale(bitmap)
        val scale = modelScaleFactor

        val w = bitmap.width
        val h = bitmap.height

        if (w <= BLOCK_SIZE && h <= BLOCK_SIZE) {
            return runSingleBlock(bitmap, model, scale)
        }

        return runBlocked(bitmap, model, scale)
    }

    private fun runBlocked(bitmap: Bitmap, model: InterpreterWrapper, scale: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val sw = BLOCK_SIZE
        val sh = BLOCK_SIZE
        val cols = (w + sw - 1) / sw
        val rows = (h + sh - 1) / sh

        val outW = w * scale
        val outH = h * scale
        val result = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val sx = col * sw
                val sy = row * sh
                val ew = minOf(sw, w - sx)
                val eh = minOf(sh, h - sy)

                val block = Bitmap.createBitmap(bitmap, sx, sy, ew, eh)
                val enhanced = runSingleBlock(block, model, scale)
                canvas.drawBitmap(enhanced, (sx * scale).toFloat(), (sy * scale).toFloat(), null)
                enhanced.recycle()
                block.recycle()
            }
        }

        return result
    }

    private fun runSingleBlock(block: Bitmap, model: InterpreterWrapper, scale: Int): Bitmap {
        val w = block.width
        val h = block.height
        val inputSize = model.inputSize

        val resizedW: Int
        val resizedH: Int
        val needsResize: Boolean

        if (w > inputSize || h > inputSize) {
            val ratio = minOf(inputSize.toFloat() / w, inputSize.toFloat() / h)
            resizedW = (w * ratio).toInt()
            resizedH = (h * ratio).toInt()
            needsResize = true
        } else if (w < inputSize && h < inputSize && (w % 4 != 0 || h % 4 != 0)) {
            resizedW = (w + 3) / 4 * 4
            resizedH = (h + 3) / 4 * 4
            needsResize = true
        } else {
            resizedW = w
            resizedH = h
            needsResize = false
        }

        val inputBitmap = if (needsResize) {
            Bitmap.createScaledBitmap(block, resizedW, resizedH, true)
        } else block

        val inputBuffer = bitmapToFloatBuffer(inputBitmap)

        val outputW = resizedW * scale
        val outputH = resizedH * scale
        val outputBuffer = FloatBuffer.allocate(1 * outputW * outputH * 3)

        model.run(inputBuffer, outputBuffer)

        val outputBitmap = floatBufferToBitmap(outputBuffer, outputW, outputH)

        if (needsResize) {
            inputBitmap.recycle()
        }

        return if (needsResize && (w * scale != outputW || h * scale != outputH)) {
            Bitmap.createScaledBitmap(outputBitmap, w * scale, h * scale, true).also {
                outputBitmap.recycle()
            }
        } else outputBitmap
    }

    private fun runClassicalUpscale(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val currentMp = (w * h) / 1_000_000f

        val targetMp = ENHANCED_TARGET_MP.coerceAtLeast((currentMp * 2).toInt())
        val scale = kotlin.math.sqrt(targetMp / currentMp.toDouble()).coerceAtMost(4.0)
        val newW = (w * scale).toInt()
        val newH = (h * scale).toInt()

        val scaled = Bitmap.createScaledBitmap(bitmap, newW, newH, true)

        return imageEnhancer.adaptiveSharpen(scaled)
    }

    private fun bitmapToFloatBuffer(bitmap: Bitmap): FloatBuffer {
        val w = bitmap.width
        val h = bitmap.height
        val buffer = FloatBuffer.allocate(1 * w * h * 3)
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val pixel = pixels[y * w + x]
                val r = ((pixel shr 16) and 0xFF) / 255f
                val g = ((pixel shr 8) and 0xFF) / 255f
                val b = (pixel and 0xFF) / 255f
                buffer.put(r)
                buffer.put(g)
                buffer.put(b)
            }
        }
        buffer.rewind()
        return buffer
    }

    private fun floatBufferToBitmap(buffer: FloatBuffer, w: Int, h: Int): Bitmap {
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)

        buffer.rewind()
        for (y in 0 until h) {
            for (x in 0 until w) {
                val r = (buffer.get() * 255f).toInt().coerceIn(0, 255)
                val g = (buffer.get() * 255f).toInt().coerceIn(0, 255)
                val b = (buffer.get() * 255f).toInt().coerceIn(0, 255)
                pixels[y * w + x] = Color.rgb(r, g, b)
            }
        }

        result.setPixels(pixels, 0, w, 0, 0, w, h)
        return result
    }

    private class InterpreterWrapper(private val modelBuffer: ByteBuffer?) {
        private var interpreter: org.tensorflow.lite.Interpreter? = null
        val inputSize: Int
        val isValid: Boolean

        init {
            var size = 0
            var valid = false
            if (modelBuffer != null) {
                try {
                    interpreter = org.tensorflow.lite.Interpreter(modelBuffer)
                    val inputShape = interpreter?.getInputTensor(0)?.shape()
                    valid = inputShape != null && inputShape.size >= 2
                    size = if (inputShape != null && inputShape.size >= 2) {
                        inputShape[1].coerceAtMost(inputShape[2])
                    } else 0
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to create interpreter: ${e.message}")
                }
            }
            isValid = valid
            inputSize = size
        }

        fun run(input: FloatBuffer, output: FloatBuffer) {
            interpreter?.run(input, output)
        }
    }
}
