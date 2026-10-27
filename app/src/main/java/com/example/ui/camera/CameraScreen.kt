package com.example.ui.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import android.view.OrientationEventListener
import android.view.Surface
import android.view.ViewGroup
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.model.CameraQualityGuidance
import com.example.data.model.CameraScanUiState
import com.example.data.model.CameraSessionStage
import com.example.data.model.DetectedDocumentQuad
import com.example.data.model.DocumentFrameAnalysisResult
import com.example.data.model.NoticeLanguage
import com.example.ui.components.GlassButton
import com.example.ui.components.GlassCard
import com.example.ui.components.GlassIconBadge
import com.example.ui.components.NoticeBuddyAccentPill
import com.example.ui.theme.LocalGlassPalette
import com.example.ui.theme.NotoSansDevanagariFontFamily
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Phase 7 & 8 CameraX Live Preview, Intelligent Document-Edge Detection, Image-Quality Guidance,
 * Auto-Capture, and Manual Capture Screen integrated with NoticeBuddy's glassmorphism design system.
 *
 * Responsibilities:
 * - Binds CameraX [Preview], [ImageCapture], and [ImageAnalysis] to [LocalLifecycleOwner] and cleanly
 *   unbinds all use cases, clears analyzers, and shuts down executors on disposal/navigation away.
 * - Analyzes live preview frames via [NoticeDocumentEdgeAnalyzer] for document boundaries, lighting,
 *   glare, cut-off edges, tilt/angle, blur, and temporal stability.
 * - Renders a visible, smoothed boundary indicator ONLY when a document is genuinely detected.
 * - Triggers auto-capture once when the document is stable and quality is acceptable (without repeating
 *   on the same frame or while a capture is in flight).
 * - Provides a prominent Auto-Capture toggle (`Auto: ON` / `Auto: OFF`) and clear manual-capture fallback.
 */
@Composable
fun NoticeBuddyCameraPreviewScreen(
    cameraState: CameraScanUiState,
    selectedLanguage: NoticeLanguage,
    onCameraInitializing: () -> Unit,
    onCameraBound: (isFlashSupported: Boolean) -> Unit,
    onCameraUnbound: () -> Unit,
    onCameraError: (String) -> Unit,
    onRetryCamera: () -> Unit,
    onToggleFlash: () -> Unit,
    onToggleAutoCapture: () -> Unit = {},
    onSwitchLens: () -> Unit,
    onDeviceRotationChanged: (Int) -> Unit,
    onFrameAnalyzed: (DocumentFrameAnalysisResult) -> Boolean = { false },
    onManualCaptureStarted: () -> Boolean,
    onPhotoCaptured: (Bitmap, Int) -> Unit,
    onCaptureFailed: (String) -> Unit,
    onOpenGallery: () -> Unit,
    onClose: () -> Unit,
    onReturnToReview: () -> Unit = {},
    enableHardwareCameraBinding: Boolean = true,
    customCaptureTrigger: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val glass = LocalGlassPalette.current
    val isHindi = selectedLanguage == NoticeLanguage.HINDI

    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val frameIdGenerator = remember { AtomicLong(1L) }
    var boundCamera by remember { mutableStateOf<Camera?>(null) }
    val currentOnFrameAnalyzed by rememberUpdatedState(onFrameAnalyzed)
    val currentCameraState by rememberUpdatedState(cameraState)

    val previewView = remember(context) {
        PreviewView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()
    }

    val executeHardwareOrCustomCapture: () -> Unit = remember(
        customCaptureTrigger,
        imageCapture,
        cameraExecutor,
        context
    ) {
        {
            if (customCaptureTrigger != null) {
                customCaptureTrigger.invoke()
            } else {
                imageCapture.takePicture(
                    cameraExecutor,
                    object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            val imgRotation = image.imageInfo.rotationDegrees
                            val bitmap = decodeImageProxyToUprightBitmap(image)
                            image.close()
                            ContextCompat.getMainExecutor(context).execute {
                                if (bitmap != null) {
                                    onPhotoCaptured(bitmap, imgRotation)
                                } else {
                                    onCaptureFailed("Could not decode captured notice photo. Please try again.")
                                }
                            }
                        }

                        override fun onError(exception: ImageCaptureException) {
                            Log.e("CameraScreen", "Photo capture failed", exception)
                            ContextCompat.getMainExecutor(context).execute {
                                onCaptureFailed(
                                    "Capture failed: ${exception.localizedMessage ?: "Please hold steady and try again."}"
                                )
                            }
                        }
                    }
                )
            }
        }
    }

    // Execute capture when Auto-Capture transitions sessionStage to CAPTURING
    var lastHandledAutoCaptureCount by remember { mutableIntStateOf(cameraState.autoCaptureTriggeredCount) }
    LaunchedEffect(cameraState.autoCaptureTriggeredCount) {
        if (cameraState.autoCaptureTriggeredCount > lastHandledAutoCaptureCount) {
            lastHandledAutoCaptureCount = cameraState.autoCaptureTriggeredCount
            if (cameraState.sessionStage == CameraSessionStage.CAPTURING && cameraState.lastCaptureWasAuto) {
                executeHardwareOrCustomCapture()
            }
        }
    }

    // Track device physical orientation changes so captured notices have upright targetRotation
    DisposableEffect(context) {
        val orientationListener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val snappedDegrees = when (orientation) {
                    in 45..134 -> 90
                    in 135..224 -> 180
                    in 225..314 -> 270
                    else -> 0
                }
                onDeviceRotationChanged(snappedDegrees)
            }
        }
        runCatching {
            if (orientationListener.canDetectOrientation()) {
                orientationListener.enable()
            }
        }
        onDispose {
            runCatching { orientationListener.disable() }
        }
    }

    // Update ImageCapture targetRotation when device rotation state updates
    LaunchedEffect(cameraState.deviceRotationDegrees) {
        val surfaceRotation = degreesToSurfaceRotation(cameraState.deviceRotationDegrees)
        runCatching {
            imageCapture.targetRotation = surfaceRotation
        }
    }

    // Synchronize torch & flashMode when flash state changes on a bound camera
    LaunchedEffect(cameraState.isFlashEnabled, cameraState.isFlashSupported, boundCamera) {
        val activeCamera = boundCamera ?: return@LaunchedEffect
        val shouldEnableFlash = cameraState.isFlashSupported && cameraState.isFlashEnabled
        runCatching {
            if (activeCamera.cameraInfo.hasFlashUnit()) {
                activeCamera.cameraControl.enableTorch(shouldEnableFlash)
            }
            imageCapture.flashMode = if (shouldEnableFlash) {
                ImageCapture.FLASH_MODE_ON
            } else {
                ImageCapture.FLASH_MODE_OFF
            }
        }
    }

    // Bind CameraX Preview + ImageCapture + ImageAnalysis to lifecycleOwner and guarantee unbind on dispose
    DisposableEffect(lifecycleOwner, cameraState.lensFacing, cameraState.bindGeneration, enableHardwareCameraBinding) {
        var isDisposed = false
        var resolvedProvider: ProcessCameraProvider? = null
        var previewUseCase: Preview? = null
        var analysisUseCase: ImageAnalysis? = null

        if (enableHardwareCameraBinding) {
            onCameraInitializing()
            try {
                val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                cameraProviderFuture.addListener({
                    if (isDisposed) return@addListener
                    try {
                        val cameraProvider = cameraProviderFuture.get()
                        resolvedProvider = cameraProvider
                        val surfaceRotation = degreesToSurfaceRotation(cameraState.deviceRotationDegrees)
                        val preview = Preview.Builder()
                            .setTargetRotation(surfaceRotation)
                            .build()
                            .also {
                                it.surfaceProvider = previewView.surfaceProvider
                            }
                        previewUseCase = preview
                        imageCapture.targetRotation = surfaceRotation

                        var lastAnalyzedElapsedMs = 0L
                        val imageAnalysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                            .setTargetRotation(surfaceRotation)
                            .build()
                            .also { analysis ->
                                analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                                    try {
                                        val nowMs = SystemClock.elapsedRealtime()
                                        val stateSnapshot = currentCameraState
                                        val canAnalyze = !isDisposed &&
                                            stateSnapshot.isCameraBound &&
                                            stateSnapshot.sessionStage == CameraSessionStage.PREVIEW_READY &&
                                            stateSnapshot.capturedPhoto == null &&
                                            (nowMs - lastAnalyzedElapsedMs >= 120L)

                                        if (canAnalyze) {
                                            lastAnalyzedElapsedMs = nowMs
                                            val frameId = frameIdGenerator.incrementAndGet()
                                            val result = NoticeDocumentEdgeAnalyzer.analyzeImageProxy(
                                                imageProxy = imageProxy,
                                                frameSequenceId = frameId
                                            )
                                            ContextCompat.getMainExecutor(context).execute {
                                                if (!isDisposed) {
                                                    currentOnFrameAnalyzed(result)
                                                }
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.w("CameraScreen", "Preview frame analysis skipped", e)
                                    } finally {
                                        imageProxy.close()
                                    }
                                }
                            }
                        analysisUseCase = imageAnalysis

                        val requestedLens = if (cameraState.lensFacing == CameraScanUiState.LENS_FACING_FRONT) {
                            CameraSelector.LENS_FACING_FRONT
                        } else {
                            CameraSelector.LENS_FACING_BACK
                        }
                        val preferredSelector = CameraSelector.Builder()
                            .requireLensFacing(requestedLens)
                            .build()

                        cameraProvider.unbindAll()
                        val activeSelector = if (runCatching { cameraProvider.hasCamera(preferredSelector) }.getOrDefault(true)) {
                            preferredSelector
                        } else {
                            CameraSelector.DEFAULT_BACK_CAMERA
                        }

                        val camera = try {
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                activeSelector,
                                preview,
                                imageCapture,
                                imageAnalysis
                            )
                        } catch (bindThreeErr: Exception) {
                            Log.w("CameraScreen", "Falling back to Preview + ImageCapture binding", bindThreeErr)
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                activeSelector,
                                preview,
                                imageCapture
                            )
                        }
                        boundCamera = camera
                        val hasFlash = runCatching { camera.cameraInfo.hasFlashUnit() }.getOrDefault(false)
                        if (hasFlash && cameraState.isFlashEnabled) {
                            runCatching { camera.cameraControl.enableTorch(true) }
                            imageCapture.flashMode = ImageCapture.FLASH_MODE_ON
                        } else {
                            imageCapture.flashMode = ImageCapture.FLASH_MODE_OFF
                        }
                        onCameraBound(hasFlash)
                    } catch (e: Exception) {
                        Log.e("CameraScreen", "Failed to bind CameraX use cases", e)
                        boundCamera = null
                        onCameraError(
                            "Camera unavailable: ${e.localizedMessage ?: "Unable to start camera preview."}"
                        )
                    }
                }, ContextCompat.getMainExecutor(context))
            } catch (e: Exception) {
                Log.e("CameraScreen", "Failed to request ProcessCameraProvider", e)
                onCameraError(
                    "Camera initialization failed: ${e.localizedMessage ?: "Camera service unavailable."}"
                )
            }
        }

        onDispose {
            isDisposed = true
            runCatching {
                analysisUseCase?.clearAnalyzer()
            }
            analysisUseCase = null
            runCatching {
                boundCamera?.cameraControl?.enableTorch(false)
            }
            boundCamera = null
            runCatching {
                previewUseCase?.surfaceProvider = null
                resolvedProvider?.unbindAll()
            }
            onCameraUnbound()
        }
    }

    // Shutdown capture executor when composable leaves composition completely
    DisposableEffect(Unit) {
        onDispose {
            runCatching { cameraExecutor.shutdown() }
        }
    }

    val isCapturing = cameraState.sessionStage == CameraSessionStage.CAPTURING
    val isInitializing = cameraState.sessionStage == CameraSessionStage.INITIALIZING
    val isError = cameraState.sessionStage == CameraSessionStage.CAMERA_ERROR ||
        cameraState.cameraErrorMessage != null
    val detection = cameraState.detectionState
    val hasValidDetectedBoundary = detection.isDocumentDetected && detection.detectedQuad != null

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0B1120))
            .testTag("camera_screen")
    ) {
        // 1. Live CameraX Preview Surface
        AndroidView(
            factory = { previewView },
            modifier = Modifier
                .fillMaxSize()
                .testTag("camerax_preview_view")
        )

        // 2. Document-Guide Frame & Phase 8 Dynamic Detected Boundary Indicator Overlay
        DocumentGuideFrameOverlay(
            isFlashActive = cameraState.isFlashSupported && cameraState.isFlashEnabled,
            detectedQuad = if (hasValidDetectedBoundary) detection.detectedQuad else null,
            qualityGuidance = detection.qualityGuidance,
            isStableLocked = detection.isStableLocked,
            stabilityProgress = detection.stabilityProgress,
            modifier = Modifier
                .fillMaxSize()
                .testTag("document_guide_frame_overlay")
        )

        // Expose a semantic testTag node ONLY when a document boundary is genuinely detected
        if (hasValidDetectedBoundary) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("camerax_detected_boundary_indicator")
            )
        }

        // 3. Top Glassmorphic Controls Bar (Close, Flash, Auto-Capture Toggle, Lens Switch, Quality Banner)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .testTag("camerax_top_controls_bar"),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Close / Back Button (48dp touch target)
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color(0xCC0F172A))
                        .border(1.dp, Color.White.copy(alpha = 0.28f), CircleShape)
                        .testTag("camerax_close_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = if (isHindi) "कैमरा बंद करें" else "Close camera",
                        tint = Color.White
                    )
                }

                // Center Control Pills: Flash + Auto-Capture Toggle
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Flash Toggle Pill (Supports flash where available, clearly indicates when unsupported)
                    if (cameraState.isFlashSupported) {
                        val flashActive = cameraState.isFlashEnabled
                        Surface(
                            color = if (flashActive) Color(0xFFD97706) else Color(0xCC0F172A),
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(24.dp))
                                .border(
                                    width = 1.dp,
                                    color = if (flashActive) Color(0xFFFBBF24) else Color.White.copy(alpha = 0.28f),
                                    shape = RoundedCornerShape(24.dp)
                                )
                                .clickable(
                                    enabled = cameraState.isCameraBound && !isCapturing,
                                    onClick = onToggleFlash
                                )
                                .semantics(mergeDescendants = true) {}
                                .testTag("camerax_flash_toggle_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (flashActive) Icons.Default.FlashOn else Icons.Default.FlashOff,
                                    contentDescription = if (flashActive) "Flash On" else "Flash Off",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (flashActive) {
                                        if (isHindi) "फ्लैश: चालू" else "Flash: ON"
                                    } else {
                                        if (isHindi) "फ्लैश: बंद" else "Flash: OFF"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    } else {
                        Surface(
                            color = Color(0x990F172A),
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(24.dp))
                                .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(24.dp))
                                .semantics(mergeDescendants = true) {}
                                .testTag("camerax_flash_unsupported_badge")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FlashOff,
                                    contentDescription = "Flash not supported on this lens",
                                    tint = Color.White.copy(alpha = 0.6f),
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (isHindi) "फ्लैश उपलब्ध नहीं" else "Flash Unavailable",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(alpha = 0.7f),
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    // Phase 8 Auto-Capture Toggle Pill (>= 48dp touch target)
                    val autoEnabled = cameraState.isAutoCaptureEnabled
                    Surface(
                        color = if (autoEnabled) Color(0xCC0D9488) else Color(0xCC0F172A),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .border(
                                width = 1.dp,
                                color = if (autoEnabled) Color(0xFF2DD4BF) else Color.White.copy(alpha = 0.28f),
                                shape = RoundedCornerShape(24.dp)
                            )
                            .clickable(
                                enabled = !isCapturing,
                                onClick = onToggleAutoCapture
                            )
                            .semantics(mergeDescendants = true) {}
                            .testTag("camerax_auto_capture_toggle_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = if (autoEnabled) "Auto-capture enabled" else "Auto-capture disabled",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (autoEnabled) {
                                    if (isHindi) "ऑटो: चालू" else "Auto: ON"
                                } else {
                                    if (isHindi) "ऑटो: बंद" else "Auto: OFF"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Switch Camera Lens Button (48dp touch target)
                IconButton(
                    onClick = onSwitchLens,
                    enabled = !isCapturing,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color(0xCC0F172A))
                        .border(1.dp, Color.White.copy(alpha = 0.28f), CircleShape)
                        .testTag("camerax_switch_lens_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Cameraswitch,
                        contentDescription = if (isHindi) "कैमरा बदलें" else "Switch camera lens",
                        tint = Color.White
                    )
                }
            }

            // Phase 8 Live Image-Quality & Document-Framing Guidance Banner
            val guidance = detection.qualityGuidance
            val bannerBorderColor = when {
                detection.isStableLocked -> Color(0xFF10B981)
                guidance.isWarning -> Color(0xFFF59E0B)
                detection.isDocumentDetected -> Color(0xFF2DD4BF)
                else -> Color.White.copy(alpha = 0.22f)
            }
            val badgeAccentColor = when {
                detection.isStableLocked -> Color(0xFF34D399)
                guidance.isWarning -> Color(0xFFFBBF24)
                detection.isDocumentDetected -> glass.tealAccent
                else -> Color(0xFF93C5FD)
            }
            val badgeBgColor = when {
                detection.isStableLocked -> Color(0xFF064E3B)
                guidance.isWarning -> Color(0xFF78350F)
                detection.isDocumentDetected -> Color(0xFF134E4A)
                else -> Color(0xFF1E3A8A)
            }

            Surface(
                color = Color(0xD90F172A),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .fillMaxWidth()
                    .widthIn(max = 500.dp)
                    .border(1.2.dp, bannerBorderColor, RoundedCornerShape(16.dp))
                    .testTag("camerax_framing_helper_banner")
            ) {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                        .testTag("camerax_quality_guidance_banner"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.weight(1f, fill = false)
                        ) {
                            Icon(
                                imageVector = when {
                                    detection.isStableLocked -> Icons.Default.CheckCircle
                                    guidance.isWarning -> Icons.Default.WarningAmber
                                    else -> Icons.Default.AutoAwesome
                                },
                                contentDescription = null,
                                tint = badgeAccentColor,
                                modifier = Modifier.size(16.dp)
                            )
                            NoticeBuddyAccentPill(
                                text = guidance.displayBadge(selectedLanguage),
                                accentColor = badgeAccentColor,
                                backgroundColor = badgeBgColor,
                                modifier = Modifier
                                    .semantics(mergeDescendants = true) {}
                                    .testTag("camerax_quality_guidance_badge")
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (detection.isDocumentDetected && detection.isQualityAcceptable) {
                                val pct = (detection.stabilityProgress * 100f).toInt().coerceIn(0, 100)
                                NoticeBuddyAccentPill(
                                    text = "$pct%",
                                    accentColor = Color.White,
                                    backgroundColor = Color(0xFF0F766E),
                                    modifier = Modifier
                                        .semantics(mergeDescendants = true) {}
                                        .testTag("camerax_stability_progress")
                                )
                            }
                            if (cameraState.deviceRotationDegrees != 0) {
                                NoticeBuddyAccentPill(
                                    text = "${cameraState.deviceRotationDegrees}°",
                                    accentColor = glass.tealAccent,
                                    backgroundColor = Color(0xFF134E4A),
                                    modifier = Modifier
                                        .semantics(mergeDescendants = true) {}
                                        .testTag("camerax_rotation_badge")
                                )
                            }
                        }
                    }

                    Text(
                        text = guidance.displayGuidance(selectedLanguage),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = NotoSansDevanagariFontFamily
                        ),
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.testTag("camerax_quality_guidance_text")
                    )

                    if (detection.isDocumentDetected && detection.isQualityAcceptable) {
                        LinearProgressIndicator(
                            progress = { detection.stabilityProgress.coerceIn(0f, 1f) },
                            color = if (detection.isStableLocked) Color(0xFF10B981) else Color(0xFF2DD4BF),
                            trackColor = Color.White.copy(alpha = 0.16f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                        )
                    }

                    // Clear Manual-Capture Fallback Pill when Auto-Capture is waiting on detection or quality
                    val canTriggerFallback = !isCapturing &&
                        !isInitializing &&
                        !isError &&
                        (cameraState.isCameraBound || customCaptureTrigger != null)
                    if (canTriggerFallback && (!cameraState.isAutoCaptureEnabled || !detection.isStableLocked)) {
                        Surface(
                            color = Color(0xCC1E293B),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                                .clickable {
                                    if (onManualCaptureStarted()) {
                                        executeHardwareOrCustomCapture()
                                    }
                                }
                                .semantics(mergeDescendants = true) {}
                                .testTag("camerax_manual_fallback_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CameraAlt,
                                    contentDescription = null,
                                    tint = Color(0xFF5EEAD4),
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = if (isHindi) {
                                        "अभी मैनुअल फोटो खींचें"
                                    } else {
                                        "Manual Capture Now"
                                    },
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = NotoSansDevanagariFontFamily
                                    ),
                                    color = Color(0xFF5EEAD4),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }

        // 4. Center Initializing or Error State Card
        if (isInitializing && !isError) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 28.dp)
                    .testTag("camerax_loading_indicator"),
                contentAlignment = Alignment.Center
            ) {
                GlassCard(
                    elevated = true,
                    contentPadding = PaddingValues(20.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        CircularProgressIndicator(
                            color = glass.electricBlue,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(28.dp)
                        )
                        Column {
                            Text(
                                text = if (isHindi) "कैमरा तैयार हो रहा है..." else "Starting Camera Preview...",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = glass.textPrimary
                            )
                            Text(
                                text = if (isHindi) {
                                    "कृपया प्रतीक्षा करें"
                                } else {
                                    "Preparing lens for notice scanning"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = glass.textSecondary
                            )
                        }
                    }
                }
            }
        }

        if (isError) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 24.dp)
                    .testTag("camerax_error_card"),
                contentAlignment = Alignment.Center
            ) {
                GlassCard(
                    elevated = true,
                    contentPadding = PaddingValues(20.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 420.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        GlassIconBadge(
                            icon = Icons.Default.ErrorOutline,
                            contentDescription = "Camera error",
                            size = 44.dp,
                            accentTint = Color(0xFFEF4444)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isHindi) "कैमरा त्रुटि" else "Camera Issue",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = glass.textPrimary
                            )
                            Text(
                                text = cameraState.cameraErrorMessage
                                    ?: if (isHindi) "कैमरा शुरू नहीं हो सका।" else "Camera preview could not be started.",
                                style = MaterialTheme.typography.bodySmall,
                                color = glass.textSecondary,
                                modifier = Modifier.testTag("camerax_error_message")
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        GlassButton(
                            text = if (isHindi) "पुनः प्रयास करें" else "Retry Camera",
                            icon = Icons.Default.Refresh,
                            isPrimary = false,
                            onClick = onRetryCamera,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("camerax_retry_button")
                        )
                        GlassButton(
                            text = if (isHindi) "गैलरी से चुनें" else "Pick Gallery",
                            icon = Icons.Default.Image,
                            isPrimary = true,
                            onClick = onOpenGallery,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("camerax_error_gallery_button")
                        )
                    }
                }
            }
        }

        // 5. Bottom Glassmorphic Shutter & Gallery Deck
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Color(0xDD0B1120))
                .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(vertical = 18.dp, horizontal = 20.dp)
                .testTag("camerax_bottom_deck"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (cameraState.scannedPages.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 460.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val retakeIndex = cameraState.scannedPages.indexOfFirst { it.pageId == cameraState.retakingPageId }
                    NoticeBuddyAccentPill(
                        text = if (retakeIndex >= 0) {
                            if (isHindi) "पृष्ठ ${retakeIndex + 1} पुनः कैप्चर" else "Retaking Page ${retakeIndex + 1} of ${cameraState.pageCount}"
                        } else {
                            if (isHindi) "${cameraState.pageCount} पृष्ठ स्कैन किए गए" else "${cameraState.pageCount} Page(s) Scanned • Capturing Page ${cameraState.pageCount + 1}"
                        },
                        accentColor = Color(0xFF2DD4BF),
                        backgroundColor = Color(0xFF134E4A),
                        modifier = Modifier
                            .semantics(mergeDescendants = true) {}
                            .testTag("camerax_preview_page_counter_badge")
                    )

                    Surface(
                        color = Color(0xCC0D9488),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .border(1.dp, Color(0xFF2DD4BF), RoundedCornerShape(14.dp))
                            .clickable(enabled = !isCapturing, onClick = onReturnToReview)
                            .semantics(mergeDescendants = true) {}
                            .testTag("camerax_open_review_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = if (isHindi) {
                                    "समीक्षा (${cameraState.pageCount})"
                                } else {
                                    "Review Pages (${cameraState.pageCount})"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 460.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Gallery Access Button (>= 48dp touch target)
                Surface(
                    color = Color(0xCC1E293B),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .heightIn(min = 52.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.28f), RoundedCornerShape(18.dp))
                        .clickable(enabled = !isCapturing, onClick = onOpenGallery)
                        .testTag("camerax_gallery_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Image,
                            contentDescription = if (isHindi) "गैलरी से फोटो चुनें" else "Choose from gallery",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = if (isHindi) "गैलरी" else "Gallery",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }

                // Manual Capture Shutter Button (76dp touch target — always available as manual fallback)
                val canCapture = !isCapturing && (cameraState.isCameraBound || customCaptureTrigger != null)
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(CircleShape)
                        .border(
                            width = 4.dp,
                            color = if (detection.isStableLocked) Color(0xFF10B981) else Color.White,
                            shape = CircleShape
                        )
                        .padding(6.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                isCapturing -> Color(0xFF475569)
                                detection.isStableLocked -> Color(0xFF059669)
                                canCapture -> glass.electricBlue
                                else -> Color(0xFF64748B)
                            }
                        )
                        .clickable(enabled = canCapture) {
                            if (!onManualCaptureStarted()) return@clickable
                            executeHardwareOrCustomCapture()
                        }
                        .testTag("camerax_shutter_button"),
                    contentAlignment = Alignment.Center
                ) {
                    if (isCapturing) {
                        CircularProgressIndicator(
                            color = Color.White,
                            strokeWidth = 2.5.dp,
                            modifier = Modifier
                                .size(28.dp)
                                .testTag("camerax_capture_loading_indicator")
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = if (isHindi) "नोटिस फोटो खींचें" else "Capture notice photo",
                            tint = Color.White,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                }

                // Active Lens Status / Flip Shortcut (>= 48dp touch target)
                Surface(
                    color = Color(0xCC1E293B),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .heightIn(min = 52.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(18.dp))
                        .clickable(enabled = !isCapturing, onClick = onSwitchLens)
                        .testTag("camerax_bottom_flip_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Cameraswitch,
                            contentDescription = if (isHindi) "लेंस बदलें" else "Flip lens",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = if (cameraState.lensFacing == CameraScanUiState.LENS_FACING_FRONT) {
                                if (isHindi) "फ्रंट" else "Front"
                            } else {
                                if (isHindi) "बैक" else "Rear"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    }
                }
            }

            Text(
                text = when {
                    isCapturing -> if (cameraState.lastCaptureWasAuto) {
                        if (isHindi) "ऑटो-कैप्चर हो रहा है..." else "Auto-capturing stable notice..."
                    } else {
                        if (isHindi) "नोटिस फोटो कैप्चर हो रही है..." else "Capturing notice photo..."
                    }
                    isInitializing -> if (isHindi) "कैमरा प्रीव्यू लोड हो रहा है..." else "Initializing camera preview..."
                    cameraState.isAutoCaptureEnabled -> if (isHindi) {
                        "ऑटो-कैप्चर चालू है • या कभी भी शटर दबाकर मैनुअल फोटो खींचें"
                    } else {
                        "Auto-capture active when stable • Or tap shutter anytime for manual capture"
                    }
                    else -> if (isHindi) {
                        "मैनुअल मोड • नोटिस की फोटो खींचने के लिए शटर दबाएं"
                    } else {
                        "Manual mode • Tap shutter to capture notice or pick from Gallery"
                    }
                },
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.9f),
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("camerax_status_caption")
            )
        }
    }
}

/**
 * Legacy compatibility overload for MainFeatureScreen.kt.
 */
@Composable
fun CameraScreen(
    onPhotoCaptured: (Bitmap) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var localState by remember {
        mutableStateOf(
            CameraScanUiState(
                sessionStage = CameraSessionStage.INITIALIZING,
                isCameraBound = false
            )
        )
    }
    NoticeBuddyCameraPreviewScreen(
        cameraState = localState,
        selectedLanguage = NoticeLanguage.ENGLISH,
        onCameraInitializing = {
            localState = localState.copy(sessionStage = CameraSessionStage.INITIALIZING)
        },
        onCameraBound = { hasFlash ->
            localState = localState.copy(
                isCameraBound = true,
                isFlashSupported = hasFlash,
                sessionStage = CameraSessionStage.PREVIEW_READY,
                cameraErrorMessage = null
            )
        },
        onCameraUnbound = {
            localState = localState.copy(isCameraBound = false, isFlashEnabled = false)
        },
        onCameraError = { msg ->
            localState = localState.copy(
                sessionStage = CameraSessionStage.CAMERA_ERROR,
                cameraErrorMessage = msg
            )
        },
        onRetryCamera = {
            localState = localState.copy(
                sessionStage = CameraSessionStage.INITIALIZING,
                cameraErrorMessage = null,
                bindGeneration = localState.bindGeneration + 1
            )
        },
        onToggleFlash = {
            if (localState.isFlashSupported) {
                localState = localState.copy(isFlashEnabled = !localState.isFlashEnabled)
            }
        },
        onToggleAutoCapture = {
            localState = localState.copy(isAutoCaptureEnabled = !localState.isAutoCaptureEnabled)
        },
        onSwitchLens = {
            val nextLens = if (localState.lensFacing == CameraScanUiState.LENS_FACING_BACK) {
                CameraScanUiState.LENS_FACING_FRONT
            } else {
                CameraScanUiState.LENS_FACING_BACK
            }
            localState = localState.copy(
                lensFacing = nextLens,
                isFlashEnabled = false,
                bindGeneration = localState.bindGeneration + 1
            )
        },
        onDeviceRotationChanged = { deg ->
            localState = localState.copy(deviceRotationDegrees = deg)
        },
        onManualCaptureStarted = {
            localState = localState.copy(sessionStage = CameraSessionStage.CAPTURING)
            true
        },
        onPhotoCaptured = { bmp, _ ->
            localState = localState.copy(sessionStage = CameraSessionStage.PREVIEW_READY)
            onPhotoCaptured(bmp)
        },
        onCaptureFailed = { msg ->
            localState = localState.copy(
                sessionStage = CameraSessionStage.CAMERA_ERROR,
                cameraErrorMessage = msg
            )
        },
        onOpenGallery = onClose,
        onClose = onClose,
        modifier = modifier
    )
}

@Composable
fun DocumentGuideFrameOverlay(
    isFlashActive: Boolean,
    detectedQuad: DetectedDocumentQuad? = null,
    qualityGuidance: CameraQualityGuidance = CameraQualityGuidance.NO_DOCUMENT_DETECTED,
    isStableLocked: Boolean = false,
    stabilityProgress: Float = 0f,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val frameWidth = (size.width * 0.84f).coerceAtMost(540.dp.toPx())
        val frameHeight = (size.height * 0.58f).coerceAtMost(frameWidth * 1.38f)
        val left = (size.width - frameWidth) / 2f
        val top = (size.height - frameHeight) / 2f - 12.dp.toPx()
        val right = left + frameWidth
        val bottom = top + frameHeight
        val cornerRadius = 18.dp.toPx()

        val cutoutPath = Path().apply {
            addRoundRect(
                RoundRect(
                    rect = Rect(Offset(left, top), Size(frameWidth, frameHeight)),
                    cornerRadius = CornerRadius(cornerRadius, cornerRadius)
                )
            )
        }

        // Dimmed outer scrim outside the document target region
        clipPath(cutoutPath, clipOp = ClipOp.Difference) {
            drawRect(color = Color(0x990B1120))
        }

        // Dashed inner alignment guide border
        drawRoundRect(
            color = Color.White.copy(alpha = if (detectedQuad != null) 0.25f else 0.45f),
            topLeft = Offset(left, top),
            size = Size(frameWidth, frameHeight),
            cornerRadius = CornerRadius(cornerRadius, cornerRadius),
            style = Stroke(
                width = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 12f), 0f)
            )
        )

        // Subtle horizontal alignment lines inside the document frame
        val thirdHeight = frameHeight / 3f
        drawLine(
            color = Color.White.copy(alpha = 0.14f),
            start = Offset(left + 20.dp.toPx(), top + thirdHeight),
            end = Offset(right - 20.dp.toPx(), top + thirdHeight),
            strokeWidth = 1.dp.toPx()
        )
        drawLine(
            color = Color.White.copy(alpha = 0.14f),
            start = Offset(left + 20.dp.toPx(), top + thirdHeight * 2f),
            end = Offset(right - 20.dp.toPx(), top + thirdHeight * 2f),
            strokeWidth = 1.dp.toPx()
        )

        // Static guide corner brackets
        val bracketLen = 30.dp.toPx()
        val strokeWidth = 3.5.dp.toPx()
        val bracketColor = when {
            isFlashActive -> Color(0xFFFBBF24)
            detectedQuad != null -> Color.White.copy(alpha = 0.45f)
            else -> Color(0xFF2DD4BF)
        }

        // Top-Left Bracket
        drawLine(bracketColor, Offset(left, top + bracketLen), Offset(left, top), strokeWidth, StrokeCap.Round)
        drawLine(bracketColor, Offset(left, top), Offset(left + bracketLen, top), strokeWidth, StrokeCap.Round)

        // Top-Right Bracket
        drawLine(bracketColor, Offset(right - bracketLen, top), Offset(right, top), strokeWidth, StrokeCap.Round)
        drawLine(bracketColor, Offset(right, top), Offset(right, top + bracketLen), strokeWidth, StrokeCap.Round)

        // Bottom-Left Bracket
        drawLine(bracketColor, Offset(left, bottom - bracketLen), Offset(left, bottom), strokeWidth, StrokeCap.Round)
        drawLine(bracketColor, Offset(left, bottom), Offset(left + bracketLen, bottom), strokeWidth, StrokeCap.Round)

        // Bottom-Right Bracket
        drawLine(bracketColor, Offset(right - bracketLen, bottom), Offset(right, bottom), strokeWidth, StrokeCap.Round)
        drawLine(bracketColor, Offset(right, bottom - bracketLen), Offset(right, bottom), strokeWidth, StrokeCap.Round)

        // Phase 8: Draw the visible, stable detected document boundary polygon when a document is detected
        if (detectedQuad != null) {
            val tl = Offset(detectedQuad.topLeft.x * size.width, detectedQuad.topLeft.y * size.height)
            val tr = Offset(detectedQuad.topRight.x * size.width, detectedQuad.topRight.y * size.height)
            val br = Offset(detectedQuad.bottomRight.x * size.width, detectedQuad.bottomRight.y * size.height)
            val bl = Offset(detectedQuad.bottomLeft.x * size.width, detectedQuad.bottomLeft.y * size.height)

            val quadPath = Path().apply {
                moveTo(tl.x, tl.y)
                lineTo(tr.x, tr.y)
                lineTo(br.x, br.y)
                lineTo(bl.x, bl.y)
                close()
            }

            val edgeColor = when {
                isStableLocked -> Color(0xFF10B981) // Emerald green when locked
                qualityGuidance.isWarning -> Color(0xFFF59E0B) // Amber when lighting/glare/tilt/blur/cut-off
                else -> Color(0xFF2DD4BF) // Teal when tracking & stabilizing
            }
            val fillAlpha = if (isStableLocked) 0.18f else (0.08f + 0.08f * stabilityProgress)

            drawPath(
                path = quadPath,
                color = edgeColor.copy(alpha = fillAlpha)
            )
            drawPath(
                path = quadPath,
                color = edgeColor,
                style = Stroke(
                    width = if (isStableLocked) 4.dp.toPx() else 3.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )

            // Draw corner lock nodes at the 4 detected vertices
            val cornerNodeRadius = if (isStableLocked) 6.5.dp.toPx() else 5.dp.toPx()
            listOf(tl, tr, br, bl).forEach { vertex ->
                drawCircle(
                    color = Color(0xFF0B1120),
                    radius = cornerNodeRadius + 2.dp.toPx(),
                    center = vertex
                )
                drawCircle(
                    color = edgeColor,
                    radius = cornerNodeRadius,
                    center = vertex
                )
            }
        }
    }
}

internal fun degreesToSurfaceRotation(degrees: Int): Int {
    return when (((degrees % 360) + 360) % 360) {
        in 45..134 -> Surface.ROTATION_270
        in 135..224 -> Surface.ROTATION_180
        in 225..314 -> Surface.ROTATION_90
        else -> Surface.ROTATION_0
    }
}

internal fun decodeImageProxyToUprightBitmap(imageProxy: ImageProxy): Bitmap? {
    return try {
        val rawBitmap = imageProxy.toBitmap()
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        if (rotationDegrees == 0) {
            rawBitmap
        } else {
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            Bitmap.createBitmap(
                rawBitmap,
                0,
                0,
                rawBitmap.width,
                rawBitmap.height,
                matrix,
                true
            )
        }
    } catch (e: Exception) {
        Log.e("CameraScreen", "Failed to convert ImageProxy to Bitmap", e)
        null
    }
}
