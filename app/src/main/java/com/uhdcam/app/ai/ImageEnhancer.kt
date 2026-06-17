package com.uhdcam.app.ai

import android.graphics.*
import androidx.annotation.WorkerThread
import kotlin.math.*

class ImageEnhancer {

    @WorkerThread
    fun enhanceDetails(bitmap: Bitmap): Bitmap {
        if (bitmap.width < 10 || bitmap.height < 10) return bitmap
        var result = bitmap

        var next = edgePreservingDenoise(result)
        if (next !== result) { result.recycle(); result = next }

        next = luminanceContrastEnhancement(result)
        if (next !== result) { result.recycle(); result = next }

        next = adaptiveUnsharpMask(result, radius = 1, amount = 0.7f)
        if (next !== result) { result.recycle(); result = next }

        next = microcontrastEnhance(result)
        if (next !== result) { result.recycle(); result = next }

        next = saturationBoost(result, 1.15f)
        if (next !== result) { result.recycle(); result = next }

        return result
    }

    @WorkerThread
    fun adaptiveSharpen(bitmap: Bitmap): Bitmap {
        return adaptiveUnsharpMask(bitmap, radius = 2, amount = 0.5f)
    }

    private fun gaussianKernel1D(radius: Int): FloatArray {
        val size = radius * 2 + 1
        val kernel = FloatArray(size)
        var sum = 0f
        for (i in 0 until size) {
            val x = i - radius
            kernel[i] = exp(-(x * x).toFloat() / (2f * radius * radius))
            sum += kernel[i]
        }
        for (i in 0 until size) kernel[i] /= sum
        return kernel
    }

    private fun separableGaussianBlur(pixels: IntArray, w: Int, h: Int, radius: Int): IntArray {
        val kernel = gaussianKernel1D(radius)
        val half = radius
        val temp = IntArray(w * h)

        for (y in 0 until h) {
            for (x in 0 until w) {
                var r = 0f; var g = 0f; var b = 0f
                for (k in kernel.indices) {
                    val sx = (x + k - half).coerceIn(0, w - 1)
                    val p = pixels[y * w + sx]
                    val kw = kernel[k]
                    r += ((p shr 16) and 0xFF) * kw
                    g += ((p shr 8) and 0xFF) * kw
                    b += (p and 0xFF) * kw
                }
                temp[y * w + x] = (0xFF shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
            }
        }

        val result = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var r = 0f; var g = 0f; var b = 0f
                for (k in kernel.indices) {
                    val sy = (y + k - half).coerceIn(0, h - 1)
                    val p = temp[sy * w + x]
                    val kw = kernel[k]
                    r += ((p shr 16) and 0xFF) * kw
                    g += ((p shr 8) and 0xFF) * kw
                    b += (p and 0xFF) * kw
                }
                result[y * w + x] = (0xFF shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
            }
        }
        return result
    }

    private fun blurFloatArray(arr: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        val kernel = gaussianKernel1D(radius)
        val half = radius
        val temp = FloatArray(w * h)

        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0f
                for (k in kernel.indices) {
                    val sx = (x + k - half).coerceIn(0, w - 1)
                    sum += arr[y * w + sx] * kernel[k]
                }
                temp[y * w + x] = sum
            }
        }

        val result = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0f
                for (k in kernel.indices) {
                    val sy = (y + k - half).coerceIn(0, h - 1)
                    sum += temp[sy * w + x] * kernel[k]
                }
                result[y * w + x] = sum
            }
        }
        return result
    }

    private fun adaptiveUnsharpMask(bitmap: Bitmap, radius: Int, amount: Float): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val blurred = separableGaussianBlur(pixels, w, h, radius)

        val out = IntArray(w * h)
        for (i in pixels.indices) {
            val oR = (pixels[i] shr 16) and 0xFF
            val oG = (pixels[i] shr 8) and 0xFF
            val oB = pixels[i] and 0xFF
            val bR = (blurred[i] shr 16) and 0xFF
            val bG = (blurred[i] shr 8) and 0xFF
            val bB = blurred[i] and 0xFF

            val diffR = oR - bR
            val diffG = oG - bG
            val diffB = oB - bB

            val oLum = 0.299f * oR + 0.587f * oG + 0.114f * oB
            val bLum = 0.299f * bR + 0.587f * bG + 0.114f * bB
            val edgeMag = abs(oLum - bLum)
            val adaptive = amount * (0.25f + 0.75f * min(edgeMag / 50f, 1f))

            val nr = (oR + adaptive * diffR).toInt().coerceIn(0, 255)
            val ng = (oG + adaptive * diffG).toInt().coerceIn(0, 255)
            val nb = (oB + adaptive * diffB).toInt().coerceIn(0, 255)
            out[i] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
        }

        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }

    private fun luminanceContrastEnhancement(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val lum = FloatArray(w * h)
        for (i in pixels.indices) {
            val r = (pixels[i] shr 16) and 0xFF
            val g = (pixels[i] shr 8) and 0xFF
            val b = pixels[i] and 0xFF
            lum[i] = 0.299f * r + 0.587f * g + 0.114f * b
        }

        val detailRadius = maxOf(1, min(w, h) / 150)
        val toneRadius = maxOf(7, min(w, h) / 20)
        val detailLum = blurFloatArray(lum, w, h, detailRadius)
        val toneLum = blurFloatArray(lum, w, h, toneRadius)

        val out = IntArray(w * h)
        for (i in pixels.indices) {
            val r = (pixels[i] shr 16) and 0xFF
            val g = (pixels[i] shr 8) and 0xFF
            val b = pixels[i] and 0xFF
            val orig = lum[i]
            val detail = detailLum[i]
            val tone = toneLum[i]

            val diff = orig - detail
            val localContrast = abs(diff) / maxOf(detail, 1f)
            val gain = 2.0f * (0.4f + 0.6f * min(localContrast * 4f, 1f))

            var enhanced = detail + gain * diff

            val toneRatio = tone / 128f
            val toneAdj = 1f + 0.15f * (1f - toneRatio).coerceIn(-1f, 1f)
            enhanced *= toneAdj

            val ratio = enhanced / maxOf(orig, 1f)
            val nr = (r * ratio).toInt().coerceIn(0, 255)
            val ng = (g * ratio).toInt().coerceIn(0, 255)
            val nb = (b * ratio).toInt().coerceIn(0, 255)
            out[i] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
        }

        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }

    private fun microcontrastEnhance(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val lum = FloatArray(w * h)
        for (i in pixels.indices) {
            val r = (pixels[i] shr 16) and 0xFF
            val g = (pixels[i] shr 8) and 0xFF
            val b = pixels[i] and 0xFF
            lum[i] = 0.299f * r + 0.587f * g + 0.114f * b
        }

        val out = IntArray(w * h)
        val strength = 0.3f

        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val idx = y * w + x
                val avgLum = (lum[idx - w - 1] + lum[idx - w] + lum[idx - w + 1] +
                    lum[idx - 1] + lum[idx + 1] +
                    lum[idx + w - 1] + lum[idx + w] + lum[idx + w + 1]) / 8f

                val diff = lum[idx] - avgLum
                val adj = strength * diff / maxOf(lum[idx], 0.001f)

                val p = pixels[idx]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val nr = (r * (1f + adj)).toInt().coerceIn(0, 255)
                val ng = (g * (1f + adj)).toInt().coerceIn(0, 255)
                val nb = (b * (1f + adj)).toInt().coerceIn(0, 255)
                out[idx] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }
        for (x in 0 until w) {
            out[x] = pixels[x]
            out[(h - 1) * w + x] = pixels[(h - 1) * w + x]
        }
        for (y in 0 until h) {
            out[y * w] = pixels[y * w]
            out[y * w + w - 1] = pixels[y * w + w - 1]
        }

        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }

    private fun edgePreservingDenoise(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val spatialSigma = 2f
        val rangeSigma = 30f
        val radius = 2

        val spatialWeight = FloatArray((radius * 2 + 1) * (radius * 2 + 1))
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                val idx = (dy + radius) * (radius * 2 + 1) + (dx + radius)
                spatialWeight[idx] = exp(-(dx * dx + dy * dy).toFloat() / (2f * spatialSigma * spatialSigma))
            }
        }

        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = pixels[y * w + x]
                val cR = (c shr 16) and 0xFF
                val cG = (c shr 8) and 0xFF
                val cB = c and 0xFF
                val cLum = 0.299f * cR + 0.587f * cG + 0.114f * cB

                var wSum = 0f
                var sR = 0f; var sG = 0f; var sB = 0f

                for (dy in -radius..radius) {
                    for (dx in -radius..radius) {
                        val px = (x + dx).coerceIn(0, w - 1)
                        val py = (y + dy).coerceIn(0, h - 1)
                        val n = pixels[py * w + px]
                        val nR = (n shr 16) and 0xFF
                        val nG = (n shr 8) and 0xFF
                        val nB = n and 0xFF
                        val nLum = 0.299f * nR + 0.587f * nG + 0.114f * nB

                        val sidx = (dy + radius) * (radius * 2 + 1) + (dx + radius)
                        val rangeW = exp(-((cLum - nLum) * (cLum - nLum)) / (2f * rangeSigma * rangeSigma))
                        val wgt = spatialWeight[sidx] * rangeW

                        sR += nR * wgt
                        sG += nG * wgt
                        sB += nB * wgt
                        wSum += wgt
                    }
                }

                val nr = (sR / maxOf(wSum, 0.001f)).toInt().coerceIn(0, 255)
                val ng = (sG / maxOf(wSum, 0.001f)).toInt().coerceIn(0, 255)
                val nb = (sB / maxOf(wSum, 0.001f)).toInt().coerceIn(0, 255)
                out[y * w + x] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }

        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }

    private fun saturationBoost(bitmap: Bitmap, factor: Float): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)

        for (i in pixels.indices) {
            val r = (pixels[i] shr 16) and 0xFF
            val g = (pixels[i] shr 8) and 0xFF
            val b = pixels[i] and 0xFF
            val gray = (r + g + b) / 3f
            val nr = (gray + (r - gray) * factor).toInt().coerceIn(0, 255)
            val ng = (gray + (g - gray) * factor).toInt().coerceIn(0, 255)
            val nb = (gray + (b - gray) * factor).toInt().coerceIn(0, 255)
            out[i] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
        }

        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }
}
