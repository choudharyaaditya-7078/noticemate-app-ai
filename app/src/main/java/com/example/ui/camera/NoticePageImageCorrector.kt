package com.example.ui.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.data.local.AppDiagnosticLogger
import com.example.data.model.CameraQualityGuidance
import com.example.data.model.CropRectNormalized
import com.example.data.model.DetectedDocumentQuad
import com.example.data.model.NormalizedPoint
import com.example.data.model.NoticePageEditState
import com.example.data.model.PageOcrPreviewStatus
import com.example.data.model.ScannedNoticePage
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Phase 9 Non-Destructive Image Correction Engine for NoticeBuddy's Multi-Page Scanner.
 *
 * Supports:
 * 1. Lossless 90° / 180° / 270° rotation via exact pixel transposition.
 * 2. Bounded normalized cropping (auto-document bounds crop or custom margin crop) that never
 *    produces corrupt or unreadably tiny images.
 * 3. 4-corner bilinear perspective rectification (`warpPerspectiveBilinear`) to flatten angled
 *    or keystone-tilted notice pages into upright rectangular pages while preserving text sharpness.
 * 4. Post-correction image-quality re-evaluation (`evaluatePageQualityGuidance`) so blur,
 *    low lighting, glare, tilt, or cut-off warnings stay accurate before document submission.
 */
object NoticePageImageCorrector {

    private const val MIN_CROP_SPAN_NORM = 0.20f
    private const val MIN_OUTPUT_DIMENSION_PX = 16
    private const val MAX_PERSPECTIVE_DIMENSION_PX = 1400

    /**
     * Evaluates initial quality and detected quadrilateral on a newly captured upright bitmap.
     */
    fun analyzeCapturedBitmap(
        bitmap: Bitmap,
        fallbackGuidance: CameraQualityGuidance,
        fallbackQuad: DetectedDocumentQuad?
    ): Pair<CameraQualityGuidance, DetectedDocumentQuad?> {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            return fallbackGuidance to fallbackQuad
        }
        return try {
            val frameAnalysis = NoticeDocumentEdgeAnalyzer.analyzeBitmap(
                bitmap = bitmap,
                rotationDegrees = 0,
                frameSequenceId = 0L
            )
            val resolvedQuad = frameAnalysis.detectedQuad ?: fallbackQuad
            val resolvedGuidance = when {
                frameAnalysis.isDocumentDetected && frameAnalysis.primaryGuidance.isWarning ->
                    frameAnalysis.primaryGuidance
                !frameAnalysis.isDocumentDetected && frameAnalysis.meanBrightness < NoticeDocumentEdgeAnalyzer.MIN_ACCEPTABLE_SCENE_LUMA ->
                    CameraQualityGuidance.POOR_LIGHTING
                fallbackGuidance.isWarning ->
                    fallbackGuidance
                frameAnalysis.isDocumentDetected && frameAnalysis.isQualityAcceptable ->
                    CameraQualityGuidance.READY_LOCKED
                !frameAnalysis.isDocumentDetected ->
                    CameraQualityGuidance.READY_LOCKED
                else ->
                    evaluateInteriorReadability(bitmap, allowTiltCheck = false)
            }
            resolvedGuidance to resolvedQuad
        } catch (e: Exception) {
            AppDiagnosticLogger.warn("IMAGE_CORRECTOR", "Fallback quality evaluation used: ${e.message}")
            fallbackGuidance to fallbackQuad
        }
    }

    /**
     * Computes a smart crop rectangle around the detected notice quadrilateral (with a 2% safety
     * margin so edge text is never clipped), or falls back to [CropRectNormalized.TIGHT_MARGIN_CROP].
     */
    fun computeSmartCropRect(page: ScannedNoticePage): CropRectNormalized {
        val quad = page.initialDetectedQuad ?: run {
            val decoded = decodeBytes(page.originalPngBytes)
            val detected = if (decoded != null) {
                try {
                    NoticeDocumentEdgeAnalyzer.analyzeBitmap(decoded, rotationDegrees = 0).detectedQuad
                } finally {
                    if (!decoded.isRecycled) decoded.recycle()
                }
            } else null
            detected
        }

        if (quad != null) {
            val minX = min(min(quad.topLeft.x, quad.bottomLeft.x), min(quad.topRight.x, quad.bottomRight.x))
            val maxX = max(max(quad.topLeft.x, quad.bottomLeft.x), max(quad.topRight.x, quad.bottomRight.x))
            val minY = min(min(quad.topLeft.y, quad.topRight.y), min(quad.bottomLeft.y, quad.bottomRight.y))
            val maxY = max(max(quad.topLeft.y, quad.topRight.y), max(quad.bottomLeft.y, quad.bottomRight.y))

            val pad = 0.02f
            val left = (minX - pad).coerceIn(0f, 0.40f)
            val top = (minY - pad).coerceIn(0f, 0.40f)
            val right = (maxX + pad).coerceIn(0.60f, 1f)
            val bottom = (maxY + pad).coerceIn(0.60f, 1f)

            val candidate = sanitizeCropRect(CropRectNormalized(left, top, right, bottom))
            if (candidate.isCropped && (candidate.widthRatio < 0.97f || candidate.heightRatio < 0.97f)) {
                return candidate
            }
        }

        return CropRectNormalized.TIGHT_MARGIN_CROP
    }

    /**
     * Sanitizes a [CropRectNormalized] so that `left < right`, `top < bottom`, and both spans are
     * at least [MIN_CROP_SPAN_NORM] (`20%` of the page) to prevent accidental destructive crops.
     */
    fun sanitizeCropRect(rect: CropRectNormalized): CropRectNormalized {
        val safeLeft = rect.left.coerceIn(0f, 1f - MIN_CROP_SPAN_NORM)
        val safeTop = rect.top.coerceIn(0f, 1f - MIN_CROP_SPAN_NORM)
        val safeRight = rect.right.coerceIn(safeLeft + MIN_CROP_SPAN_NORM, 1f)
        val safeBottom = rect.bottom.coerceIn(safeTop + MIN_CROP_SPAN_NORM, 1f)
        return CropRectNormalized(
            left = safeLeft,
            top = safeTop,
            right = safeRight,
            bottom = safeBottom
        )
    }

    /**
     * Non-destructively applies [newEditState] (perspective rectification, crop, and rotation)
     * starting from [page.originalPngBytes].
     *
     * Never mutates or degrades [page.originalPngBytes], and resets stale OCR preview text if the
     * image geometry changed.
     */
    fun applyEditsToPage(
        page: ScannedNoticePage,
        newEditState: NoticePageEditState
    ): ScannedNoticePage {
        val sanitizedEdit = newEditState.copy(
            extraRotationDegrees = newEditState.normalizedRotationDegrees,
            cropRect = sanitizeCropRect(newEditState.cropRect),
            perspectiveQuad = newEditState.perspectiveQuad ?: page.initialDetectedQuad
        )

        if (!sanitizedEdit.hasAnyEdits) {
            val baseDecoded = decodeBytes(page.originalPngBytes)
            val restoredGuidance = if (baseDecoded != null) {
                try {
                    analyzeCapturedBitmap(
                        bitmap = baseDecoded,
                        fallbackGuidance = page.captureQualityGuidance,
                        fallbackQuad = page.initialDetectedQuad
                    ).first
                } finally {
                    if (!baseDecoded.isRecycled) baseDecoded.recycle()
                }
            } else {
                page.captureQualityGuidance
            }
            return page.copy(
                currentPngBytes = page.originalPngBytes,
                widthPx = page.originalWidthPx,
                heightPx = page.originalHeightPx,
                captureQualityGuidance = restoredGuidance,
                editState = sanitizedEdit,
                ocrStatus = PageOcrPreviewStatus.NOT_RUN,
                ocrPreviewText = "",
                ocrStatusMessage = null
            )
        }

        val sourceBitmap = decodeBytes(page.originalPngBytes) ?: return page
        var workingBitmap: Bitmap = sourceBitmap

        try {
            // 1. Perspective Correction (rectifies 4-corner quadrilateral to upright rectangle)
            if (sanitizedEdit.isPerspectiveCorrected) {
                val quadToWarp = sanitizedEdit.perspectiveQuad
                    ?: page.initialDetectedQuad
                    ?: NoticeDocumentEdgeAnalyzer.analyzeBitmap(workingBitmap, rotationDegrees = 0).detectedQuad
                    ?: defaultPerspectiveQuad()

                val warped = warpPerspectiveBilinear(workingBitmap, quadToWarp)
                if (warped !== workingBitmap) {
                    if (workingBitmap !== sourceBitmap && !workingBitmap.isRecycled) {
                        workingBitmap.recycle()
                    }
                    workingBitmap = warped
                }
            }

            // 2. Normalized Crop
            if (sanitizedEdit.cropRect.isCropped) {
                val cropped = cropBitmapNormalized(workingBitmap, sanitizedEdit.cropRect)
                if (cropped !== workingBitmap) {
                    if (workingBitmap !== sourceBitmap && !workingBitmap.isRecycled) {
                        workingBitmap.recycle()
                    }
                    workingBitmap = cropped
                }
            }

            // 3. Lossless 90° / 180° / 270° Quarter-Turn Rotation
            if (sanitizedEdit.normalizedRotationDegrees != 0) {
                val rotated = rotateQuarterTurnsLossless(
                    bitmap = workingBitmap,
                    degrees = sanitizedEdit.normalizedRotationDegrees
                )
                if (rotated !== workingBitmap) {
                    if (workingBitmap !== sourceBitmap && !workingBitmap.isRecycled) {
                        workingBitmap.recycle()
                    }
                    workingBitmap = rotated
                }
            }

            val updatedGuidance = evaluateCorrectedPageQuality(
                bitmap = workingBitmap,
                editState = sanitizedEdit,
                previousGuidance = page.captureQualityGuidance
            )

            val encodedBytes = ByteArrayOutputStream().use { out ->
                workingBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }

            if (encodedBytes.isEmpty()) {
                return page
            }

            val finalW = workingBitmap.width
            val finalH = workingBitmap.height

            return page.copy(
                currentPngBytes = encodedBytes,
                widthPx = finalW,
                heightPx = finalH,
                captureQualityGuidance = updatedGuidance,
                editState = sanitizedEdit,
                ocrStatus = PageOcrPreviewStatus.NOT_RUN,
                ocrPreviewText = "",
                ocrStatusMessage = null
            )
        } catch (e: Exception) {
            AppDiagnosticLogger.error("IMAGE_CORRECTOR", "Failed to apply image edits to ${page.fileName}", e)
            return page
        } finally {
            if (workingBitmap !== sourceBitmap && !workingBitmap.isRecycled) {
                workingBitmap.recycle()
            }
            if (!sourceBitmap.isRecycled) {
                sourceBitmap.recycle()
            }
        }
    }

    /**
     * Crops [bitmap] to [cropRect] in normalized `[0f, 1f]` coordinates while guaranteeing valid,
     * readable output dimensions.
     */
    internal fun cropBitmapNormalized(
        bitmap: Bitmap,
        cropRect: CropRectNormalized
    ): Bitmap {
        val safe = sanitizeCropRect(cropRect)
        val w = bitmap.width
        val h = bitmap.height
        if (w <= MIN_OUTPUT_DIMENSION_PX || h <= MIN_OUTPUT_DIMENSION_PX) {
            return bitmap
        }

        val x0 = (safe.left * w).roundToInt().coerceIn(0, w - MIN_OUTPUT_DIMENSION_PX)
        val y0 = (safe.top * h).roundToInt().coerceIn(0, h - MIN_OUTPUT_DIMENSION_PX)
        val x1 = (safe.right * w).roundToInt().coerceIn(x0 + MIN_OUTPUT_DIMENSION_NORM_PX(w), w)
        val y1 = (safe.bottom * h).roundToInt().coerceIn(y0 + MIN_OUTPUT_DIMENSION_NORM_PX(h), h)

        val cropW = (x1 - x0).coerceAtLeast(1)
        val cropH = (y1 - y0).coerceAtLeast(1)
        if (cropW == w && cropH == h) {
            return bitmap
        }

        return Bitmap.createBitmap(bitmap, x0, y0, cropW, cropH)
    }

    private fun MIN_OUTPUT_DIMENSION_NORM_PX(totalDim: Int): Int {
        return max(MIN_OUTPUT_DIMENSION_PX, (totalDim * MIN_CROP_SPAN_NORM).roundToInt()).coerceAtMost(totalDim)
    }

    /**
     * Rotates [bitmap] by `90`, `180`, or `270` degrees clockwise using exact pixel transposition
     * so there is zero interpolation blur and dimensions swap deterministically on all runtimes.
     */
    internal fun rotateQuarterTurnsLossless(
        bitmap: Bitmap,
        degrees: Int
    ): Bitmap {
        val norm = ((degrees % 360) + 360) % 360
        if (norm == 0) return bitmap

        val srcW = bitmap.width
        val srcH = bitmap.height
        val srcPixels = IntArray(srcW * srcH)
        bitmap.getPixels(srcPixels, 0, srcW, 0, 0, srcW, srcH)

        return when (norm) {
            90 -> {
                val dstW = srcH
                val dstH = srcW
                val dstPixels = IntArray(dstW * dstH)
                for (y in 0 until srcH) {
                    val srcRow = y * srcW
                    val dstX = srcH - 1 - y
                    for (x in 0 until srcW) {
                        dstPixels[x * dstW + dstX] = srcPixels[srcRow + x]
                    }
                }
                Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888).apply {
                    setPixels(dstPixels, 0, dstW, 0, 0, dstW, dstH)
                }
            }

            180 -> {
                val dstPixels = IntArray(srcW * srcH)
                val total = srcW * srcH
                for (i in 0 until total) {
                    dstPixels[total - 1 - i] = srcPixels[i]
                }
                Bitmap.createBitmap(srcW, srcH, Bitmap.Config.ARGB_8888).apply {
                    setPixels(dstPixels, 0, srcW, 0, 0, srcW, srcH)
                }
            }

            270 -> {
                val dstW = srcH
                val dstH = srcW
                val dstPixels = IntArray(dstW * dstH)
                for (y in 0 until srcH) {
                    val srcRow = y * srcW
                    for (x in 0 until srcW) {
                        val dstY = srcW - 1 - x
                        dstPixels[dstY * dstW + y] = srcPixels[srcRow + x]
                    }
                }
                Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888).apply {
                    setPixels(dstPixels, 0, dstW, 0, 0, dstW, dstH)
                }
            }

            else -> bitmap
        }
    }

    /**
     * Rectifies the 4-corner quadrilateral [quad] inside [source] into a flat, upright rectangular
     * [Bitmap] using inverse bilinear coordinate mapping and bilinear sub-pixel interpolation.
     */
    internal fun warpPerspectiveBilinear(
        source: Bitmap,
        quad: DetectedDocumentQuad
    ): Bitmap {
        val srcW = source.width
        val srcH = source.height
        if (srcW < MIN_OUTPUT_DIMENSION_PX || srcH < MIN_OUTPUT_DIMENSION_PX) {
            return source
        }

        val tlX = (quad.topLeft.x.coerceIn(0f, 1f)) * (srcW - 1)
        val tlY = (quad.topLeft.y.coerceIn(0f, 1f)) * (srcH - 1)
        val trX = (quad.topRight.x.coerceIn(0f, 1f)) * (srcW - 1)
        val trY = (quad.topRight.y.coerceIn(0f, 1f)) * (srcH - 1)
        val brX = (quad.bottomRight.x.coerceIn(0f, 1f)) * (srcW - 1)
        val brY = (quad.bottomRight.y.coerceIn(0f, 1f)) * (srcH - 1)
        val blX = (quad.bottomLeft.x.coerceIn(0f, 1f)) * (srcW - 1)
        val blY = (quad.bottomLeft.y.coerceIn(0f, 1f)) * (srcH - 1)

        val topSpan = hypot((trX - tlX).toDouble(), (trY - tlY).toDouble()).toFloat()
        val bottomSpan = hypot((brX - blX).toDouble(), (brY - blY).toDouble()).toFloat()
        val leftSpan = hypot((blX - tlX).toDouble(), (blY - tlY).toDouble()).toFloat()
        val rightSpan = hypot((brX - trX).toDouble(), (brY - trY).toDouble()).toFloat()

        val rawTargetW = ((topSpan + bottomSpan) * 0.5f).roundToInt().coerceAtLeast(MIN_OUTPUT_DIMENSION_PX)
        val rawTargetH = ((leftSpan + rightSpan) * 0.5f).roundToInt().coerceAtLeast(MIN_OUTPUT_DIMENSION_PX)

        val maxDim = max(rawTargetW, rawTargetH)
        val scale = if (maxDim > MAX_PERSPECTIVE_DIMENSION_PX) {
            MAX_PERSPECTIVE_DIMENSION_PX.toFloat() / maxDim.toFloat()
        } else {
            1.0f
        }
        val dstW = (rawTargetW * scale).roundToInt().coerceAtLeast(MIN_OUTPUT_DIMENSION_PX)
        val dstH = (rawTargetH * scale).roundToInt().coerceAtLeast(MIN_OUTPUT_DIMENSION_PX)

        val srcPixels = IntArray(srcW * srcH)
        source.getPixels(srcPixels, 0, srcW, 0, 0, srcW, srcH)
        val dstPixels = IntArray(dstW * dstH)

        val invW = if (dstW > 1) 1f / (dstW - 1).toFloat() else 0f
        val invH = if (dstH > 1) 1f / (dstH - 1).toFloat() else 0f

        for (v in 0 until dstH) {
            val t = v * invH
            val oneMinusT = 1f - t
            val rowOffset = v * dstW

            for (u in 0 until dstW) {
                val s = u * invW
                val oneMinusS = 1f - s

                val sx = (oneMinusS * oneMinusT * tlX) +
                    (s * oneMinusT * trX) +
                    (s * t * brX) +
                    (oneMinusS * t * blX)

                val sy = (oneMinusS * oneMinusT * tlY) +
                    (s * oneMinusT * trY) +
                    (s * t * brY) +
                    (oneMinusS * t * blY)

                dstPixels[rowOffset + u] = sampleBilinearPixel(srcPixels, srcW, srcH, sx, sy)
            }
        }

        return Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888).apply {
            setPixels(dstPixels, 0, dstW, 0, 0, dstW, dstH)
        }
    }

    private fun sampleBilinearPixel(
        pixels: IntArray,
        width: Int,
        height: Int,
        x: Float,
        y: Float
    ): Int {
        val clampedX = x.coerceIn(0f, (width - 1).toFloat())
        val clampedY = y.coerceIn(0f, (height - 1).toFloat())

        val x0 = clampedX.toInt().coerceIn(0, width - 1)
        val y0 = clampedY.toInt().coerceIn(0, height - 1)
        val x1 = (x0 + 1).coerceAtMost(width - 1)
        val y1 = (y0 + 1).coerceAtMost(height - 1)

        val fx = clampedX - x0
        val fy = clampedY - y0
        val w00 = (1f - fx) * (1f - fy)
        val w10 = fx * (1f - fy)
        val w01 = (1f - fx) * fy
        val w11 = fx * fy

        val c00 = pixels[y0 * width + x0]
        val c10 = pixels[y0 * width + x1]
        val c01 = pixels[y1 * width + x0]
        val c11 = pixels[y1 * width + x1]

        val r = (((c00 shr 16) and 0xFF) * w00 +
            ((c10 shr 16) and 0xFF) * w10 +
            ((c01 shr 16) and 0xFF) * w01 +
            ((c11 shr 16) and 0xFF) * w11).roundToInt().coerceIn(0, 255)

        val g = (((c00 shr 8) and 0xFF) * w00 +
            ((c10 shr 8) and 0xFF) * w10 +
            ((c01 shr 8) and 0xFF) * w01 +
            ((c11 shr 8) and 0xFF) * w11).roundToInt().coerceIn(0, 255)

        val b = ((c00 and 0xFF) * w00 +
            (c10 and 0xFF) * w10 +
            (c01 and 0xFF) * w01 +
            (c11 and 0xFF) * w11).roundToInt().coerceIn(0, 255)

        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /**
     * Evaluates the image quality of a corrected page bitmap.
     * When perspective correction or cropping has been applied, geometric tilt/border cut-off
     * warnings are resolved if the interior luminance, glare, and sharpness are acceptable.
     */
    internal fun evaluateCorrectedPageQuality(
        bitmap: Bitmap,
        editState: NoticePageEditState,
        previousGuidance: CameraQualityGuidance
    ): CameraQualityGuidance {
        val interiorCheck = evaluateInteriorReadability(bitmap, allowTiltCheck = !editState.isPerspectiveCorrected)
        if (interiorCheck.isWarning) {
            return interiorCheck
        }

        if (!editState.isPerspectiveCorrected && !editState.cropRect.isCropped) {
            val fullAnalysis = NoticeDocumentEdgeAnalyzer.analyzeBitmap(bitmap, rotationDegrees = 0)
            if (fullAnalysis.isDocumentDetected && fullAnalysis.primaryGuidance.isWarning) {
                return fullAnalysis.primaryGuidance
            }
            if (previousGuidance.isWarning) {
                return previousGuidance
            }
        }

        return CameraQualityGuidance.READY_LOCKED
    }

    private fun evaluateInteriorReadability(
        bitmap: Bitmap,
        allowTiltCheck: Boolean
    ): CameraQualityGuidance {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 8 || h < 8) return CameraQualityGuidance.BLURRY

        val stepX = (w / 96).coerceAtLeast(1)
        val stepY = (h / 128).coerceAtLeast(1)
        val gridW = (w / stepX).coerceAtLeast(4)
        val gridH = (h / stepY).coerceAtLeast(4)
        val luma = IntArray(gridW * gridH)

        var totalLuma = 0L
        for (gy in 0 until gridH) {
            val sy = (gy * stepY).coerceAtMost(h - 1)
            for (gx in 0 until gridW) {
                val sx = (gx * stepX).coerceAtMost(w - 1)
                val px = bitmap.getPixel(sx, sy)
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF
                val yVal = ((66 * r + 129 * g + 25 * b + 128) shr 8).coerceIn(0, 255)
                luma[gy * gridW + gx] = yVal
                totalLuma += yVal
            }
        }

        val meanBrightness = totalLuma.toFloat() / (gridW * gridH).toFloat()
        if (meanBrightness < NoticeDocumentEdgeAnalyzer.MIN_ACCEPTABLE_SCENE_LUMA) {
            return CameraQualityGuidance.POOR_LIGHTING
        }

        val x0 = (gridW * 0.12f).toInt().coerceIn(1, gridW - 2)
        val x1 = (gridW * 0.88f).toInt().coerceIn(x0, gridW - 2)
        val y0 = (gridH * 0.12f).toInt().coerceIn(1, gridH - 2)
        val y1 = (gridH * 0.88f).toInt().coerceIn(y0, gridH - 2)

        var count = 0
        var glareCount = 0
        val laplacians = IntArray((x1 - x0 + 1) * (y1 - y0 + 1))

        for (y in y0..y1) {
            val row = y * gridW
            for (x in x0..x1) {
                val c = luma[row + x]
                if (c >= 250) {
                    val l = luma[row + x - 1]
                    val r = luma[row + x + 1]
                    val u = luma[(y - 1) * gridW + x]
                    val d = luma[(y + 1) * gridW + x]
                    if (l >= 246 && r >= 246 && u >= 246 && d >= 246) {
                        glareCount++
                    }
                }
                val lap = abs(
                    4 * c -
                        luma[row + x - 1] -
                        luma[row + x + 1] -
                        luma[(y - 1) * gridW + x] -
                        luma[(y + 1) * gridW + x]
                )
                laplacians[count++] = lap
            }
        }

        if (count > 0) {
            val glareRatio = glareCount.toFloat() / count.toFloat()
            if (glareRatio >= NoticeDocumentEdgeAnalyzer.MAX_ACCEPTABLE_GLARE_RATIO) {
                return CameraQualityGuidance.GLARE_DETECTED
            }

            val sorted = laplacians.copyOf(count).also { it.sort() }
            val p92 = sorted[(count * 0.92f).toInt().coerceIn(0, count - 1)]
            val sharpnessScore = (p92.toFloat() / 4.0f).coerceIn(0f, 100f)
            if (sharpnessScore < NoticeDocumentEdgeAnalyzer.MIN_ACCEPTABLE_SHARPNESS_SCORE) {
                return CameraQualityGuidance.BLURRY
            }
        }

        if (allowTiltCheck) {
            val analysis = NoticeDocumentEdgeAnalyzer.analyzeBitmap(bitmap, rotationDegrees = 0)
            if (analysis.isDocumentDetected && analysis.primaryGuidance == CameraQualityGuidance.TILTED_OR_ANGLED) {
                return CameraQualityGuidance.TILTED_OR_ANGLED
            }
        }

        return CameraQualityGuidance.READY_LOCKED
    }

    private fun defaultPerspectiveQuad(): DetectedDocumentQuad {
        return DetectedDocumentQuad(
            topLeft = NormalizedPoint(0.10f, 0.10f),
            topRight = NormalizedPoint(0.90f, 0.10f),
            bottomRight = NormalizedPoint(0.92f, 0.90f),
            bottomLeft = NormalizedPoint(0.08f, 0.90f),
            confidence = 0.85f,
            areaRatio = 0.64f
        )
    }

    private fun decodeBytes(pngBytes: ByteArray): Bitmap? {
        if (pngBytes.isEmpty()) return null
        return runCatching {
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size, opts)
        }.getOrNull()
    }
}
