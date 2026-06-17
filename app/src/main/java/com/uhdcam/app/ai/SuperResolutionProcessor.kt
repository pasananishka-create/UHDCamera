package com.uhdcam.app.ai

import android.content.Context
import android.graphics.*
import android.util.Log
import androidx.annotation.WorkerThread
import java.io.File
import kotlin.math.*

class SuperResolutionProcessor(private val context: Context) {

    private var tfliteModel: InterpreterWrapper? = null
    private val imageEnhancer = ImageEnhancer()

    companion object {
        private const val TAG = "SuperResolution"
        private const val MODEL_FILENAME = "sr_model_2x.tflite"
        private const val ENHANCED_TARGET_MP = 12
        private const val BLOCK_SIZE = 192
        private const val MAX_MEMORY_USAGE = 0.65
        private const val STEP_SCALE = 2.0
        private const val MIN_DIM = 4
    }

    @WorkerThread
    fun enhance(bitmap: Bitmap): Bitmap {
        if (bitmap.isRecycled || bitmap.width < MIN_DIM || bitmap.height < MIN_DIM) return bitmap

        val startTime = System.nanoTime()
        Log.d(TAG, "Enhancing: ${bitmap.width}x${bitmap.height}")
        loadModelIfNeeded()

        val enhanced: Bitmap = try {
            if (tfliteModel != null && tfliteModel!!.isValid) {
                runTFLiteSuperResolution(bitmap)
            } else {
                runClassicalUpscale(bitmap)
            }
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM during upscale, falling back", e)
            System.gc()
            runLowMemoryUpscale(bitmap)
        } catch (e: Exception) {
            Log.e(TAG, "Enhancement failed", e)
            bitmap
        }

        val finalResult = try {
            imageEnhancer.enhanceDetails(enhanced)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM during detail enhancement", e)
            System.gc()
            enhanced
        } catch (e: Exception) {
            Log.w(TAG, "Detail enhancement failed", e)
            enhanced
        }

        if (enhanced !== bitmap && enhanced !== finalResult) {
            try { enhanced.recycle() } catch (_: Exception) {}
        }

        val elapsed = (System.nanoTime() - startTime) / 1_000_000
        Log.d(TAG, "Done in ${elapsed}ms -> ${finalResult.width}x${finalResult.height}")
        return finalResult
    }

    private fun runClassicalUpscale(bitmap: Bitmap): Bitmap {
        val w = maxOf(bitmap.width, MIN_DIM)
        val h = maxOf(bitmap.height, MIN_DIM)
        val currentMp = (w * h) / 1_000_000f
        val targetMp = maxOf(ENHANCED_TARGET_MP, (currentMp * 2).toInt())
        val targetScale = min(sqrt(targetMp.toDouble() / currentMp), 4.0)

        val maxMem = Runtime.getRuntime().maxMemory()
        val needed = (w * targetScale).toLong() * (h * targetScale).toLong() * 4L * 3L
        if (needed > maxMem * MAX_MEMORY_USAGE) {
            val reducedScale = sqrt((maxMem * MAX_MEMORY_USAGE / (w.toLong() * h * 4L * 3L)).toDouble())
            val rw = maxOf((w * reducedScale).toInt(), MIN_DIM)
            val rh = maxOf((h * reducedScale).toInt(), MIN_DIM)
            val scaled = Bitmap.createScaledBitmap(bitmap, rw, rh, true)
            return imageEnhancer.adaptiveSharpen(scaled)
        }

        return multiStepUpscale(bitmap, targetScale)
    }

    private fun multiStepUpscale(bitmap: Bitmap, targetScale: Double): Bitmap {
        var current = bitmap
        var remaining = targetScale
        var needRecycle = false

        while (remaining > 1.3) {
            val step = min(STEP_SCALE, remaining)
            val sw = maxOf((current.width * step).toInt(), MIN_DIM)
            val sh = maxOf((current.height * step).toInt(), MIN_DIM)
            if (sw <= current.width && sh <= current.height) break
            val scaled = Bitmap.createScaledBitmap(current, sw, sh, true)
            if (needRecycle) try { current.recycle() } catch (_: Exception) {}
            current = imageEnhancer.adaptiveSharpen(scaled)
            needRecycle = true
            remaining /= step
        }

        if (remaining > 1.01) {
            val sw = maxOf((current.width * remaining).toInt(), MIN_DIM)
            val sh = maxOf((current.height * remaining).toInt(), MIN_DIM)
            if (sw > current.width || sh > current.height) {
                val scaled = Bitmap.createScaledBitmap(current, sw, sh, true)
                if (needRecycle) try { current.recycle() } catch (_: Exception) {}
                current = scaled
            }
        }

        return current
    }

    private fun runLowMemoryUpscale(bitmap: Bitmap): Bitmap {
        val scale = min(2.0, sqrt(3_000_000.0 / maxOf(bitmap.width * bitmap.height, 1)))
        val w = maxOf((bitmap.width * scale).toInt().coerceAtMost(2000), MIN_DIM)
        val h = maxOf((bitmap.height * scale).toInt().coerceAtMost(2000), MIN_DIM)
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }

    private fun runTFLiteSuperResolution(bitmap: Bitmap): Bitmap {
        val model = tfliteModel ?: return runClassicalUpscale(bitmap)
        val w = bitmap.width
        val h = bitmap.height

        if (w <= BLOCK_SIZE && h <= BLOCK_SIZE) {
            return runSingleBlock(bitmap, model)
        }
        return runBlocked(bitmap, model)
    }

    private fun runBlocked(bitmap: Bitmap, model: InterpreterWrapper): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val scale = 2
        val outW = w * scale
        val outH = h * scale

        val maxMem = Runtime.getRuntime().maxMemory()
        val bitmapMem = outW.toLong() * outH * 4L
        if (bitmapMem > maxMem * MAX_MEMORY_USAGE) {
            return runClassicalUpscale(bitmap)
        }

        val result = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val cols = (w + BLOCK_SIZE - 1) / BLOCK_SIZE
        val rows = (h + BLOCK_SIZE - 1) / BLOCK_SIZE

        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val sx = col * BLOCK_SIZE
                val sy = row * BLOCK_SIZE
                val ew = minOf(BLOCK_SIZE, w - sx)
                val eh = minOf(BLOCK_SIZE, h - sy)

                try {
                    val block = Bitmap.createBitmap(bitmap, sx, sy, ew, eh)
                    val enhanced = runSingleBlock(block, model)
                    canvas.drawBitmap(enhanced, (sx * scale).toFloat(), (sy * scale).toFloat(), null)
                    enhanced.recycle()
                    block.recycle()
                } catch (e: Exception) {
                    Log.w(TAG, "Block processing failed at $sx,$sy", e)
                }
            }
        }
        return result
    }

    private fun runSingleBlock(block: Bitmap, model: InterpreterWrapper): Bitmap {
        val w = block.width
        val h = block.height
        val inputSize = model.inputSize

        val padW = if (w % 2 != 0) w + 1 else w
        val padH = if (h % 2 != 0) h + 1 else h
        val inputBitmap = if (padW != w || padH != h || padW > inputSize || padH > inputSize) {
            val rw = minOf(padW, inputSize)
            val rh = minOf(padH, inputSize)
            Bitmap.createScaledBitmap(block, rw, rh, true)
        } else block

        val needRecycle = inputBitmap !== block
        val ibw = inputBitmap.width
        val ibh = inputBitmap.height

        val inputBuffer = bitmapToFloatBuffer(inputBitmap)
        val outputBuffer = java.nio.FloatBuffer.allocate(1 * ibw * 2 * ibh * 2 * 3)
        model.run(inputBuffer, outputBuffer)
        val outputBitmap = floatBufferToBitmap(outputBuffer, ibw * 2, ibh * 2)

        if (needRecycle) inputBitmap.recycle()

        return if (outputBitmap.width != w * 2 || outputBitmap.height != h * 2) {
            Bitmap.createScaledBitmap(outputBitmap, w * 2, h * 2, true).also {
                outputBitmap.recycle()
            }
        } else outputBitmap
    }

    private fun bitmapToFloatBuffer(bitmap: Bitmap): java.nio.FloatBuffer {
        val w = bitmap.width
        val h = bitmap.height
        val buffer = java.nio.FloatBuffer.allocate(1 * w * h * 3)
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = pixels[y * w + x]
                buffer.put(((p shr 16) and 0xFF) / 255f)
                buffer.put(((p shr 8) and 0xFF) / 255f)
                buffer.put((p and 0xFF) / 255f)
            }
        }
        buffer.rewind()
        return buffer
    }

    private fun floatBufferToBitmap(buffer: java.nio.FloatBuffer, w: Int, h: Int): Bitmap {
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        buffer.rewind()
        for (i in pixels.indices) {
            val r = (buffer.get() * 255f).toInt().coerceIn(0, 255)
            val g = (buffer.get() * 255f).toInt().coerceIn(0, 255)
            val b = (buffer.get() * 255f).toInt().coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        result.setPixels(pixels, 0, w, 0, 0, w, h)
        return result
    }

    private fun loadModelIfNeeded() {
        if (tfliteModel != null) return
        try {
            val modelFile = File(context.getExternalFilesDir("models"), MODEL_FILENAME)
            if (modelFile.exists()) {
                val buf = loadModelFile(modelFile)
                tfliteModel = InterpreterWrapper(buf)
            } else {
                val stream = context.assets.open(MODEL_FILENAME)
                val bytes = stream.readBytes()
                stream.close()
                val buf = java.nio.ByteBuffer.allocateDirect(bytes.size)
                buf.put(bytes)
                buf.rewind()
                tfliteModel = InterpreterWrapper(buf)
            }
        } catch (e: Exception) {
            Log.w(TAG, "No TFLite model, using classical pipeline")
            tfliteModel = InterpreterWrapper(null)
        }
    }

    private fun loadModelFile(file: File): java.nio.ByteBuffer {
        val stream = java.io.FileInputStream(file)
        val ch = stream.channel
        val buf = ch.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, file.length())
        ch.close()
        stream.close()
        return buf
    }

    private class InterpreterWrapper(private val modelBuffer: java.nio.ByteBuffer?) {
        private var interpreter: org.tensorflow.lite.Interpreter? = null
        val inputSize: Int
        val isValid: Boolean

        init {
            var size = 0
            var valid = false
            if (modelBuffer != null) {
                try {
                    interpreter = org.tensorflow.lite.Interpreter(modelBuffer)
                    val shape = interpreter?.getInputTensor(0)?.shape()
                    if (shape != null && shape.size >= 2) {
                        valid = true
                        size = shape[1].coerceAtMost(shape[2])
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Interpreter init failed: ${e.message}")
                }
            }
            isValid = valid
            inputSize = size
        }

        fun run(input: java.nio.FloatBuffer, output: java.nio.FloatBuffer) {
            interpreter?.run(input, output)
        }
    }
}
