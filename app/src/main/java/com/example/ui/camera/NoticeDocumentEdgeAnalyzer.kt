package com.example.ui.camera

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import com.example.data.model.CameraQualityGuidance
import com.example.data.model.DetectedDocumentQuad
import com.example.data.model.DocumentDetectionUiState
import com.example.data.model.DocumentFrameAnalysisResult
import com.example.data.model.NormalizedPoint
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Phase 8 Intelligent Notice Document-Edge & Image-Quality Analyzer.
 *
 * Uses CameraX `ImageAnalysis` (YUV_420_888 Y-plane) or `Bitmap` downsampling to a normalized
 * luminance grid (`120 x 160` upright) with:
 * 1. Morphological local-maximum paper surface envelope (bridging dark printed text lines inside a bright sheet).
 * 2. Background-vs-page contrast verification to avoid false positives on empty camera scenes.
 * 3. Four-edge linear boundary regression & analytical corner intersection for accurate quadrilateral detection.
 * 4. Real-time image-quality checks for:
 *    - Poor lighting (`POOR_LIGHTING`)
 *    - Specular glare hotspots (`GLARE_DETECTED`)
 *    - Cut-off document edges (`CUT_OFF_EDGES`)
 *    - In-plane tilt and perspective keystone angle (`TILTED_OR_ANGLED`)
 *    - Focus / motion blur via interior Laplacian & Sobel sharpness (`BLURRY`)
 */
object NoticeDocumentEdgeAnalyzer {

    private const val TARGET_GRID_WIDTH = 120
    private const val TARGET_GRID_HEIGHT = 160

    const val MIN_ACCEPTABLE_SCENE_LUMA = 52f
    const val MIN_ACCEPTABLE_PAGE_LUMA = 88f
    const val MIN_PAGE_CONTRAST_DELTA = 28f
    const val MAX_ACCEPTABLE_GLARE_RATIO = 0.12f
    const val MAX_ACCEPTABLE_TILT_DEGREES = 10.0f
    const val MIN_ACCEPTABLE_SHARPNESS_SCORE = 18.0f
    const val CUT_OFF_MARGIN_NORM = 0.025f

    /**
     * Analyzes a live CameraX [ImageProxy] frame.
     * Caller is responsible for closing [imageProxy] in a `finally` block.
     */
    fun analyzeImageProxy(
        imageProxy: ImageProxy,
        frameSequenceId: Long
    ): DocumentFrameAnalysisResult {
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val planes = imageProxy.planes
        if (planes.isNotEmpty() && imageProxy.width > 0 && imageProxy.height > 0) {
            val yPlane = planes[0]
            val buffer = yPlane.buffer
            val rowStride = yPlane.rowStride
            val pixelStride = max(1, yPlane.pixelStride)
            val srcW = imageProxy.width
            val srcH = imageProxy.height
            val normalizedRot = ((rotationDegrees % 360) + 360) % 360

            val gridW = TARGET_GRID_WIDTH
            val gridH = TARGET_GRID_HEIGHT
            val lumaGrid = IntArray(gridW * gridH)

            buffer.rewind()
            val limit = buffer.limit()

            for (gy in 0 until gridH) {
                val normY = (gy + 0.5f) / gridH.toFloat()
                for (gx in 0 until gridW) {
                    val normX = (gx + 0.5f) / gridW.toFloat()
                    val (sxNorm, syNorm) = when (normalizedRot) {
                        90 -> Pair(normY, 1f - normX)
                        180 -> Pair(1f - normX, 1f - normY)
                        270 -> Pair(1f - normY, normX)
                        else -> Pair(normX, normY)
                    }
                    val sx = (sxNorm * srcW).toInt().coerceIn(0, srcW - 1)
                    val sy = (syNorm * srcH).toInt().coerceIn(0, srcH - 1)
                    val index = sy * rowStride + sx * pixelStride
                    val yVal = if (index in 0 until limit) {
                        buffer.get(index).toInt() and 0xFF
                    } else {
                        0
                    }
                    lumaGrid[gy * gridW + gx] = yVal
                }
            }

            return analyzeLuminanceGrid(
                width = gridW,
                height = gridH,
                luma = lumaGrid,
                frameSequenceId = frameSequenceId
            )
        }

        // Fallback if planes are unavailable
        val bmp = decodeImageProxyToUprightBitmap(imageProxy)
        if (bmp != null) {
            try {
                return analyzeBitmap(bmp, rotationDegrees = 0, frameSequenceId = frameSequenceId)
            } finally {
                if (!bmp.isRecycled) {
                    bmp.recycle()
                }
            }
        }

        return DocumentFrameAnalysisResult(
            frameSequenceId = frameSequenceId,
            isDocumentDetected = false,
            detectedQuad = null,
            primaryGuidance = CameraQualityGuidance.NO_DOCUMENT_DETECTED,
            isQualityAcceptable = false
        )
    }

    /**
     * Analyzes an Android [Bitmap] (live preview frame or test synthetic notice scene).
     */
    fun analyzeBitmap(
        bitmap: Bitmap,
        rotationDegrees: Int = 0,
        frameSequenceId: Long = System.nanoTime()
    ): DocumentFrameAnalysisResult {
        val srcW = bitmap.width
        val srcH = bitmap.height
        if (srcW <= 4 || srcH <= 4) {
            return DocumentFrameAnalysisResult(
                frameSequenceId = frameSequenceId,
                isDocumentDetected = false,
                detectedQuad = null,
                primaryGuidance = CameraQualityGuidance.NO_DOCUMENT_DETECTED,
                isQualityAcceptable = false
            )
        }

        val normalizedRot = ((rotationDegrees % 360) + 360) % 360
        val gridW = TARGET_GRID_WIDTH
        val gridH = TARGET_GRID_HEIGHT
        val lumaGrid = IntArray(gridW * gridH)

        for (gy in 0 until gridH) {
            val normY = (gy + 0.5f) / gridH.toFloat()
            for (gx in 0 until gridW) {
                val normX = (gx + 0.5f) / gridW.toFloat()
                val (sxNorm, syNorm) = when (normalizedRot) {
                    90 -> Pair(normY, 1f - normX)
                    180 -> Pair(1f - normX, 1f - normY)
                    270 -> Pair(1f - normY, normX)
                    else -> Pair(normX, normY)
                }
                val sx = (sxNorm * srcW).toInt().coerceIn(0, srcW - 1)
                val sy = (syNorm * srcH).toInt().coerceIn(0, srcH - 1)
                val argb = bitmap.getPixel(sx, sy)
                val r = (argb shr 16) and 0xFF
                val g = (argb shr 8) and 0xFF
                val b = argb and 0xFF
                val yVal = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                lumaGrid[gy * gridW + gx] = ((r * 299 + g * 587 + b * 114) / 1000).coerceIn(0, 255)
            }
        }

        return analyzeLuminanceGrid(
            width = gridW,
            height = gridH,
            luma = lumaGrid,
            frameSequenceId = frameSequenceId
        )
    }

    /**
     * Core deterministic computer-vision pipeline on a 2D luminance buffer (`0..255`).
     */
    fun analyzeLuminanceGrid(
        width: Int,
        height: Int,
        luma: IntArray,
        frameSequenceId: Long
    ): DocumentFrameAnalysisResult {
        val totalPixels = width * height
        if (width < 16 || height < 16 || luma.size < totalPixels) {
            return DocumentFrameAnalysisResult(
                frameSequenceId = frameSequenceId,
                isDocumentDetected = false,
                detectedQuad = null,
                primaryGuidance = CameraQualityGuidance.NO_DOCUMENT_DETECTED,
                isQualityAcceptable = false
            )
        }

        // 1. Global brightness and standard deviation
        var sumLuma = 0L
        for (i in 0 until totalPixels) {
            sumLuma += luma[i].coerceIn(0, 255)
        }
        val meanBrightness = sumLuma.toFloat() / totalPixels.toFloat()

        var sumSqDiff = 0.0
        for (i in 0 until totalPixels) {
            val d = luma[i] - meanBrightness
            sumSqDiff += (d * d).toDouble()
        }
        val globalStdDev = sqrt(sumSqDiff / totalPixels.toDouble()).toFloat()

        // 2. Compute 5x5 local maximum envelope so dark printed text lines inside a white/light page
        //    do not fragment the page surface mask.
        val envelope = IntArray(totalPixels)
        for (y in 0 until height) {
            val yMin = max(0, y - 2)
            val yMax = min(height - 1, y + 2)
            for (x in 0 until width) {
                val xMin = max(0, x - 2)
                val xMax = min(width - 1, x + 2)
                var maxVal = 0
                for (ny in yMin..yMax) {
                    val rowOff = ny * width
                    for (nx in xMin..xMax) {
                        val v = luma[rowOff + nx]
                        if (v > maxVal) maxVal = v
                    }
                }
                envelope[y * width + x] = maxVal
            }
        }

        // 3. Estimate background luminance from outer border perimeter (25th percentile)
        //    and candidate page surface luminance from central region (80th percentile).
        val borderSamples = IntArray(2 * width + 2 * height)
        var bCount = 0
        for (x in 0 until width) {
            borderSamples[bCount++] = envelope[x]
            borderSamples[bCount++] = envelope[(height - 1) * width + x]
        }
        for (y in 1 until height - 1) {
            borderSamples[bCount++] = envelope[y * width]
            borderSamples[bCount++] = envelope[y * width + (width - 1)]
        }
        val sortedBorder = borderSamples.copyOf(bCount).also { it.sort() }
        val backgroundLuma = sortedBorder[(bCount * 0.25f).toInt().coerceIn(0, bCount - 1)].toFloat()

        val cX0 = (width * 0.22f).toInt()
        val cX1 = (width * 0.78f).toInt()
        val cY0 = (height * 0.22f).toInt()
        val cY1 = (height * 0.78f).toInt()
        val centerSamples = IntArray((cX1 - cX0 + 1) * (cY1 - cY0 + 1))
        var cCount = 0
        for (y in cY0..cY1) {
            val rowOff = y * width
            for (x in cX0..cX1) {
                centerSamples[cCount++] = envelope[rowOff + x]
            }
        }
        val sortedCenter = centerSamples.copyOf(cCount).also { it.sort() }
        val pageSurfaceLuma = sortedCenter[(cCount * 0.80f).toInt().coerceIn(0, cCount - 1)].toFloat()
        val pageContrast = pageSurfaceLuma - backgroundLuma

        // If the entire scene is uniform/empty without contrast between a page and background:
        if (pageContrast < MIN_PAGE_CONTRAST_DELTA || globalStdDev < 14f) {
            val guidance = if (meanBrightness < MIN_ACCEPTABLE_SCENE_LUMA && pageSurfaceLuma < MIN_ACCEPTABLE_PAGE_LUMA) {
                CameraQualityGuidance.POOR_LIGHTING
            } else {
                CameraQualityGuidance.NO_DOCUMENT_DETECTED
            }
            return DocumentFrameAnalysisResult(
                frameSequenceId = frameSequenceId,
                isDocumentDetected = false,
                detectedQuad = null,
                primaryGuidance = guidance,
                isQualityAcceptable = false,
                meanBrightness = meanBrightness
            )
        }

        // 4. Segment candidate paper mask
        val pageThreshold = (backgroundLuma + pageContrast * 0.45f).toInt()
        val isPaper = BooleanArray(totalPixels)
        var paperPixelCount = 0
        var minPaperX = width
        var maxPaperX = -1
        var minPaperY = height
        var maxPaperY = -1

        for (y in 0 until height) {
            val rowOff = y * width
            for (x in 0 until width) {
                if (envelope[rowOff + x] >= pageThreshold) {
                    isPaper[rowOff + x] = true
                    paperPixelCount++
                    if (x < minPaperX) minPaperX = x
                    if (x > maxPaperX) maxPaperX = x
                    if (y < minPaperY) minPaperY = y
                    if (y > maxPaperY) maxPaperY = y
                }
            }
        }

        val areaRatio = paperPixelCount.toFloat() / totalPixels.toFloat()
        val bboxW = maxPaperX - minPaperX + 1
        val bboxH = maxPaperY - minPaperY + 1

        // Reject tiny specks or full-frame uniform walls (area > 0.96 with all 4 borders covered)
        val touchesLeft = minPaperX <= (width * CUT_OFF_MARGIN_NORM).toInt()
        val touchesRight = maxPaperX >= width - 1 - (width * CUT_OFF_MARGIN_NORM).toInt()
        val touchesTop = minPaperY <= (height * CUT_OFF_MARGIN_NORM).toInt()
        val touchesBottom = maxPaperY >= height - 1 - (height * CUT_OFF_MARGIN_NORM).toInt()
        val touchedSidesCount = (if (touchesLeft) 1 else 0) +
            (if (touchesRight) 1 else 0) +
            (if (touchesTop) 1 else 0) +
            (if (touchesBottom) 1 else 0)

        if (areaRatio < 0.10f || bboxW < width * 0.25f || bboxH < height * 0.22f || (areaRatio > 0.95f && touchedSidesCount == 4)) {
            val guidance = if (meanBrightness < MIN_ACCEPTABLE_SCENE_LUMA) {
                CameraQualityGuidance.POOR_LIGHTING
            } else {
                CameraQualityGuidance.NO_DOCUMENT_DETECTED
            }
            return DocumentFrameAnalysisResult(
                frameSequenceId = frameSequenceId,
                isDocumentDetected = false,
                detectedQuad = null,
                primaryGuidance = guidance,
                isQualityAcceptable = false,
                meanBrightness = meanBrightness
            )
        }

        // 5. Fit top, bottom, left, and right linear boundaries along the central 50% span of the sheet
        val midYStart = (minPaperY + bboxH * 0.25f).toInt().coerceIn(0, height - 1)
        val midYEnd = (minPaperY + bboxH * 0.75f).toInt().coerceIn(midYStart, height - 1)
        val leftPtsX = FloatArray(midYEnd - midYStart + 1)
        val leftPtsY = FloatArray(midYEnd - midYStart + 1)
        val rightPtsX = FloatArray(midYEnd - midYStart + 1)
        val rightPtsY = FloatArray(midYEnd - midYStart + 1)
        var lrValidCount = 0

        for (y in midYStart..midYEnd) {
            val rowOff = y * width
            var firstX = -1
            var lastX = -1
            for (x in 0 until width) {
                if (isPaper[rowOff + x]) {
                    if (firstX == -1) firstX = x
                    lastX = x
                }
            }
            if (firstX != -1 && lastX - firstX >= width * 0.18f) {
                val ny = y.toFloat() / height.toFloat()
                leftPtsY[lrValidCount] = ny
                leftPtsX[lrValidCount] = firstX.toFloat() / width.toFloat()
                rightPtsY[lrValidCount] = ny
                rightPtsX[lrValidCount] = lastX.toFloat() / width.toFloat()
                lrValidCount++
            }
        }

        val midXStart = (minPaperX + bboxW * 0.25f).toInt().coerceIn(0, width - 1)
        val midXEnd = (minPaperX + bboxW * 0.75f).toInt().coerceIn(midXStart, width - 1)
        val topPtsX = FloatArray(midXEnd - midXStart + 1)
        val topPtsY = FloatArray(midXEnd - midXStart + 1)
        val bottomPtsX = FloatArray(midXEnd - midXStart + 1)
        val bottomPtsY = FloatArray(midXEnd - midXStart + 1)
        var tbValidCount = 0

        for (x in midXStart..midXEnd) {
            var firstY = -1
            var lastY = -1
            for (y in 0 until height) {
                if (isPaper[y * width + x]) {
                    if (firstY == -1) firstY = y
                    lastY = y
                }
            }
            if (firstY != -1 && lastY - firstY >= height * 0.18f) {
                val nx = x.toFloat() / width.toFloat()
                topPtsX[tbValidCount] = nx
                topPtsY[tbValidCount] = firstY.toFloat() / height.toFloat()
                bottomPtsX[tbValidCount] = nx
                bottomPtsY[tbValidCount] = lastY.toFloat() / height.toFloat()
                tbValidCount++
            }
        }

        if (lrValidCount < 6 || tbValidCount < 6) {
            return DocumentFrameAnalysisResult(
                frameSequenceId = frameSequenceId,
                isDocumentDetected = false,
                detectedQuad = null,
                primaryGuidance = CameraQualityGuidance.NO_DOCUMENT_DETECTED,
                isQualityAcceptable = false,
                meanBrightness = meanBrightness
            )
        }

        // Fit lines:
        // Top: y = mTop * x + bTop
        // Bottom: y = mBottom * x + bBottom
        // Left: x = mLeft * y + bLeft
        // Right: x = mRight * y + bRight
        val topFit = fitLine(topPtsX, topPtsY, tbValidCount)
        val bottomFit = fitLine(bottomPtsX, bottomPtsY, tbValidCount)
        val leftFit = fitLine(leftPtsY, leftPtsX, lrValidCount)
        val rightFit = fitLine(rightPtsY, rightPtsX, lrValidCount)

        val maxRmsError = max(
            max(topFit.rmsError, bottomFit.rmsError),
            max(leftFit.rmsError, rightFit.rmsError)
        )
        // Reject irregular/non-polygonal shapes whose edges are not straight lines
        if (maxRmsError > 0.055f) {
            return DocumentFrameAnalysisResult(
                frameSequenceId = frameSequenceId,
                isDocumentDetected = false,
                detectedQuad = null,
                primaryGuidance = CameraQualityGuidance.NO_DOCUMENT_DETECTED,
                isQualityAcceptable = false,
                meanBrightness = meanBrightness
            )
        }

        val rawTopLeft = intersectHorizAndVert(topFit.slope, topFit.intercept, leftFit.slope, leftFit.intercept)
        val rawTopRight = intersectHorizAndVert(topFit.slope, topFit.intercept, rightFit.slope, rightFit.intercept)
        val rawBottomRight = intersectHorizAndVert(bottomFit.slope, bottomFit.intercept, rightFit.slope, rightFit.intercept)
        val rawBottomLeft = intersectHorizAndVert(bottomFit.slope, bottomFit.intercept, leftFit.slope, leftFit.intercept)

        val cornerOutOfBounds = isPointNearOrOutsideBorder(rawTopLeft) ||
            isPointNearOrOutsideBorder(rawTopRight) ||
            isPointNearOrOutsideBorder(rawBottomRight) ||
            isPointNearOrOutsideBorder(rawBottomLeft)
        val hasCutOffEdges = touchedSidesCount > 0 || cornerOutOfBounds

        val quad = DetectedDocumentQuad(
            topLeft = clampPoint(rawTopLeft),
            topRight = clampPoint(rawTopRight),
            bottomRight = clampPoint(rawBottomRight),
            bottomLeft = clampPoint(rawBottomLeft),
            confidence = (1f - (maxRmsError / 0.06f)).coerceIn(0.55f, 0.99f),
            areaRatio = areaRatio.coerceIn(0.05f, 0.99f)
        )

        // 6. Compute Tilt / Keystone Angle Metrics
        val topAngleDeg = abs(Math.toDegrees(atan(topFit.slope.toDouble()))).toFloat()
        val bottomAngleDeg = abs(Math.toDegrees(atan(bottomFit.slope.toDouble()))).toFloat()
        val leftAngleDeg = abs(Math.toDegrees(atan(leftFit.slope.toDouble()))).toFloat()
        val rightAngleDeg = abs(Math.toDegrees(atan(rightFit.slope.toDouble()))).toFloat()
        val inPlaneTiltDeg = (topAngleDeg + bottomAngleDeg + leftAngleDeg + rightAngleDeg) * 0.25f

        val topEdgeWidth = hypot(
            (quad.topRight.x - quad.topLeft.x).toDouble(),
            (quad.topRight.y - quad.topLeft.y).toDouble()
        ).toFloat().coerceAtLeast(0.05f)
        val bottomEdgeWidth = hypot(
            (quad.bottomRight.x - quad.bottomLeft.x).toDouble(),
            (quad.bottomRight.y - quad.bottomLeft.y).toDouble()
        ).toFloat().coerceAtLeast(0.05f)
        val leftEdgeHeight = hypot(
            (quad.bottomLeft.x - quad.topLeft.x).toDouble(),
            (quad.bottomLeft.y - quad.topLeft.y).toDouble()
        ).toFloat().coerceAtLeast(0.05f)
        val rightEdgeHeight = hypot(
            (quad.bottomRight.x - quad.topRight.x).toDouble(),
            (quad.bottomRight.y - quad.topRight.y).toDouble()
        ).toFloat().coerceAtLeast(0.05f)

        val horizKeystoneRatio = abs(topEdgeWidth - bottomEdgeWidth) / max(topEdgeWidth, bottomEdgeWidth)
        val vertKeystoneRatio = abs(leftEdgeHeight - rightEdgeHeight) / max(leftEdgeHeight, rightEdgeHeight)
        val keystoneEquivalentDeg = max(horizKeystoneRatio, vertKeystoneRatio) * 65f
        val effectiveTiltDegrees = max(inPlaneTiltDeg, keystoneEquivalentDeg)

        // 7. Compute Interior Lighting, Glare, and Sharpness Metrics
        val innerX0 = (minPaperX + bboxW * 0.12f).toInt().coerceIn(1, width - 2)
        val innerX1 = (maxPaperX - bboxW * 0.12f).toInt().coerceIn(innerX0, width - 2)
        val innerY0 = (minPaperY + bboxH * 0.12f).toInt().coerceIn(1, height - 2)
        val innerY1 = (maxPaperY - bboxH * 0.12f).toInt().coerceIn(innerY0, height - 2)

        var innerPixelCount = 0
        var innerLumaSum = 0L
        var saturatedGlarePixels = 0
        val laplacianValues = IntArray((innerX1 - innerX0 + 1) * (innerY1 - innerY0 + 1))

        for (y in innerY0..innerY1) {
            val rowOff = y * width
            for (x in innerX0..innerX1) {
                val center = luma[rowOff + x]
                innerLumaSum += center
                if (center >= 250) {
                    // Check if surrounding 3x3 patch is also washed out (low local variance -> specular glare)
                    val left = luma[rowOff + x - 1]
                    val right = luma[rowOff + x + 1]
                    val up = luma[(y - 1) * width + x]
                    val down = luma[(y + 1) * width + x]
                    if (left >= 246 && right >= 246 && up >= 246 && down >= 246) {
                        saturatedGlarePixels++
                    }
                }
                val lap = abs(
                    4 * center -
                        luma[rowOff + x - 1] -
                        luma[rowOff + x + 1] -
                        luma[(y - 1) * width + x] -
                        luma[(y + 1) * width + x]
                )
                laplacianValues[innerPixelCount++] = lap
            }
        }

        val pageInteriorLuma = if (innerPixelCount > 0) {
            innerLumaSum.toFloat() / innerPixelCount.toFloat()
        } else {
            pageSurfaceLuma
        }
        val glareRatio = if (innerPixelCount > 0) {
            saturatedGlarePixels.toFloat() / innerPixelCount.toFloat()
        } else {
            0f
        }

        val sharpnessScore = if (innerPixelCount > 0) {
            val sortedLap = laplacianValues.copyOf(innerPixelCount).also { it.sort() }
            val p92 = sortedLap[(innerPixelCount * 0.92f).toInt().coerceIn(0, innerPixelCount - 1)]
            (p92.toFloat() / 4.0f).coerceIn(0f, 100f)
        } else {
            0f
        }

        // 8. Determine Primary Image-Quality Guidance
        val primaryGuidance = when {
            meanBrightness < MIN_ACCEPTABLE_SCENE_LUMA || pageInteriorLuma < MIN_ACCEPTABLE_PAGE_LUMA ->
                CameraQualityGuidance.POOR_LIGHTING
            hasCutOffEdges ->
                CameraQualityGuidance.CUT_OFF_EDGES
            glareRatio >= MAX_ACCEPTABLE_GLARE_RATIO ->
                CameraQualityGuidance.GLARE_DETECTED
            effectiveTiltDegrees >= MAX_ACCEPTABLE_TILT_DEGREES ->
                CameraQualityGuidance.TILTED_OR_ANGLED
            sharpnessScore < MIN_ACCEPTABLE_SHARPNESS_SCORE ->
                CameraQualityGuidance.BLURRY
            else ->
                CameraQualityGuidance.HOLD_STEADY_MOVEMENT
        }

        val isQualityAcceptable = primaryGuidance == CameraQualityGuidance.HOLD_STEADY_MOVEMENT ||
            primaryGuidance == CameraQualityGuidance.READY_LOCKED

        return DocumentFrameAnalysisResult(
            frameSequenceId = frameSequenceId,
            isDocumentDetected = true,
            detectedQuad = quad,
            primaryGuidance = primaryGuidance,
            isQualityAcceptable = isQualityAcceptable,
            meanBrightness = meanBrightness,
            sharpnessScore = sharpnessScore,
            glareRatio = glareRatio,
            tiltDegrees = effectiveTiltDegrees,
            hasCutOffEdges = hasCutOffEdges
        )
    }

    private data class LineFit(
        val slope: Float,
        val intercept: Float,
        val rmsError: Float
    )

    private fun fitLine(indep: FloatArray, dep: FloatArray, count: Int): LineFit {
        if (count <= 1) return LineFit(0f, dep.firstOrNull() ?: 0f, 0f)
        var sumX = 0.0
        var sumY = 0.0
        for (i in 0 until count) {
            sumX += indep[i]
            sumY += dep[i]
        }
        val meanX = sumX / count
        val meanY = sumY / count
        var num = 0.0
        var den = 0.0
        for (i in 0 until count) {
            val dx = indep[i] - meanX
            val dy = dep[i] - meanY
            num += dx * dy
            den += dx * dx
        }
        val slope = if (den > 1e-6) (num / den).toFloat() else 0f
        val intercept = (meanY - slope * meanX).toFloat()

        var sqErr = 0.0
        for (i in 0 until count) {
            val pred = slope * indep[i] + intercept
            val diff = dep[i] - pred
            sqErr += (diff * diff).toDouble()
        }
        val rms = sqrt(sqErr / count).toFloat()
        return LineFit(slope = slope, intercept = intercept, rmsError = rms)
    }

    /**
     * Intersects horizontal-like line `y = mH * x + bH` with vertical-like line `x = mV * y + bV`.
     */
    private fun intersectHorizAndVert(mH: Float, bH: Float, mV: Float, bV: Float): NormalizedPoint {
        val denom = 1f - mV * mH
        if (abs(denom) < 1e-4f) {
            return NormalizedPoint(x = bV, y = bH)
        }
        val x = (mV * bH + bV) / denom
        val y = mH * x + bH
        return NormalizedPoint(x = x, y = y)
    }

    private fun isPointNearOrOutsideBorder(pt: NormalizedPoint): Boolean {
        return pt.x <= CUT_OFF_MARGIN_NORM ||
            pt.x >= 1f - CUT_OFF_MARGIN_NORM ||
            pt.y <= CUT_OFF_MARGIN_NORM ||
            pt.y >= 1f - CUT_OFF_MARGIN_NORM
    }

    private fun clampPoint(pt: NormalizedPoint): NormalizedPoint {
        return NormalizedPoint(
            x = pt.x.coerceIn(0.01f, 0.99f),
            y = pt.y.coerceIn(0.01f, 0.99f)
        )
    }
}

/**
 * Temporal Stability & Boundary Smoothing Tracker for Phase 8 Camera Scanning.
 *
 * - Smooths detected quadrilateral corners via Exponential Moving Average (EMA) so the visible
 *   boundary indicator remains calm and readable without high-frequency jitter.
 * - Detects hand/document movement between consecutive frames (`maxCornerDisplacement > MAX_STABLE_CORNER_DELTA`)
 *   and resets stability progress if the user moves the camera.
 * - Locks (`isStableLocked = true`, `qualityGuidance = READY_LOCKED`) only after
 *   `requiredStableFrames` consecutive frames with acceptable image quality and minimal movement.
 */
object NoticeDocumentStabilityTracker {

    const val MAX_STABLE_CORNER_DELTA = 0.032f
    private const val SMOOTHING_ALPHA = 0.55f

    fun updateDetectionState(
        previous: DocumentDetectionUiState,
        frame: DocumentFrameAnalysisResult,
        requiredStableFrames: Int = previous.requiredStableFrames.coerceAtLeast(1)
    ): DocumentDetectionUiState {
        if (!frame.isDocumentDetected || frame.detectedQuad == null) {
            return DocumentDetectionUiState(
                frameSequenceId = frame.frameSequenceId,
                isDocumentDetected = false,
                detectedQuad = null,
                qualityGuidance = frame.primaryGuidance,
                isQualityAcceptable = false,
                consecutiveStableFrames = 0,
                requiredStableFrames = requiredStableFrames,
                stabilityProgress = 0f,
                isStableLocked = false,
                meanBrightness = frame.meanBrightness,
                sharpnessScore = frame.sharpnessScore,
                glareRatio = frame.glareRatio,
                tiltDegrees = frame.tiltDegrees
            )
        }

        val currentQuad = frame.detectedQuad
        val prevQuad = previous.detectedQuad

        val maxCornerDelta = if (prevQuad != null && previous.isDocumentDetected) {
            maxCornerDistance(prevQuad, currentQuad)
        } else {
            0f
        }

        val isMovedSignificantly = prevQuad != null &&
            previous.isDocumentDetected &&
            maxCornerDelta > MAX_STABLE_CORNER_DELTA

        val smoothedQuad = if (prevQuad != null && previous.isDocumentDetected && maxCornerDelta <= 0.08f) {
            blendQuads(prevQuad, currentQuad, SMOOTHING_ALPHA)
        } else {
            currentQuad
        }

        if (isMovedSignificantly) {
            return DocumentDetectionUiState(
                frameSequenceId = frame.frameSequenceId,
                isDocumentDetected = true,
                detectedQuad = currentQuad,
                qualityGuidance = if (frame.isQualityAcceptable) {
                    CameraQualityGuidance.HOLD_STEADY_MOVEMENT
                } else {
                    frame.primaryGuidance
                },
                isQualityAcceptable = frame.isQualityAcceptable,
                consecutiveStableFrames = 0,
                requiredStableFrames = requiredStableFrames,
                stabilityProgress = 0f,
                isStableLocked = false,
                meanBrightness = frame.meanBrightness,
                sharpnessScore = frame.sharpnessScore,
                glareRatio = frame.glareRatio,
                tiltDegrees = frame.tiltDegrees
            )
        }

        if (!frame.isQualityAcceptable) {
            return DocumentDetectionUiState(
                frameSequenceId = frame.frameSequenceId,
                isDocumentDetected = true,
                detectedQuad = smoothedQuad,
                qualityGuidance = frame.primaryGuidance,
                isQualityAcceptable = false,
                consecutiveStableFrames = 0,
                requiredStableFrames = requiredStableFrames,
                stabilityProgress = 0f,
                isStableLocked = false,
                meanBrightness = frame.meanBrightness,
                sharpnessScore = frame.sharpnessScore,
                glareRatio = frame.glareRatio,
                tiltDegrees = frame.tiltDegrees
            )
        }

        val nextStableFrames = (previous.consecutiveStableFrames + 1).coerceAtMost(requiredStableFrames)
        val progress = (nextStableFrames.toFloat() / requiredStableFrames.toFloat()).coerceIn(0f, 1f)
        val isLocked = nextStableFrames >= requiredStableFrames

        return DocumentDetectionUiState(
            frameSequenceId = frame.frameSequenceId,
            isDocumentDetected = true,
            detectedQuad = smoothedQuad,
            qualityGuidance = if (isLocked) {
                CameraQualityGuidance.READY_LOCKED
            } else {
                CameraQualityGuidance.HOLD_STEADY_MOVEMENT
            },
            isQualityAcceptable = true,
            consecutiveStableFrames = nextStableFrames,
            requiredStableFrames = requiredStableFrames,
            stabilityProgress = progress,
            isStableLocked = isLocked,
            meanBrightness = frame.meanBrightness,
            sharpnessScore = frame.sharpnessScore,
            glareRatio = frame.glareRatio,
            tiltDegrees = frame.tiltDegrees
        )
    }

    internal fun maxCornerDistance(a: DetectedDocumentQuad, b: DetectedDocumentQuad): Float {
        val dTL = hypot((a.topLeft.x - b.topLeft.x).toDouble(), (a.topLeft.y - b.topLeft.y).toDouble()).toFloat()
        val dTR = hypot((a.topRight.x - b.topRight.x).toDouble(), (a.topRight.y - b.topRight.y).toDouble()).toFloat()
        val dBR = hypot((a.bottomRight.x - b.bottomRight.x).toDouble(), (a.bottomRight.y - b.bottomRight.y).toDouble()).toFloat()
        val dBL = hypot((a.bottomLeft.x - b.bottomLeft.x).toDouble(), (a.bottomLeft.y - b.bottomLeft.y).toDouble()).toFloat()
        return max(max(dTL, dTR), max(dBR, dBL))
    }

    private fun blendQuads(
        prev: DetectedDocumentQuad,
        curr: DetectedDocumentQuad,
        alpha: Float
    ): DetectedDocumentQuad {
        fun lerp(a: Float, b: Float) = a + (b - a) * alpha
        fun lerpPt(p1: NormalizedPoint, p2: NormalizedPoint) = NormalizedPoint(
            x = lerp(p1.x, p2.x),
            y = lerp(p1.y, p2.y)
        )
        return DetectedDocumentQuad(
            topLeft = lerpPt(prev.topLeft, curr.topLeft),
            topRight = lerpPt(prev.topRight, curr.topRight),
            bottomRight = lerpPt(prev.bottomRight, curr.bottomRight),
            bottomLeft = lerpPt(prev.bottomLeft, curr.bottomLeft),
            confidence = lerp(prev.confidence, curr.confidence),
            areaRatio = lerp(prev.areaRatio, curr.areaRatio)
        )
    }
}
