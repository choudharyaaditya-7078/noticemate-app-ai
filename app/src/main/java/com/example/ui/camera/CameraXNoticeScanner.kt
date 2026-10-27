package com.example.ui.camera

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.data.local.RecentNoticeStorage
import com.example.data.model.CameraPermissionState
import com.example.data.model.CameraScanUiState
import com.example.data.model.CapturedNoticePhoto
import com.example.data.model.DocumentFrameAnalysisResult
import com.example.data.model.NoticeLanguage
import com.example.data.model.PageOcrPreviewStatus
import com.example.data.model.ScannedNoticePage
import com.example.ui.components.GlassButton
import com.example.ui.components.GlassCard
import com.example.ui.components.GlassIconBadge
import com.example.ui.components.NoticeBuddyAccentPill
import com.example.ui.theme.LocalGlassPalette
import com.example.ui.theme.LocalNoticeBuddyRadius

/**
 * Phase 7, 8 & 9 NoticeBuddy Camera-Scanning Workflow Screen:
 * Coordinates:
 * 1. Runtime CAMERA permission checking, request prompt, and denial/fallback UI.
 * 2. Live CameraX preview with intelligent document-edge detection, quality guidance,
 *    auto-capture, manual capture fallback, flash where supported, lens flip, gallery access,
 *    and multi-page progress indicator.
 * 3. Multi-page & single-page captured notice review screen with page counter, thumbnail strip,
 *    add/remove/retake/reorder page controls, non-destructive crop/rotate/perspective correction,
 *    OCR readability check, and quality warnings before finalizing into the NoticeBuddy pipeline.
 */
@Composable
fun NoticeBuddyCameraScannerScreen(
    cameraState: CameraScanUiState,
    selectedLanguage: NoticeLanguage,
    onPermissionChecked: (Boolean) -> Unit,
    onPermissionResult: (Boolean) -> Unit,
    onCameraInitializing: () -> Unit,
    onCameraBound: (Boolean) -> Unit,
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
    onRetakePhoto: () -> Unit,
    onAcceptPhoto: () -> Unit,
    onAddAnotherPage: () -> Unit = {},
    onReturnToReview: () -> Unit = {},
    onSelectPage: (Int) -> Unit = {},
    onRemovePage: (Int) -> Unit = {},
    onMovePageEarlier: () -> Unit = {},
    onMovePageLater: () -> Unit = {},
    onRotateClockwise: () -> Unit = {},
    onRotateCounterClockwise: () -> Unit = {},
    onToggleCrop: () -> Unit = {},
    onTogglePerspective: () -> Unit = {},
    onResetPageEdits: () -> Unit = {},
    onRunOcrCheck: () -> Unit = {},
    onOpenGallery: () -> Unit,
    onClose: () -> Unit,
    enableHardwareCameraBinding: Boolean = true,
    customCaptureTrigger: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        onPermissionResult(granted)
    }

    // Check current OS permission state on initial entry when hardware camera binding is enabled
    LaunchedEffect(enableHardwareCameraBinding) {
        if (!enableHardwareCameraBinding) return@LaunchedEffect
        val osGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (cameraState.permissionState == CameraPermissionState.UNKNOWN) {
            onPermissionChecked(osGranted)
            if (!osGranted) {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
        } else if (osGranted && cameraState.permissionState != CameraPermissionState.GRANTED) {
            onPermissionChecked(true)
        }
    }

    val capturedPhoto = cameraState.capturedPhoto

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(24.dp))
            .testTag("camerax_scanner_root")
    ) {
        when {
            capturedPhoto != null -> {
                CapturedNoticeReviewGlassScreen(
                    cameraState = cameraState,
                    capturedPhoto = capturedPhoto,
                    selectedLanguage = selectedLanguage,
                    onRetake = onRetakePhoto,
                    onAcceptAndAnalyze = onAcceptPhoto,
                    onAddAnotherPage = onAddAnotherPage,
                    onSelectPage = onSelectPage,
                    onRemovePage = onRemovePage,
                    onMovePageEarlier = onMovePageEarlier,
                    onMovePageLater = onMovePageLater,
                    onRotateClockwise = onRotateClockwise,
                    onRotateCounterClockwise = onRotateCounterClockwise,
                    onToggleCrop = onToggleCrop,
                    onTogglePerspective = onTogglePerspective,
                    onResetPageEdits = onResetPageEdits,
                    onRunOcrCheck = onRunOcrCheck,
                    onClose = onClose
                )
            }

            cameraState.permissionState != CameraPermissionState.GRANTED -> {
                CameraPermissionGlassView(
                    selectedLanguage = selectedLanguage,
                    permissionDeniedCount = cameraState.permissionDeniedCount,
                    onRequestPermission = {
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    },
                    onOpenGallery = onOpenGallery,
                    onCancel = onClose
                )
            }

            else -> {
                NoticeBuddyCameraPreviewScreen(
                    cameraState = cameraState,
                    selectedLanguage = selectedLanguage,
                    onCameraInitializing = onCameraInitializing,
                    onCameraBound = onCameraBound,
                    onCameraUnbound = onCameraUnbound,
                    onCameraError = onCameraError,
                    onRetryCamera = onRetryCamera,
                    onToggleFlash = onToggleFlash,
                    onToggleAutoCapture = onToggleAutoCapture,
                    onSwitchLens = onSwitchLens,
                    onDeviceRotationChanged = onDeviceRotationChanged,
                    onFrameAnalyzed = onFrameAnalyzed,
                    onManualCaptureStarted = onManualCaptureStarted,
                    onPhotoCaptured = onPhotoCaptured,
                    onCaptureFailed = onCaptureFailed,
                    onOpenGallery = onOpenGallery,
                    onClose = onClose,
                    onReturnToReview = onReturnToReview,
                    enableHardwareCameraBinding = enableHardwareCameraBinding,
                    customCaptureTrigger = customCaptureTrigger
                )
            }
        }
    }
}

@Composable
private fun CapturedNoticeReviewGlassScreen(
    cameraState: CameraScanUiState,
    capturedPhoto: CapturedNoticePhoto,
    selectedLanguage: NoticeLanguage,
    onRetake: () -> Unit,
    onAcceptAndAnalyze: () -> Unit,
    onAddAnotherPage: () -> Unit,
    onSelectPage: (Int) -> Unit,
    onRemovePage: (Int) -> Unit,
    onMovePageEarlier: () -> Unit,
    onMovePageLater: () -> Unit,
    onRotateClockwise: () -> Unit,
    onRotateCounterClockwise: () -> Unit,
    onToggleCrop: () -> Unit,
    onTogglePerspective: () -> Unit,
    onResetPageEdits: () -> Unit,
    onRunOcrCheck: () -> Unit,
    onClose: () -> Unit
) {
    val glass = LocalGlassPalette.current
    val radius = LocalNoticeBuddyRadius.current
    val isHindi = selectedLanguage == NoticeLanguage.HINDI

    val pages = cameraState.scannedPages
    val totalPages = pages.size.coerceAtLeast(1)
    val selectedIndex = cameraState.safeSelectedPageIndex
    val activePage: ScannedNoticePage? = cameraState.activeScannedPage

    val activeBytes = activePage?.currentPngBytes ?: capturedPhoto.pngBytes
    val decodedBitmap = remember(activePage?.pageId, activeBytes) {
        runCatching {
            BitmapFactory.decodeByteArray(activeBytes, 0, activeBytes.size)
        }.getOrNull()
    }

    val activeQualityGuidance = activePage?.captureQualityGuidance ?: capturedPhoto.captureQualityGuidance
    val ocrStatus = activePage?.ocrPreviewStatus ?: PageOcrPreviewStatus.NOT_RUN
    val hasActiveWarning = activePage?.hasQualityOrOcrWarning == true || activeQualityGuidance.isWarning

    GlassCard(
        elevated = true,
        cornerRadius = radius.card,
        contentPadding = PaddingValues(10.dp),
        modifier = Modifier
            .fillMaxSize()
            .testTag("camerax_review_screen")
    ) {
        // 1. Top Review Header: Page Counter Badge + Capture Mode Badge + Close
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    NoticeBuddyAccentPill(
                        text = if (isHindi) {
                            "पृष्ठ ${selectedIndex + 1} / $totalPages"
                        } else {
                            "Page ${selectedIndex + 1} of $totalPages"
                        },
                        accentColor = Color(0xFF2DD4BF),
                        backgroundColor = Color(0xFF134E4A),
                        modifier = Modifier
                            .semantics(mergeDescendants = true) {}
                            .testTag("camerax_page_counter_badge")
                    )
                    NoticeBuddyAccentPill(
                        text = if (capturedPhoto.wasAutoCaptured) {
                            if (isHindi) "ऑटो-कैप्चर" else "Auto-Captured"
                        } else {
                            if (isHindi) "मैनुअल कैप्चर" else "Manual Capture"
                        },
                        accentColor = if (capturedPhoto.wasAutoCaptured) glass.tealAccent else glass.electricBlue,
                        backgroundColor = if (capturedPhoto.wasAutoCaptured) glass.tealSoft else glass.electricBlueSoft,
                        modifier = Modifier
                            .semantics(mergeDescendants = true) {}
                            .testTag("camerax_review_capture_mode_badge")
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = if (totalPages > 1) {
                        if (isHindi) {
                            "सभी $totalPages पृष्ठ एक ही नोटिस दस्तावेज़ के रूप में जाँचे जाएंगे"
                        } else {
                            "All $totalPages pages will be analyzed together as one notice"
                        }
                    } else {
                        if (isHindi) {
                            "जाँचें कि नोटिस का टेक्स्ट और तारीखें स्पष्ट हैं"
                        } else {
                            "Check that notice text & dates are readable"
                        }
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = glass.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .size(36.dp)
                    .testTag("camerax_review_close_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = if (isHindi) "बंद करें" else "Close review",
                    tint = glass.textPrimary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // 2. Quality / Unreadable / Incomplete Page Warning Banner
        if (hasActiveWarning || cameraState.pagesWithWarningsCount > 0) {
            val warningText = when {
                ocrStatus == PageOcrPreviewStatus.UNREADABLE -> {
                    activePage?.ocrWarningMessage
                        ?: if (isHindi) "इस पृष्ठ पर कोई पठनीय टेक्स्ट नहीं मिला। कृपया दोबारा फोटो लें।"
                        else "No readable text found on Page ${selectedIndex + 1}. Retake or adjust crop/lighting."
                }
                ocrStatus == PageOcrPreviewStatus.INCOMPLETE_OR_LOW_TEXT -> {
                    activePage?.ocrWarningMessage
                        ?: if (isHindi) "इस पृष्ठ पर बहुत कम टेक्स्ट मिला (अधूरा पृष्ठ)।"
                        else "Very little text detected on Page ${selectedIndex + 1}. Ensure edges are not cut off."
                }
                ocrStatus == PageOcrPreviewStatus.OCR_FAILED -> {
                    activePage?.ocrWarningMessage
                        ?: if (isHindi) "OCR जाँच विफल रही। आप पुनः प्रयास कर सकते हैं।"
                        else "OCR check could not complete for Page ${selectedIndex + 1}. Tap OCR Check to retry."
                }
                activeQualityGuidance.isWarning -> {
                    if (isHindi) {
                        "चेतावनी (${activeQualityGuidance.displayBadge(selectedLanguage)}): ${activeQualityGuidance.displayGuidance(selectedLanguage)}"
                    } else {
                        "Warning (${activeQualityGuidance.displayBadge(selectedLanguage)}): ${activeQualityGuidance.displayGuidance(selectedLanguage)}"
                    }
                }
                else -> {
                    if (isHindi) {
                        "${cameraState.pagesWithWarningsCount} पृष्ठ में गुणवत्ता चेतावनी है। कृपया समीक्षा करें।"
                    } else {
                        "${cameraState.pagesWithWarningsCount} page(s) have readability warnings. Review before finalizing."
                    }
                }
            }

            Surface(
                color = Color(0xFF451A03).copy(alpha = 0.88f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFFF59E0B), RoundedCornerShape(8.dp))
                    .semantics(mergeDescendants = true) {}
                    .testTag("camerax_page_quality_warning_banner")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.WarningAmber,
                        contentDescription = null,
                        tint = Color(0xFFFBBF24),
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = warningText,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFFEF3C7),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
        }

        // 3. Captured & Corrected Image Preview Container
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag("camerax_captured_image_preview")
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF0F172A))
                .border(1.2.dp, glass.glassBorder, RoundedCornerShape(14.dp))
                .padding(6.dp),
            contentAlignment = Alignment.Center
        ) {
            if (decodedBitmap != null) {
                Image(
                    bitmap = decodedBitmap.asImageBitmap(),
                    contentDescription = if (isHindi) "कैप्चर किए गए नोटिस का पूर्वावलोकन" else "Captured notice preview",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    text = if (isHindi) "नोटिस इमेज तैयार है" else "Captured notice image ready",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White
                )
            }

            // Overlay active edit, Reset button, & OCR badges inside bottom of preview
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (activePage?.editState?.hasEdits == true) {
                        val editParts = buildList {
                            if (activePage.editState.extraRotationDegrees != 0) {
                                add("${activePage.editState.extraRotationDegrees}°")
                            }
                            if (activePage.editState.isCropped) {
                                add(if (isHindi) "क्रॉप" else "Cropped")
                            }
                            if (activePage.editState.isPerspectiveCorrected) {
                                add(if (isHindi) "परिप्रेक्ष्य" else "Rectified")
                            }
                        }
                        NoticeBuddyAccentPill(
                            text = editParts.joinToString(" • ").ifBlank { "Edited" },
                            accentColor = Color(0xFF38BDF8),
                            backgroundColor = Color(0xCC0C4A6E),
                            modifier = Modifier
                                .semantics(mergeDescendants = true) {}
                                .testTag("camerax_active_edits_badge")
                        )
                    }

                    if (ocrStatus != PageOcrPreviewStatus.NOT_RUN) {
                        val ocrBadgeText = when (ocrStatus) {
                            PageOcrPreviewStatus.RUNNING -> if (isHindi) "OCR जाँच..." else "OCR Checking..."
                            PageOcrPreviewStatus.READABLE -> if (isHindi) {
                                "OCR पठनीय (${activePage?.ocrCharacterCount ?: 0} अक्षर)"
                            } else {
                                "OCR Readable (${activePage?.ocrCharacterCount ?: 0} chars)"
                            }
                            PageOcrPreviewStatus.INCOMPLETE_OR_LOW_TEXT -> if (isHindi) "कम टेक्स्ट" else "Low Text Warning"
                            PageOcrPreviewStatus.UNREADABLE -> if (isHindi) "अपठनीय पृष्ठ" else "Unreadable Page"
                            PageOcrPreviewStatus.OCR_FAILED -> if (isHindi) "OCR त्रुटि" else "OCR Error"
                            PageOcrPreviewStatus.NOT_RUN -> ""
                        }
                        val ocrAccent = when (ocrStatus) {
                            PageOcrPreviewStatus.READABLE -> Color(0xFF34D399)
                            PageOcrPreviewStatus.RUNNING -> Color(0xFF93C5FD)
                            else -> Color(0xFFFBBF24)
                        }
                        val ocrBg = when (ocrStatus) {
                            PageOcrPreviewStatus.READABLE -> Color(0xCC064E3B)
                            PageOcrPreviewStatus.RUNNING -> Color(0xCC1E3A8A)
                            else -> Color(0xCC78350F)
                        }
                        NoticeBuddyAccentPill(
                            text = ocrBadgeText,
                            accentColor = ocrAccent,
                            backgroundColor = ocrBg,
                            modifier = Modifier
                                .semantics(mergeDescendants = true) {}
                                .testTag("camerax_ocr_preview_status")
                        )
                    }
                }

                if (activePage?.editState?.hasEdits == true) {
                    CorrectionToolChip(
                        label = if (isHindi) "रीसेट" else "Reset",
                        icon = Icons.Default.Refresh,
                        isActive = false,
                        onClick = onResetPageEdits,
                        testTag = "camerax_reset_page_edits_button"
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // 4. Phase 9 Single-Row Image Correction & OCR Toolbar (Rotate L, Rotate R, Crop, Perspective, OCR Check)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("camerax_image_correction_toolbar"),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CorrectionToolChip(
                label = if (isHindi) "बाएँ" else "Rot L",
                icon = Icons.AutoMirrored.Filled.RotateLeft,
                isActive = false,
                onClick = onRotateCounterClockwise,
                testTag = "camerax_rotate_left_button",
                modifier = Modifier.weight(1f)
            )
            CorrectionToolChip(
                label = if (isHindi) "दाएँ" else "Rot R",
                icon = Icons.AutoMirrored.Filled.RotateRight,
                isActive = (activePage?.editState?.extraRotationDegrees ?: 0) != 0,
                onClick = onRotateClockwise,
                testTag = "camerax_rotate_right_button",
                modifier = Modifier.weight(1f)
            )
            CorrectionToolChip(
                label = if (activePage?.editState?.isCropped == true) {
                    if (isHindi) "क्रॉप✓" else "Crop✓"
                } else {
                    if (isHindi) "क्रॉप" else "Crop"
                },
                icon = Icons.Default.Crop,
                isActive = activePage?.editState?.isCropped == true,
                onClick = onToggleCrop,
                testTag = "camerax_crop_button",
                modifier = Modifier.weight(1f)
            )
            CorrectionToolChip(
                label = if (activePage?.editState?.isPerspectiveCorrected == true) {
                    if (isHindi) "सीधा✓" else "Flat✓"
                } else {
                    if (isHindi) "सीधा" else "Flat"
                },
                icon = Icons.Default.AutoAwesome,
                isActive = activePage?.editState?.isPerspectiveCorrected == true,
                onClick = onTogglePerspective,
                testTag = "camerax_perspective_button",
                modifier = Modifier.weight(1f)
            )
            CorrectionToolChip(
                label = if (cameraState.isOcrCheckRunning) {
                    "OCR.."
                } else {
                    "OCR"
                },
                icon = Icons.Default.Description,
                isActive = ocrStatus == PageOcrPreviewStatus.READABLE,
                onClick = onRunOcrCheck,
                testTag = "camerax_ocr_preview_button",
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // 5. Phase 9 Multi-Page Thumbnail Strip & Page Management Controls (Add, Reorder, Remove)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("camerax_page_thumbnail_strip"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val thumbScroll = rememberScrollState()
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(thumbScroll),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                pages.forEachIndexed { idx, page ->
                    val isSelected = idx == selectedIndex
                    val pageHasWarn = page.hasQualityOrOcrWarning
                    Surface(
                        color = when {
                            isSelected -> Color(0xFF0D9488)
                            pageHasWarn -> Color(0xFF78350F)
                            else -> Color(0xFF1E293B)
                        },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .heightIn(min = 30.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(
                                width = if (isSelected) 1.5.dp else 1.dp,
                                color = when {
                                    isSelected -> Color(0xFF5EEAD4)
                                    pageHasWarn -> Color(0xFFFBBF24)
                                    else -> Color.White.copy(alpha = 0.22f)
                                },
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable { onSelectPage(idx) }
                            .semantics(mergeDescendants = true) {}
                            .testTag("camerax_page_thumbnail_$idx")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                text = "P${idx + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            if (page.editState.hasEdits) {
                                Text(
                                    text = "✦",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF38BDF8)
                                )
                            }
                            if (pageHasWarn) {
                                Icon(
                                    imageVector = Icons.Default.WarningAmber,
                                    contentDescription = "Page warning",
                                    tint = Color(0xFFFBBF24),
                                    modifier = Modifier.size(11.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(4.dp))

            // Page Reorder / Remove / Add Controls
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (totalPages > 1) {
                    IconButton(
                        onClick = onMovePageEarlier,
                        enabled = selectedIndex > 0,
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1E293B))
                            .testTag("camerax_move_page_left_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = if (isHindi) "पृष्ठ पहले ले जाएँ" else "Move page earlier",
                            tint = if (selectedIndex > 0) Color.White else Color.White.copy(alpha = 0.35f),
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    IconButton(
                        onClick = onMovePageLater,
                        enabled = selectedIndex < totalPages - 1,
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1E293B))
                            .testTag("camerax_move_page_right_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = if (isHindi) "पृष्ठ बाद में ले जाएँ" else "Move page later",
                            tint = if (selectedIndex < totalPages - 1) Color.White else Color.White.copy(alpha = 0.35f),
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    IconButton(
                        onClick = { onRemovePage(selectedIndex) },
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF7F1D1D))
                            .testTag("camerax_remove_page_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = if (isHindi) "पृष्ठ हटाएँ" else "Remove current page",
                            tint = Color(0xFFFCA5A5),
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }

                // + Add Page Button
                Surface(
                    color = Color(0xFF0F766E),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .heightIn(min = 30.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, Color(0xFF2DD4BF), RoundedCornerShape(8.dp))
                        .clickable(onClick = onAddAnotherPage)
                        .semantics(mergeDescendants = true) {}
                        .testTag("camerax_add_page_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AddAPhoto,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = if (isHindi) "+ पृष्ठ" else "+ Add Page",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // 6. Metadata Pill Row (Dimensions, Orientation, File Size)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("camerax_captured_image_meta"),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = activePage?.fileName ?: capturedPhoto.fileName,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = glass.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            val widthPx = activePage?.widthPx ?: capturedPhoto.widthPx
            val heightPx = activePage?.heightPx ?: capturedPhoto.heightPx
            val sizeBytes = activePage?.fileSizeBytes ?: capturedPhoto.fileSizeBytes
            Text(
                text = "${widthPx}×${heightPx} • ${RecentNoticeStorage.formatFileSize(sizeBytes)}",
                style = MaterialTheme.typography.labelSmall,
                color = glass.textSecondary
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // 7. Bottom Review Actions: Retake Page vs Accept / Finalize Document
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            GlassButton(
                text = if (totalPages > 1) {
                    if (isHindi) "पृष्ठ ${selectedIndex + 1} दोबारा लें" else "Retake P${selectedIndex + 1}"
                } else {
                    if (isHindi) "दोबारा खींचें" else "Retake"
                },
                icon = Icons.Default.Refresh,
                isPrimary = false,
                onClick = onRetake,
                modifier = Modifier
                    .weight(1f)
                    .testTag("camerax_retake_button")
            )

            GlassButton(
                text = if (totalPages > 1) {
                    if (isHindi) "$totalPages पृष्ठ स्वीकार करें" else "Use $totalPages Pages"
                } else {
                    if (isHindi) "फोटो स्वीकार करें" else "Use Photo"
                },
                icon = Icons.Default.CheckCircle,
                isPrimary = true,
                onClick = onAcceptAndAnalyze,
                modifier = Modifier
                    .weight(1.35f)
                    .testTag("camerax_accept_button")
            )
        }
    }
}

@Composable
private fun CorrectionToolChip(
    label: String,
    icon: ImageVector,
    isActive: Boolean,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    Surface(
        color = if (isActive) Color(0xFF0D9488) else Color(0xFF1E293B),
        shape = RoundedCornerShape(8.dp),
        modifier = modifier
            .heightIn(min = 28.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(
                width = 1.dp,
                color = if (isActive) Color(0xFF2DD4BF) else Color.White.copy(alpha = 0.22f),
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(12.dp)
            )
            Spacer(modifier = Modifier.width(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun CameraPermissionGlassView(
    selectedLanguage: NoticeLanguage,
    permissionDeniedCount: Int,
    onRequestPermission: () -> Unit,
    onOpenGallery: () -> Unit,
    onCancel: () -> Unit
) {
    val glass = LocalGlassPalette.current
    val isHindi = selectedLanguage == NoticeLanguage.HINDI
    val scrollState = rememberScrollState()

    GlassCard(
        elevated = true,
        contentPadding = PaddingValues(16.dp),
        modifier = Modifier
            .fillMaxSize()
            .testTag("camera_permission_denied_card")
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                GlassIconBadge(
                    icon = Icons.Default.CameraAlt,
                    contentDescription = null,
                    size = 40.dp,
                    accentTint = glass.electricBlue
                )

                NoticeBuddyAccentPill(
                    text = if (isHindi) "कैमरा अनुमति आवश्यक" else "Camera Permission Required",
                    accentColor = glass.electricBlue,
                    backgroundColor = glass.electricBlueSoft
                )
            }

            Text(
                text = if (isHindi) {
                    "नोटिस स्कैन करने के लिए कैमरा अनुमति दें"
                } else {
                    "Camera Access Needed to Scan Notices"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = glass.textPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("camera_permission_title")
            )

            Text(
                text = if (isHindi) {
                    "NoticeBuddy भौतिक नोटिस बोर्ड और परिपत्रों की स्पष्ट फोटो लेने के लिए आपके कैमरे का उपयोग करता है। आप गैलरी से भी नोटिस फोटो चुन सकते हैं।"
                } else {
                    "NoticeBuddy uses your camera to capture clear photos of physical notice boards and circulars. You can also pick an existing photo from your gallery anytime."
                },
                style = MaterialTheme.typography.bodySmall,
                color = glass.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("camera_permission_message")
            )

            if (permissionDeniedCount >= 1) {
                GlassCard(
                    contentPadding = PaddingValues(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("camera_permission_settings_hint")
                ) {
                    Text(
                        text = if (isHindi) {
                            "कैमरा अनुमति अस्वीकार कर दी गई है। पुनः अनुमति देने के लिए 'Allow Camera' दबाएँ, या फोन की App Settings > Permissions > Camera में जाकर अनुमति दें।"
                        } else {
                            "Camera permission was denied. Tap 'Allow Camera' to grant access, or enable Camera permission in Android App Settings if prompted."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = glass.textPrimary,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            GlassButton(
                text = if (isHindi) "कैमरा अनुमति दें" else "Allow Camera",
                icon = Icons.Default.CameraAlt,
                isPrimary = true,
                onClick = onRequestPermission,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("camerax_permission_grant_button")
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                GlassButton(
                    text = if (isHindi) "गैलरी चुनें" else "Gallery",
                    icon = Icons.Default.Image,
                    isPrimary = false,
                    onClick = onOpenGallery,
                    modifier = Modifier
                        .weight(1.2f)
                        .testTag("camerax_permission_gallery_button")
                )

                GlassButton(
                    text = if (isHindi) "वापस" else "Back",
                    icon = Icons.Default.Close,
                    isPrimary = false,
                    onClick = onCancel,
                    modifier = Modifier
                        .weight(0.8f)
                        .testTag("camerax_permission_cancel_button")
                )
            }
        }
    }
}

/**
 * Legacy compatibility overload for MainFeatureScreen.kt.
 */
@Composable
fun CameraXNoticeScannerScreen(
    onNoticeCapturedAndConfirmed: (Bitmap) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var localState by remember {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        mutableStateOf(
            CameraScanUiState(
                permissionState = if (granted) CameraPermissionState.GRANTED else CameraPermissionState.UNKNOWN
            )
        )
    }
    var lastBitmap by remember { mutableStateOf<Bitmap?>(null) }

    BackHandler {
        if (localState.capturedPhoto != null) {
            localState = localState.copy(capturedPhoto = null)
            lastBitmap = null
        } else {
            onDismiss()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B1120))
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        NoticeBuddyCameraScannerScreen(
            cameraState = localState,
            selectedLanguage = NoticeLanguage.ENGLISH,
            onPermissionChecked = { granted ->
                localState = localState.copy(
                    permissionState = if (granted) CameraPermissionState.GRANTED else CameraPermissionState.DENIED
                )
            },
            onPermissionResult = { granted ->
                localState = localState.copy(
                    permissionState = if (granted) CameraPermissionState.GRANTED else CameraPermissionState.DENIED,
                    permissionDeniedCount = if (granted) localState.permissionDeniedCount else localState.permissionDeniedCount + 1
                )
            },
            onCameraInitializing = {},
            onCameraBound = { hasFlash ->
                localState = localState.copy(isCameraBound = true, isFlashSupported = hasFlash)
            },
            onCameraUnbound = {
                localState = localState.copy(isCameraBound = false, isFlashEnabled = false)
            },
            onCameraError = { msg ->
                localState = localState.copy(cameraErrorMessage = msg)
            },
            onRetryCamera = {
                localState = localState.copy(
                    cameraErrorMessage = null,
                    bindGeneration = localState.bindGeneration + 1
                )
            },
            onToggleFlash = {
                if (localState.isFlashSupported) {
                    localState = localState.copy(isFlashEnabled = !localState.isFlashEnabled)
                }
            },
            onSwitchLens = {
                val nextLens = if (localState.lensFacing == CameraScanUiState.LENS_FACING_BACK) {
                    CameraScanUiState.LENS_FACING_FRONT
                } else {
                    CameraScanUiState.LENS_FACING_BACK
                }
                localState = localState.copy(lensFacing = nextLens, isFlashEnabled = false)
            },
            onDeviceRotationChanged = { deg ->
                localState = localState.copy(deviceRotationDegrees = deg)
            },
            onManualCaptureStarted = { true },
            onPhotoCaptured = { bmp, rot ->
                lastBitmap = bmp
                val out = java.io.ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                val bytes = out.toByteArray()
                val page = ScannedNoticePage(
                    pageId = "legacy_page_1",
                    fileName = "Scanned_Notice.png",
                    originalPngBytes = bytes,
                    currentPngBytes = bytes,
                    widthPx = bmp.width,
                    heightPx = bmp.height,
                    originalWidthPx = bmp.width,
                    originalHeightPx = bmp.height,
                    baseRotationDegrees = rot
                )
                localState = localState.copy(
                    scannedPages = listOf(page),
                    selectedPageIndex = 0,
                    capturedPhoto = CapturedNoticePhoto(
                        fileName = "Scanned_Notice.png",
                        pngBytes = bytes,
                        widthPx = bmp.width,
                        heightPx = bmp.height,
                        rotationDegrees = rot
                    )
                )
            },
            onCaptureFailed = { msg ->
                localState = localState.copy(cameraErrorMessage = msg)
            },
            onRetakePhoto = {
                localState = localState.copy(capturedPhoto = null, scannedPages = emptyList())
                lastBitmap = null
            },
            onAcceptPhoto = {
                lastBitmap?.let { onNoticeCapturedAndConfirmed(it) }
            },
            onOpenGallery = onDismiss,
            onClose = onDismiss
        )
    }
}
