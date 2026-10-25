package com.example.ui.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import android.view.ViewGroup
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ui.theme.NotoSansDevanagariFontFamily
import java.util.concurrent.Executors

/**
 * Full-screen CameraX composable providing a live camera preview,
 * a document-guide frame overlay for physical campus notices,
 * and an interactive flash/torch toggle button.
 */
@Composable
fun CameraScreen(
    onPhotoCaptured: (Bitmap) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    var lensFacing by rememberSaveable { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var isFlashEnabled by rememberSaveable { mutableStateOf(false) }
    var isCapturing by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var boundCamera by remember { mutableStateOf<Camera?>(null) }

    val previewView = remember {
        PreviewView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
        }
    }

    LaunchedEffect(lensFacing) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val selector = CameraSelector.Builder()
                    .requireLensFacing(lensFacing)
                    .build()

                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    selector,
                    preview,
                    imageCapture
                )
                boundCamera = camera
                camera.cameraControl.enableTorch(isFlashEnabled)
                imageCapture.flashMode = if (isFlashEnabled) {
                    ImageCapture.FLASH_MODE_ON
                } else {
                    ImageCapture.FLASH_MODE_OFF
                }
                cameraError = null
            } catch (e: Exception) {
                Log.e("CameraScreen", "Failed to bind CameraX use cases", e)
                cameraError = "Camera unavailable: ${e.localizedMessage ?: "Check device camera"}"
            }
        }, ContextCompat.getMainExecutor(context))
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0B1120))
            .testTag("camera_screen")
    ) {
        // 1. Full-Screen CameraX Preview
        AndroidView(
            factory = { previewView },
            modifier = Modifier
                .fillMaxSize()
                .testTag("camerax_preview_view")
        )

        // 2. Document-Guide Frame Overlay
        DocumentGuideFrameOverlay(
            isFlashActive = isFlashEnabled,
            modifier = Modifier
                .fillMaxSize()
                .testTag("document_guide_frame_overlay")
        )

        // 3. Top Controls Bar (Close, Title, Flash Toggle Button, Lens Switch)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f))
                        .testTag("camerax_close_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close camera",
                        tint = Color.White
                    )
                }

                // Flash Toggle Pill Button
                Surface(
                    color = if (isFlashEnabled) Color(0xFFD97706) else Color.Black.copy(alpha = 0.65f),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(24.dp))
                        .clickable {
                            val nextFlash = !isFlashEnabled
                            isFlashEnabled = nextFlash
                            boundCamera?.cameraControl?.enableTorch(nextFlash)
                            imageCapture.flashMode = if (nextFlash) {
                                ImageCapture.FLASH_MODE_ON
                            } else {
                                ImageCapture.FLASH_MODE_OFF
                            }
                        }
                        .testTag("camerax_flash_toggle_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isFlashEnabled) Icons.Default.FlashOn else Icons.Default.FlashOff,
                            contentDescription = if (isFlashEnabled) "Flash On" else "Flash Off",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isFlashEnabled) "Flash: ON" else "Flash: OFF",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                IconButton(
                    onClick = {
                        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                            CameraSelector.LENS_FACING_FRONT
                        } else {
                            CameraSelector.LENS_FACING_BACK
                        }
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f))
                        .testTag("camerax_switch_lens_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Cameraswitch,
                        contentDescription = "Switch camera lens",
                        tint = Color.White
                    )
                }
            }

            // Document Framing Helper Banner
            Surface(
                color = Color(0xFF0B1120).copy(alpha = 0.78f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Align physical notice inside the document frame",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "नोटिस को फ्रेम के भीतर रखें · अंतिम तिथि व पात्रता स्पष्ट दिखनी चाहिए",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = NotoSansDevanagariFontFamily
                        ),
                        color = Color(0xFF2DD4BF)
                    )
                }
            }

            if (cameraError != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = cameraError ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }

        // 4. Bottom Shutter Deck
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Color(0xFF0B1120).copy(alpha = 0.82f))
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(vertical = 20.dp, horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .border(4.dp, Color.White, CircleShape)
                    .padding(6.dp)
                    .clip(CircleShape)
                    .background(if (isCapturing) Color.Gray else Color(0xFF1B4DFF))
                    .clickable(enabled = !isCapturing) {
                        isCapturing = true
                        imageCapture.takePicture(
                            cameraExecutor,
                            object : ImageCapture.OnImageCapturedCallback() {
                                override fun onCaptureSuccess(image: ImageProxy) {
                                    val bitmap = decodeImageProxyToUprightBitmap(image)
                                    image.close()
                                    ContextCompat.getMainExecutor(context).execute {
                                        isCapturing = false
                                        if (bitmap != null) {
                                            onPhotoCaptured(bitmap)
                                        } else {
                                            cameraError = "Could not decode captured notice photo."
                                        }
                                    }
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    Log.e("CameraScreen", "Photo capture failed", exception)
                                    ContextCompat.getMainExecutor(context).execute {
                                        isCapturing = false
                                        cameraError = "Capture error: ${exception.localizedMessage}"
                                    }
                                }
                            }
                        )
                    }
                    .testTag("camerax_shutter_button"),
                contentAlignment = Alignment.Center
            ) {
                if (isCapturing) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(28.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = "Capture notice photo",
                        tint = Color.White,
                        modifier = Modifier.size(30.dp)
                    )
                }
            }

            Text(
                text = if (isCapturing) "Capturing high-resolution notice..." else "Tap shutter to capture campus notice",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.9f)
            )
        }
    }
}

@Composable
fun DocumentGuideFrameOverlay(
    isFlashActive: Boolean,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val frameWidth = (size.width * 0.84f).coerceAtMost(540.dp.toPx())
        val frameHeight = (size.height * 0.60f).coerceAtMost(frameWidth * 1.38f)
        val left = (size.width - frameWidth) / 2f
        val top = (size.height - frameHeight) / 2f - 16.dp.toPx()
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
            color = Color.White.copy(alpha = 0.45f),
            topLeft = Offset(left, top),
            size = Size(frameWidth, frameHeight),
            cornerRadius = CornerRadius(cornerRadius, cornerRadius),
            style = Stroke(
                width = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 12f), 0f)
            )
        )

        // Subtle horizontal text-alignment guide lines inside the document frame
        val thirdHeight = frameHeight / 3f
        drawLine(
            color = Color.White.copy(alpha = 0.16f),
            start = Offset(left + 20.dp.toPx(), top + thirdHeight),
            end = Offset(right - 20.dp.toPx(), top + thirdHeight),
            strokeWidth = 1.dp.toPx()
        )
        drawLine(
            color = Color.White.copy(alpha = 0.16f),
            start = Offset(left + 20.dp.toPx(), top + thirdHeight * 2f),
            end = Offset(right - 20.dp.toPx(), top + thirdHeight * 2f),
            strokeWidth = 1.dp.toPx()
        )

        // High-contrast corner brackets (Amber when flash is active, Emerald Teal otherwise)
        val bracketLen = 34.dp.toPx()
        val strokeWidth = 4.5.dp.toPx()
        val bracketColor = if (isFlashActive) Color(0xFFFBBF24) else Color(0xFF2DD4BF)

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
