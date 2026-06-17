package com.uhdcam.app.ai

import android.graphics.*
import androidx.annotation.WorkerThread
import kotlin.math.*

class ImageEnhancer {

    @WorkerThread
    fun enhanceDetails(bitmap: Bitmap): Bitmap {
        var result = bitmap
        result = localContrastEnhancement(result)
        result = unsharpMask(result)
        result = adaptiveSharpen(result)
        result = denoiseLightly(result)
        return result
    }

    @WorkerThread
    fun adaptiveSharpen(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val r = FloatArray(w * h)
        val g = FloatArray(w * h)
        val b = FloatArray(w * h)
        for (i in pixels.indices) {
            r[i] = ((pixels[i] shr 16) and 0xFF).toFloat()
            g[i] = ((pixels[i] shr 8) and 0xFF).toFloat()
            b[i] = (pixels[i] and 0xFF).toFloat()
        }

        val lapR = laplacian(r, w, h)
        val lapG = laplacian(g, w, h)
        val lapB = laplacian(b, w, h)

        val strength = 0.6f
        val outPixels = IntArray(w * h)
        for (i in pixels.indices) {
            val nr = (r[i] - strength * lapR[i]).coerceIn(0f, 255f).toInt()
            val ng = (g[i] - strength * lapG[i]).coerceIn(0f, 255f).toInt()
            val nb = (b[i] - strength * lapB[i]).coerceIn(0f, 255f).toInt()
            outPixels[i] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
        }

        result.setPixels(outPixels, 0, w, 0, 0, w, h)
        return result
    }

    private fun laplacian(input: FloatArray, w: Int, h: Int): FloatArray {
        val result = FloatArray(input.size)
        val kernel = arrayOf(
            intArrayOf(0, -1, 0),
            intArrayOf(-1, 4, -1),
            intArrayOf(0, -1, 0)
        )

        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                var sum = 0f
                for (ky in 0..2) {
                    for (kx in 0..2) {
                        val px = (x + kx - 1).coerceIn(0, w - 1)
                        val py = (y + ky - 1).coerceIn(0, h - 1)
                        sum += input[py * w + px] * kernel[ky][kx]
                    }
                }
                result[y * w + x] = sum
            }
        }
        return result
    }

    private fun unsharpMask(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val blurredPixels = IntArray(w * h)
        val blurRadius = 2
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sumR = 0; var sumG = 0; var sumB = 0; var count = 0
                for (dy in -blurRadius..blurRadius) {
                    for (dx in -blurRadius..blurRadius) {
                        val px = (x + dx).coerceIn(0, w - 1)
                        val py = (y + dy).coerceIn(0, h - 1)
                        val p = pixels[py * w + px]
                        sumR += (p shr 16) and 0xFF
                        sumG += (p shr 8) and 0xFF
                        sumB += p and 0xFF
                        count++
                    }
                }
                val avgR = sumR / count; val avgG = sumG / count; val avgB = sumB / count
                blurredPixels[y * w + x] = (0xFF shl 24) or (avgR shl 16) or (avgG shl 8) or avgB
            }
        }

        val outPixels = IntArray(w * h)
        val amount = 0.3f
        for (i in pixels.indices) {
            val origR = (pixels[i] shr 16) and 0xFF
            val origG = (pixels[i] shr 8) and 0xFF
            val origB = pixels[i] and 0xFF
            val blurR = (blurredPixels[i] shr 16) and 0xFF
            val blurG = (blurredPixels[i] shr 8) and 0xFF
            val blurB = blurredPixels[i] and 0xFF
            val nr = (origR + amount * (origR - blurR)).toInt().coerceIn(0, 255)
            val ng = (origG + amount * (origG - blurG)).toInt().coerceIn(0, 255)
            val nb = (origB + amount * (origB - blurB)).toInt().coerceIn(0, 255)
            outPixels[i] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
        }

        result.setPixels(outPixels, 0, w, 0, 0, w, h)
        return result
    }

    private fun localContrastEnhancement(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val radius = (Math.max(w, h) / 50).coerceIn(2, 20)

        val outPixels = IntArray(w * h)

        for (y in 0 until h) {
            for (x in 0 until w) {
                var sumR = 0; var sumG = 0; var sumB = 0
                val kyStart = max(0, y - radius)
                val kyEnd = min(h - 1, y + radius)
                val kxStart = max(0, x - radius)
                val kxEnd = min(w - 1, x + radius)
                var count = 0
                for (ky in kyStart..kyEnd) {
                    for (kx in kxStart..kxEnd) {
                        val pixel = pixels[ky * w + kx]
                        sumR += (pixel shr 16) and 0xFF
                        sumG += (pixel shr 8) and 0xFF
                        sumB += pixel and 0xFF
                        count++
                    }
                }

                if (count > 0) {
                    val avgR = sumR / count; val avgG = sumG / count; val avgB = sumB / count
                    val curPixel = pixels[y * w + x]
                    val curR = (curPixel shr 16) and 0xFF
                    val curG = (curPixel shr 8) and 0xFF
                    val curB = curPixel and 0xFF
                    val contrast = 1.4f
                    val nr = ((avgR + (curR - avgR) * contrast)).toInt().coerceIn(0, 255)
                    val ng = ((avgG + (curG - avgG) * contrast)).toInt().coerceIn(0, 255)
                    val nb = ((avgB + (curB - avgB) * contrast)).toInt().coerceIn(0, 255)
                    outPixels[y * w + x] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
                }
            }
        }

        result.setPixels(outPixels, 0, w, 0, 0, w, h)
        return result
    }

    private fun denoiseLightly(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)

        val threshold = 30
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val center = pixels[y * w + x]
                val cR = (center shr 16) and 0xFF
                val cG = (center shr 8) and 0xFF
                val cB = center and 0xFF
                var sumR = 0; var sumG = 0; var sumB = 0; var count = 0

                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val neighbor = pixels[(y + dy) * w + (x + dx)]
                        val nR = (neighbor shr 16) and 0xFF
                        val nG = (neighbor shr 8) and 0xFF
                        val nB = neighbor and 0xFF
                        if (abs(cR - nR) < threshold && abs(cG - nG) < threshold && abs(cB - nB) < threshold) {
                            sumR += nR; sumG += nG; sumB += nB; count++
                        }
                    }
                }

                if (count > 0) {
                    val nr = (sumR / count).coerceIn(0, 255)
                    val ng = (sumG / count).coerceIn(0, 255)
                    val nb = (sumB / count).coerceIn(0, 255)
                    out[y * w + x] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
                } else {
                    out[y * w + x] = center
                }
            }
        }

        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }
}
