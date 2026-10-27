package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.AppDiagnosticLogger
import com.example.data.local.AppThemeMode
import com.example.data.local.DiagnosticLogEntry
import com.example.data.local.DiagnosticLogLevel
import com.example.data.local.NoticeInputType
import com.example.data.local.RecentNoticeEntry
import com.example.data.local.RecentNoticeStorage
import com.example.data.model.NoticeDescriptionResult
import com.example.data.model.NoticeLanguage
import com.example.data.model.NoticeRequestError
import com.example.data.model.NoticeRequestErrorCategory
import com.example.data.model.NoticeValidationGateResult
import com.example.data.model.NoticeValidationStatus
import com.example.data.model.PdfExtractionResult
import com.example.data.remote.NetworkConnectivityStatus
import com.example.ui.camera.NoticeBuddyCameraScannerScreen
import com.example.ui.components.BokehGlassBackground
import com.example.ui.components.GlassButton
import com.example.ui.components.GlassCard
import com.example.ui.components.GlassIconBadge
import com.example.ui.components.GlassModalDialog
import com.example.ui.components.LiquidLanguageTogglePill
import com.example.ui.components.NoticeBuddyAccentPill
import com.example.ui.components.NoticeBuddyActionCard
import com.example.ui.theme.LocalGlassPalette
import com.example.ui.theme.LocalNoticeBuddyRadius
import com.example.ui.theme.LocalNoticeBuddySpacing
import com.example.ui.theme.NoticeMateTheme
import com.example.ui.viewmodel.HomePhaseUiState
import com.example.ui.viewmodel.HomePhaseViewModel
import com.example.ui.viewmodel.NoticeAppRoute
import com.example.ui.viewmodel.SelectedImageDraft
import com.example.ui.viewmodel.SelectedPdfDraft
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun NoticeMateHomeApp(
    viewModel: HomePhaseViewModel,
    enableHardwareCameraBinding: Boolean = true,
    customCameraCaptureTrigger: (() -> Unit)? = null
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val pdfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) {
            viewModel.onPdfPickerCancelled()
        } else {
            val meta = resolveUriMetadata(context, uri)
            viewModel.onPdfFilePicked(
                fileName = meta.first,
                mimeType = meta.second,
                fileSizeBytes = meta.third,
                uriString = uri.toString()
            )
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val meta = resolveUriMetadata(context, uri)
            viewModel.onImageFilePicked(
                fileName = meta.first,
                mimeType = meta.second,
                fileSizeBytes = meta.third,
                uriString = uri.toString()
            )
        }
    }

    BackHandler(enabled = uiState.currentRoute != NoticeAppRoute.HOME || uiState.isProfileMenuOpen) {
        viewModel.navigateBack()
    }

    NoticeMateTheme(darkTheme = uiState.themeMode == AppThemeMode.NIGHT) {
        val glass = LocalGlassPalette.current

        BokehGlassBackground(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .widthIn(max = 640.dp)
                    .align(Alignment.TopCenter)
            ) {
                // Top Liquid Glass Header with App Title & Profile Icon
                TopGlassHeader(
                    currentRoute = uiState.currentRoute,
                    onBackClick = { viewModel.navigateBack() },
                    onProfileClick = { viewModel.toggleProfileMenu() }
                )

                // Phase 6 Non-Intrusive Connectivity Banner (startup offline & live connectivity changes)
                if (!uiState.networkStatus.hasInternetAccess) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .padding(bottom = 8.dp)
                    ) {
                        NetworkConnectivityBanner(
                            networkStatus = uiState.networkStatus,
                            selectedLanguage = uiState.selectedLanguage,
                            onRefreshConnectivity = { viewModel.refreshNetworkConnectivity() }
                        )
                    }
                }

                // Main Content Area
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                ) {
                    when (uiState.currentRoute) {
                        NoticeAppRoute.HOME -> HomepageView(
                            uiState = uiState,
                            onUploadNoticePdfClick = {
                                viewModel.navigateTo(NoticeAppRoute.UPLOAD_PDF_SELECT)
                            },
                            onUploadNoticeImageClick = {
                                viewModel.navigateTo(NoticeAppRoute.UPLOAD_IMAGE_SELECT)
                            },
                            onEnterNoticeTextClick = {
                                viewModel.navigateTo(NoticeAppRoute.ENTER_TEXT_INPUT)
                            },
                            onOpenRecentNoticesClick = {
                                viewModel.navigateTo(NoticeAppRoute.RECENT_NOTICES)
                            }
                        )

                        NoticeAppRoute.RECENT_NOTICES -> RecentNoticesScreen(
                            recentNotices = uiState.recentNotices,
                            selectedLanguage = uiState.selectedLanguage,
                            onOpenNotice = { entry ->
                                viewModel.openRecentNotice(entry)
                            },
                            onDeleteNotice = { entryId ->
                                viewModel.deleteSavedNotice(entryId)
                            },
                            onClearAllNotices = {
                                viewModel.clearAllSavedNotices()
                            },
                            onBackHome = {
                                viewModel.onHomeButtonClicked()
                            }
                        )

                        NoticeAppRoute.ENTER_TEXT_INPUT -> EnterNoticeTextScreen(
                            draftText = uiState.draftNoticeText,
                            validationError = uiState.textValidationError,
                            isProcessing = uiState.isProcessing,
                            savedDescriptionAvailable = uiState.activeConfirmedEntry?.inputType == NoticeInputType.TEXT &&
                                uiState.activeConfirmedEntry?.descriptionResult != null &&
                                uiState.activeConfirmedEntry?.textContent == uiState.draftNoticeText,
                            onViewSavedDescription = {
                                viewModel.navigateTo(NoticeAppRoute.ENTER_TEXT_CONFIRMATION)
                            },
                            onTextChange = viewModel::updateDraftNoticeText,
                            onContinue = { viewModel.submitNoticeText() },
                            onBackHome = { viewModel.onHomeButtonClicked() }
                        )

                        NoticeAppRoute.ENTER_TEXT_CONFIRMATION -> NoticeTextConfirmationScreen(
                            entry = uiState.activeConfirmedEntry,
                            draftText = uiState.draftNoticeText,
                            selectedLanguage = uiState.selectedLanguage,
                            onSelectLanguage = viewModel::setNoticeLanguage,
                            isProcessing = uiState.isProcessing,
                            processingStageMessage = uiState.processingStageMessage,
                            analysisError = uiState.analysisError,
                            requestError = uiState.requestError,
                            networkStatus = uiState.networkStatus,
                            validationGateResult = uiState.validationGateResult,
                            onRetryAnalysis = { viewModel.retryCurrentNoticeAnalysis() },
                            onDeleteCurrentNotice = {
                                uiState.activeConfirmedEntry?.id?.let { id ->
                                    viewModel.deleteSavedNotice(id)
                                    viewModel.onHomeButtonClicked()
                                }
                            },
                            onEditNotice = {
                                viewModel.navigateTo(NoticeAppRoute.ENTER_TEXT_INPUT)
                            },
                            onBackHome = { viewModel.onHomeButtonClicked() }
                        )

                        NoticeAppRoute.UPLOAD_PDF_SELECT -> UploadNoticePdfScreen(
                            selectedPdf = uiState.selectedPdfDraft,
                            validationError = uiState.pdfValidationError,
                            isProcessing = uiState.isProcessing,
                            savedDescriptionAvailable = uiState.activeConfirmedEntry?.inputType == NoticeInputType.PDF &&
                                uiState.activeConfirmedEntry?.descriptionResult != null &&
                                uiState.activeConfirmedEntry?.pdfFileName == uiState.selectedPdfDraft?.fileName,
                            onViewSavedDescription = {
                                viewModel.navigateTo(NoticeAppRoute.UPLOAD_PDF_CONFIRMATION)
                            },
                            onLaunchPdfPicker = {
                                pdfPickerLauncher.launch(arrayOf("application/pdf"))
                            },
                            onRemovePdf = viewModel::removeSelectedPdf,
                            onContinue = { viewModel.submitSelectedPdf() },
                            onBackHome = { viewModel.onHomeButtonClicked() }
                        )

                        NoticeAppRoute.UPLOAD_PDF_CONFIRMATION -> PdfConfirmationScreen(
                            entry = uiState.activeConfirmedEntry,
                            selectedLanguage = uiState.selectedLanguage,
                            onSelectLanguage = viewModel::setNoticeLanguage,
                            isProcessing = uiState.isProcessing,
                            processingStageMessage = uiState.processingStageMessage,
                            analysisError = uiState.analysisError,
                            requestError = uiState.requestError,
                            networkStatus = uiState.networkStatus,
                            validationGateResult = uiState.validationGateResult,
                            extractionResult = uiState.lastPdfExtractionResult,
                            onRetryAnalysis = { viewModel.retryCurrentNoticeAnalysis() },
                            onDeleteCurrentNotice = {
                                uiState.activeConfirmedEntry?.id?.let { id ->
                                    viewModel.deleteSavedNotice(id)
                                    viewModel.onHomeButtonClicked()
                                }
                            },
                            onSelectAnotherPdf = {
                                viewModel.navigateTo(NoticeAppRoute.UPLOAD_PDF_SELECT)
                            },
                            onBackHome = { viewModel.onHomeButtonClicked() }
                        )

                        NoticeAppRoute.UPLOAD_IMAGE_SELECT -> UploadNoticeImageScreen(
                            selectedImage = uiState.selectedImageDraft,
                            validationError = uiState.imageValidationError,
                            isProcessing = uiState.isProcessing,
                            savedDescriptionAvailable = uiState.activeConfirmedEntry?.inputType == NoticeInputType.IMAGE &&
                                uiState.activeConfirmedEntry?.descriptionResult != null &&
                                uiState.activeConfirmedEntry?.pdfFileName == uiState.selectedImageDraft?.fileName,
                            onViewSavedDescription = {
                                viewModel.navigateTo(NoticeAppRoute.UPLOAD_IMAGE_CONFIRMATION)
                            },
                            onOpenCameraScanner = {
                                viewModel.openCameraScanner()
                            },
                            onLaunchImagePicker = {
                                imagePickerLauncher.launch(arrayOf("image/*"))
                            },
                            onRemoveImage = viewModel::removeSelectedImage,
                            onContinue = { viewModel.submitSelectedImage() },
                            onBackHome = { viewModel.onHomeButtonClicked() }
                        )

                        NoticeAppRoute.SCAN_NOTICE_CAMERA -> NoticeBuddyCameraScannerScreen(
                            cameraState = uiState.cameraScanState,
                            selectedLanguage = uiState.selectedLanguage,
                            onPermissionChecked = viewModel::onCameraPermissionChecked,
                            onPermissionResult = viewModel::onCameraPermissionResult,
                            onCameraInitializing = viewModel::onCameraPreviewInitializing,
                            onCameraBound = viewModel::onCameraPreviewBound,
                            onCameraUnbound = viewModel::onCameraPreviewUnbound,
                            onCameraError = viewModel::onCameraError,
                            onRetryCamera = viewModel::retryCameraInitialization,
                            onToggleFlash = { viewModel.toggleCameraFlash() },
                            onToggleAutoCapture = { viewModel.toggleAutoCapture() },
                            onSwitchLens = viewModel::switchCameraLens,
                            onDeviceRotationChanged = viewModel::onDeviceRotationChanged,
                            onFrameAnalyzed = viewModel::onPreviewFrameAnalyzed,
                            onManualCaptureStarted = viewModel::onManualCaptureStarted,
                            onPhotoCaptured = { bitmap, rotationDegrees ->
                                viewModel.onManualCaptureSucceeded(bitmap, rotationDegrees)
                            },
                            onCaptureFailed = viewModel::onManualCaptureFailed,
                            onRetakePhoto = viewModel::onRetakeCapturedPhoto,
                            onAcceptPhoto = { viewModel.onAcceptCapturedPhoto() },
                            onAddAnotherPage = viewModel::onStartAddAnotherPage,
                            onReturnToReview = viewModel::onReturnToMultiPageReview,
                            onSelectPage = viewModel::onSelectScannedPage,
                            onRemovePage = viewModel::onRemoveScannedPage,
                            onMovePageEarlier = viewModel::onMoveScannedPageEarlier,
                            onMovePageLater = viewModel::onMoveScannedPageLater,
                            onRotateClockwise = viewModel::onRotateSelectedPageClockwise,
                            onRotateCounterClockwise = viewModel::onRotateSelectedPageCounterClockwise,
                            onToggleCrop = viewModel::onToggleCropSelectedPage,
                            onTogglePerspective = viewModel::onTogglePerspectiveCorrectionSelectedPage,
                            onResetPageEdits = viewModel::onResetSelectedPageEdits,
                            onRunOcrCheck = viewModel::onRunOcrCheckOnSelectedPage,
                            onOpenGallery = {
                                imagePickerLauncher.launch(arrayOf("image/*"))
                            },
                            onClose = { viewModel.navigateBack() },
                            enableHardwareCameraBinding = enableHardwareCameraBinding,
                            customCaptureTrigger = customCameraCaptureTrigger
                        )

                        NoticeAppRoute.UPLOAD_IMAGE_CONFIRMATION -> ImageConfirmationScreen(
                            entry = uiState.activeConfirmedEntry,
                            selectedLanguage = uiState.selectedLanguage,
                            onSelectLanguage = viewModel::setNoticeLanguage,
                            isProcessing = uiState.isProcessing,
                            processingStageMessage = uiState.processingStageMessage,
                            analysisError = uiState.analysisError,
                            requestError = uiState.requestError,
                            networkStatus = uiState.networkStatus,
                            validationGateResult = uiState.validationGateResult,
                            onRetryAnalysis = { viewModel.retryCurrentNoticeAnalysis() },
                            onDeleteCurrentNotice = {
                                uiState.activeConfirmedEntry?.id?.let { id ->
                                    viewModel.deleteSavedNotice(id)
                                    viewModel.onHomeButtonClicked()
                                }
                            },
                            onSelectAnotherImage = {
                                viewModel.navigateTo(NoticeAppRoute.UPLOAD_IMAGE_SELECT)
                            },
                            onBackHome = { viewModel.onHomeButtonClicked() }
                        )

                        NoticeAppRoute.SETTINGS -> SettingsMainScreen(
                            themeMode = uiState.themeMode,
                            onToggleTheme = viewModel::toggleThemeMode,
                            onOpenApiKeys = {
                                viewModel.navigateTo(NoticeAppRoute.SETTINGS_API_KEYS)
                            },
                            onOpenModel = {
                                viewModel.navigateTo(NoticeAppRoute.SETTINGS_MODEL)
                            },
                            onOpenDebugLogs = {
                                viewModel.navigateTo(NoticeAppRoute.SETTINGS_DEBUG_LOGS)
                            }
                        )

                        NoticeAppRoute.SETTINGS_API_KEYS -> SettingsApiKeysScreen(
                            onBackToSettings = { viewModel.navigateBack() }
                        )

                        NoticeAppRoute.SETTINGS_MODEL -> SettingsModelScreen(
                            onBackToSettings = { viewModel.navigateBack() }
                        )

                        NoticeAppRoute.SETTINGS_DEBUG_LOGS -> SettingsDebugLogsScreen(
                            onBackToSettings = { viewModel.navigateBack() }
                        )
                    }
                }

                // Bottom Liquid Glass Home Dock (hidden on notice-analysis confirmation screens
                // and full-screen camera scanner which provide their own dedicated bottom controls)
                val hideBottomHomeDock =
                    uiState.currentRoute == NoticeAppRoute.ENTER_TEXT_CONFIRMATION ||
                        uiState.currentRoute == NoticeAppRoute.UPLOAD_PDF_CONFIRMATION ||
                        uiState.currentRoute == NoticeAppRoute.UPLOAD_IMAGE_CONFIRMATION ||
                        uiState.currentRoute == NoticeAppRoute.SCAN_NOTICE_CAMERA
                if (!hideBottomHomeDock) {
                    BottomGlassHomeBar(
                        isHomeActive = uiState.currentRoute == NoticeAppRoute.HOME,
                        onHomeClick = { viewModel.onHomeButtonClicked() }
                    )
                }
            }

            // Glassmorphic Profile Menu Modal
            if (uiState.isProfileMenuOpen) {
                GlassModalDialog(
                    onDismissRequest = { viewModel.dismissProfileMenu() }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        GlassIconBadge(
                            icon = Icons.Default.Person,
                            contentDescription = "Profile",
                            size = 44.dp
                        )
                        Column {
                            Text(
                                text = "Profile",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = glass.textPrimary
                            )
                            Text(
                                text = "NoticeBuddy Preferences",
                                style = MaterialTheme.typography.bodySmall,
                                color = glass.textSecondary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = glass.glassBorder.copy(alpha = 0.4f))
                    Spacer(modifier = Modifier.height(14.dp))

                    GlassButton(
                        text = "Settings",
                        icon = Icons.Default.Settings,
                        onClick = { viewModel.openSettingsFromProfile() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("profile_settings_option")
                    )
                }
            }
        }
    }
}

@Composable
private fun TopGlassHeader(
    currentRoute: NoticeAppRoute,
    onBackClick: () -> Unit,
    onProfileClick: () -> Unit
) {
    val glass = LocalGlassPalette.current
    val spacing = LocalNoticeBuddySpacing.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.screenHorizontal, vertical = spacing.screenVertical),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            if (currentRoute != NoticeAppRoute.HOME) {
                GlassIconBadge(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    size = spacing.minTouchTarget,
                    accentTint = glass.accentGlow,
                    onClick = onBackClick,
                    modifier = Modifier.testTag("top_back_button")
                )
            } else {
                GlassIconBadge(
                    icon = Icons.Default.AutoAwesome,
                    contentDescription = "NoticeBuddy",
                    size = 46.dp,
                    accentTint = glass.accentGlow
                )
            }
            Column {
                Text(
                    text = "NoticeBuddy",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = glass.textPrimary,
                    modifier = Modifier.testTag("app_header_title")
                )
                Text(
                    text = "Simple Notice Reader • English & हिंदी",
                    style = MaterialTheme.typography.labelSmall,
                    color = glass.textSecondary
                )
            }
        }

        GlassIconBadge(
            icon = Icons.Default.Person,
            contentDescription = "Profile",
            size = spacing.minTouchTarget,
            accentTint = glass.lavenderAccent,
            onClick = onProfileClick,
            modifier = Modifier.testTag("profile_icon_button")
        )
    }
}

/**
 * NoticeBuddy Home Screen (Phase 2 Design System Showcase):
 * Demonstrates the centralized NoticeBuddy design system with soft sky-blue & pastel background,
 * crisp white translucent glassmorphism cards, electric-blue primary actions, and subtle
 * lavender, pink, and teal accents while preserving all existing actions and testTags.
 */
@Composable
private fun HomepageView(
    uiState: HomePhaseUiState,
    onUploadNoticePdfClick: () -> Unit,
    onUploadNoticeImageClick: () -> Unit,
    onEnterNoticeTextClick: () -> Unit,
    onOpenRecentNoticesClick: () -> Unit
) {
    val glass = LocalGlassPalette.current
    val spacing = LocalNoticeBuddySpacing.current
    val radius = LocalNoticeBuddyRadius.current
    val savedCount = uiState.recentNotices.size
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(vertical = spacing.xxs)
            .testTag("homepage_root"),
        verticalArrangement = Arrangement.spacedBy(spacing.cardSpacing)
    ) {
        // Quick-Glance Glassmorphic Hero Banner
        GlassCard(
            elevated = true,
            cornerRadius = radius.heroCard,
            contentPadding = PaddingValues(horizontal = spacing.cardPadding, vertical = 16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.xs)
            ) {
                NoticeBuddyAccentPill(
                    text = "AI Grounded Summary",
                    accentColor = glass.accentGlow,
                    backgroundColor = Color(0xFFDBEAFE),
                    leadingDotColor = glass.tealAccent
                )
                NoticeBuddyAccentPill(
                    text = "EN • हिंदी",
                    accentColor = glass.lavenderAccent,
                    backgroundColor = glass.lavenderSoft
                )
            }
            Spacer(modifier = Modifier.height(spacing.sm))
            Text(
                text = "Turn official notices into clear action steps",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = glass.textPrimary
            )
            Spacer(modifier = Modifier.height(spacing.xxs))
            Text(
                text = "Upload any PDF, photo, or text notice for an instant, easy summary.",
                style = MaterialTheme.typography.bodySmall,
                color = glass.textSecondary
            )
        }

        // Action 1: Upload Notice PDF (Electric-Blue Primary Accent)
        NoticeBuddyActionCard(
            title = "Upload Notice PDF",
            subtitle = "Read any PDF notice or scanned circular",
            icon = Icons.Default.PictureAsPdf,
            accentColor = glass.accentGlow,
            badgeText = "PDF & Scanned OCR",
            badgeBackgroundColor = Color(0xFFDBEAFE),
            onClick = onUploadNoticePdfClick,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("upload_notice_pdf_button")
        )

        // Action 2: Upload Notice Image (Subtle Pastel Pink Accent)
        NoticeBuddyActionCard(
            title = "Upload Notice Image",
            subtitle = "Pick a photo or screenshot of a notice",
            icon = Icons.Default.Image,
            accentColor = glass.pinkAccent,
            badgeText = "Photo & Screenshot",
            badgeBackgroundColor = glass.pinkSoft,
            onClick = onUploadNoticeImageClick,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("upload_notice_image_button")
        )

        // Action 3: Enter Notice Text (Subtle Teal Accent)
        NoticeBuddyActionCard(
            title = "Enter Notice Text",
            subtitle = "Paste or type notice text directly",
            icon = Icons.Default.EditNote,
            accentColor = glass.tealAccent,
            badgeText = "Direct Text",
            badgeBackgroundColor = glass.tealSoft,
            onClick = onEnterNoticeTextClick,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("enter_notice_text_button")
        )

        // Action 4: Dedicated Recent Notices Card (Subtle Lavender Accent)
        NoticeBuddyActionCard(
            title = if (savedCount > 0) "Recent Notices ($savedCount)" else "Recent Notices",
            subtitle = if (savedCount > 0) {
                "Tap to view or delete saved notices"
            } else {
                "No saved notices yet"
            },
            icon = Icons.Default.History,
            accentColor = glass.lavenderAccent,
            badgeText = if (savedCount > 0) "$savedCount Saved" else "History",
            badgeBackgroundColor = glass.lavenderSoft,
            onClick = onOpenRecentNoticesClick,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("open_recent_notices_button")
        )
    }
}

/**
 * Dedicated Recent Notices Screen (opened when user taps Recent Notices button on Home screen).
 * Supports viewing any saved notice and deleting individual notices or clearing all.
 */
@Composable
private fun RecentNoticesScreen(
    recentNotices: List<RecentNoticeEntry>,
    selectedLanguage: NoticeLanguage,
    onOpenNotice: (RecentNoticeEntry) -> Unit,
    onDeleteNotice: (String) -> Unit,
    onClearAllNotices: () -> Unit,
    onBackHome: () -> Unit
) {
    val glass = LocalGlassPalette.current

    GlassCard(
        elevated = true,
        modifier = Modifier
            .fillMaxSize()
            .testTag("recent_notices_screen")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Recent Notices",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = glass.textPrimary
                )
                Text(
                    text = if (recentNotices.isEmpty()) {
                        "No saved notices"
                    } else {
                        "${recentNotices.size} saved notice(s)"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = glass.textSecondary
                )
            }

            if (recentNotices.isNotEmpty()) {
                GlassButton(
                    text = "Clear All",
                    icon = Icons.Default.DeleteOutline,
                    isPrimary = false,
                    onClick = onClearAllNotices,
                    modifier = Modifier.testTag("clear_all_notices_button")
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        if (recentNotices.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("recent_notices_empty_state"),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    GlassIconBadge(
                        icon = Icons.Default.Description,
                        contentDescription = null,
                        size = 56.dp
                    )
                    Text(
                        text = "No saved notices yet",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = glass.textPrimary
                    )
                    Text(
                        text = "Upload a PDF, Image, or Text notice from the Home screen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = glass.textSecondary
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(recentNotices, key = { it.id }) { entry ->
                    RecentNoticeItemRow(
                        entry = entry,
                        selectedLanguage = selectedLanguage,
                        onClick = { onOpenNotice(entry) },
                        onDelete = { onDeleteNotice(entry.id) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        GlassButton(
            text = "Back to Home",
            icon = Icons.Default.Home,
            isPrimary = false,
            onClick = onBackHome,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("recent_notices_back_home_button")
        )
    }
}

@Composable
private fun RecentNoticeItemRow(
    entry: RecentNoticeEntry,
    selectedLanguage: NoticeLanguage,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val glass = LocalGlassPalette.current
    val formattedDate = rememberFormattedTimestamp(entry.timestamp)
    val displayTitle = entry.descriptionResult?.displayTitle(selectedLanguage, entry.title) ?: entry.title
    val previewSummary = entry.descriptionResult?.displaySummary(selectedLanguage).orEmpty()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(glass.glassSurfaceElevated)
            .border(1.dp, glass.glassBorder.copy(alpha = 0.55f), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag("recent_notice_item_${entry.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        GlassIconBadge(
            icon = when (entry.inputType) {
                NoticeInputType.PDF -> Icons.Default.PictureAsPdf
                NoticeInputType.IMAGE -> Icons.Default.Image
                NoticeInputType.TEXT -> Icons.Default.Description
            },
            contentDescription = entry.inputType.label,
            size = 42.dp
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = displayTitle,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = glass.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (previewSummary.isNotBlank()) {
                Text(
                    text = previewSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = glass.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = entry.inputType.label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = glass.accentGlow
                )
                Text(
                    text = "•",
                    style = MaterialTheme.typography.labelSmall,
                    color = glass.textSecondary
                )
                Text(
                    text = formattedDate,
                    style = MaterialTheme.typography.labelSmall,
                    color = glass.textSecondary
                )
            }
        }

        IconButton(
            onClick = onDelete,
            modifier = Modifier
                .size(48.dp)
                .testTag("delete_notice_button_${entry.id}")
        ) {
            Icon(
                imageVector = Icons.Default.DeleteOutline,
                contentDescription = "Delete notice",
                tint = Color(0xFFF87171)
            )
        }
    }
}

@Composable
private fun EnterNoticeTextScreen(
    draftText: String,
    validationError: String?,
    isProcessing: Boolean,
    savedDescriptionAvailable: Boolean,
    onViewSavedDescription: () -> Unit,
    onTextChange: (String) -> Unit,
    onContinue: () -> Unit,
    onBackHome: () -> Unit
) {
    val glass = LocalGlassPalette.current

    GlassCard(
        elevated = true,
        modifier = Modifier
            .fillMaxSize()
            .testTag("enter_notice_text_screen")
    ) {
        Text(
            text = "Enter Notice Text",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = glass.textPrimary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Paste or type the notice below for a simple explanation.",
            style = MaterialTheme.typography.bodySmall,
            color = glass.textSecondary
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = draftText,
            onValueChange = onTextChange,
            enabled = !isProcessing,
            placeholder = {
                Text(
                    text = "Paste or type notice text here...",
                    color = glass.textSecondary.copy(alpha = 0.7f)
                )
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = glass.textPrimary,
                unfocusedTextColor = glass.textPrimary,
                focusedBorderColor = glass.accentGlow,
                unfocusedBorderColor = glass.glassBorder
            ),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag("notice_text_input")
        )

        if (validationError != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = validationError,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFF87171),
                modifier = Modifier.testTag("text_validation_error")
            )
        }

        if (savedDescriptionAvailable && !isProcessing) {
            Spacer(modifier = Modifier.height(10.dp))
            GlassButton(
                text = "View Saved Explanation",
                isPrimary = false,
                onClick = onViewSavedDescription,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("view_saved_text_description_button")
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            GlassButton(
                text = "Back",
                isPrimary = false,
                enabled = !isProcessing,
                onClick = onBackHome,
                modifier = Modifier
                    .weight(1f)
                    .testTag("back_from_text_button")
            )
            GlassButton(
                text = if (isProcessing) "Analyzing..." else "Explain Notice",
                isPrimary = true,
                enabled = !isProcessing,
                onClick = onContinue,
                modifier = Modifier
                    .weight(1.4f)
                    .testTag("continue_text_button")
            )
        }
    }
}

@Composable
private fun NoticeTextConfirmationScreen(
    entry: RecentNoticeEntry?,
    draftText: String,
    selectedLanguage: NoticeLanguage,
    onSelectLanguage: (NoticeLanguage) -> Unit,
    isProcessing: Boolean,
    processingStageMessage: String?,
    analysisError: String?,
    requestError: NoticeRequestError? = null,
    networkStatus: NetworkConnectivityStatus = NetworkConnectivityStatus.ONLINE,
    validationGateResult: NoticeValidationGateResult? = null,
    onRetryAnalysis: () -> Unit,
    onDeleteCurrentNotice: () -> Unit,
    onEditNotice: () -> Unit,
    onBackHome: () -> Unit
) {
    val glass = LocalGlassPalette.current
    val radius = LocalNoticeBuddyRadius.current
    val displayedText = entry?.textContent?.ifBlank { draftText } ?: draftText
    val fallbackTitle = entry?.title ?: RecentNoticeStorage.deriveTitleFromNoticeText(displayedText)
    val description = entry?.descriptionResult
    val displayedTitle = description?.displayTitle(selectedLanguage, fallbackTitle) ?: fallbackTitle
    var showOriginalText by remember { mutableStateOf(false) }
    val isHindi = selectedLanguage == NoticeLanguage.HINDI
    val isGateBlocked = validationGateResult != null &&
        !validationGateResult.isAllowedForNoticeAnalysis &&
        validationGateResult.status != NoticeValidationStatus.CLASSIFICATION_ERROR

    NoticeAnalysisScreenScaffold(
        screenTestTag = "notice_text_confirmation_screen",
        sourceBadgeLabel = if (isHindi) "टेक्स्ट नोटिस" else "Text Notice",
        sourceBadgeColor = glass.tealAccent,
        sourceBadgeBackground = glass.tealSoft,
        title = displayedTitle,
        selectedLanguage = selectedLanguage,
        onSelectLanguage = onSelectLanguage,
        canDelete = entry != null && !isProcessing,
        onDelete = onDeleteCurrentNotice,
        isProcessing = isProcessing,
        secondaryActionText = "Edit Text",
        secondaryActionIcon = Icons.Default.EditNote,
        secondaryActionTestTag = "edit_notice_text_button",
        onSecondaryAction = onEditNotice,
        primaryActionText = "Done",
        primaryActionTestTag = "confirm_return_home_button",
        onPrimaryAction = onBackHome
    ) {
        if (isProcessing) {
            AnalysisLoadingCard(
                message = processingStageMessage ?: "Summarizing notice in simple words...",
                selectedLanguage = selectedLanguage
            )
        }

        if (isGateBlocked && !isProcessing) {
            NoticeValidationGateCard(
                gateResult = validationGateResult!!,
                selectedLanguage = selectedLanguage,
                chooseAnotherText = if (isHindi) "दूसरा नोटिस दर्ज करें" else "Enter Another Notice",
                onChooseAnother = onEditNotice
            )
        }

        if (analysisError != null && !isProcessing && !isGateBlocked) {
            AnalysisErrorCard(
                errorMessage = analysisError,
                requestError = requestError,
                networkStatus = networkStatus,
                hasPreservedResult = description != null,
                selectedLanguage = selectedLanguage,
                onRetry = onRetryAnalysis
            )
        }

        if (!isProcessing && analysisError == null && !isGateBlocked && description == null) {
            AnalysisEmptyStateCard(
                selectedLanguage = selectedLanguage,
                onRetry = onRetryAnalysis
            )
        }

        if (description != null) {
            SimplifiedNoticeResultSection(
                description = description,
                selectedLanguage = selectedLanguage
            )
        }

        // Collapsible Original Notice Text Card (preserves confirmed_notice_text_body)
        GlassCard(
            cornerRadius = radius.innerCard,
            contentPadding = PaddingValues(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { showOriginalText = !showOriginalText }
                    .testTag("original_notice_text_toggle_row"),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    GlassIconBadge(
                        icon = Icons.Default.Description,
                        contentDescription = null,
                        size = 36.dp,
                        accentTint = glass.tealAccent
                    )
                    Column {
                        Text(
                            text = if (isHindi) {
                                "मूल नोटिस टेक्स्ट (Original Notice Text)"
                            } else {
                                "Original Notice Text"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = glass.textPrimary
                        )
                        Text(
                            text = if (showOriginalText || description == null) {
                                if (isHindi) "छुपाने के लिए टैप करें" else "Tap to collapse source text"
                            } else {
                                if (isHindi) "पूरा मूल टेक्स्ट देखने के लिए टैप करें" else "Tap to view full source text"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = glass.textSecondary
                        )
                    }
                }
                NoticeBuddyAccentPill(
                    text = if (showOriginalText || description == null) "Hide" else "Show",
                    accentColor = glass.tealAccent,
                    backgroundColor = glass.tealSoft
                )
            }
            if (showOriginalText || description == null) {
                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = glass.glassBorder.copy(alpha = 0.45f))
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = displayedText,
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 22.sp,
                    color = glass.textPrimary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("confirmed_notice_text_body")
                )
            }
        }
    }
}

@Composable
private fun UploadNoticePdfScreen(
    selectedPdf: SelectedPdfDraft?,
    validationError: String?,
    isProcessing: Boolean,
    savedDescriptionAvailable: Boolean,
    onViewSavedDescription: () -> Unit,
    onLaunchPdfPicker: () -> Unit,
    onRemovePdf: () -> Unit,
    onContinue: () -> Unit,
    onBackHome: () -> Unit
) {
    val glass = LocalGlassPalette.current

    GlassCard(
        elevated = true,
        modifier = Modifier
            .fillMaxSize()
            .testTag("upload_notice_pdf_screen")
    ) {
        Text(
            text = "Upload Notice PDF",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = glass.textPrimary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Works with both regular PDFs and scanned image PDFs.",
            style = MaterialTheme.typography.bodySmall,
            color = glass.textSecondary
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (selectedPdf == null) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(glass.glassSurface)
                    .border(1.4.dp, glass.glassBorder, RoundedCornerShape(22.dp))
                    .clickable(enabled = !isProcessing, onClick = onLaunchPdfPicker)
                    .padding(24.dp)
                    .testTag("select_pdf_dropzone"),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    GlassIconBadge(
                        icon = Icons.Default.UploadFile,
                        contentDescription = "Select PDF File",
                        size = 62.dp
                    )
                    Text(
                        text = "Tap to Choose Notice PDF",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = glass.textPrimary
                    )
                    Text(
                        text = "Supports digital & scanned image PDFs (up to 15 MB)",
                        style = MaterialTheme.typography.bodySmall,
                        color = glass.textSecondary
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("selected_pdf_info_card")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        GlassIconBadge(
                            icon = Icons.Default.PictureAsPdf,
                            contentDescription = "Selected PDF",
                            size = 46.dp
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = selectedPdf.fileName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = glass.textPrimary,
                                modifier = Modifier.testTag("selected_pdf_filename")
                            )
                            Text(
                                text = RecentNoticeStorage.formatFileSize(selectedPdf.fileSizeBytes),
                                style = MaterialTheme.typography.bodySmall,
                                color = glass.textSecondary,
                                modifier = Modifier.testTag("selected_pdf_size")
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        GlassButton(
                            text = "Change",
                            icon = Icons.Default.Refresh,
                            isPrimary = false,
                            enabled = !isProcessing,
                            onClick = onLaunchPdfPicker,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("replace_pdf_button")
                        )
                        GlassButton(
                            text = "Remove",
                            icon = Icons.Default.DeleteOutline,
                            isPrimary = false,
                            enabled = !isProcessing,
                            onClick = onRemovePdf,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("remove_pdf_button")
                        )
                    }
                }

                if (savedDescriptionAvailable && !isProcessing) {
                    GlassButton(
                        text = "View Saved Explanation",
                        isPrimary = false,
                        onClick = onViewSavedDescription,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("view_saved_pdf_description_button")
                    )
                }
            }
        }

        if (validationError != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = validationError,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFF87171),
                modifier = Modifier.testTag("pdf_validation_error")
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            GlassButton(
                text = "Back",
                isPrimary = false,
                enabled = !isProcessing,
                onClick = onBackHome,
                modifier = Modifier
                    .weight(1f)
                    .testTag("back_from_pdf_button")
            )
            if (selectedPdf != null) {
                GlassButton(
                    text = if (isProcessing) "Reading..." else "Explain PDF",
                    isPrimary = true,
                    enabled = !isProcessing,
                    onClick = onContinue,
                    modifier = Modifier
                        .weight(1.4f)
                        .testTag("continue_pdf_button")
                )
            }
        }
    }
}

@Composable
private fun PdfConfirmationScreen(
    entry: RecentNoticeEntry?,
    selectedLanguage: NoticeLanguage,
    onSelectLanguage: (NoticeLanguage) -> Unit,
    isProcessing: Boolean,
    processingStageMessage: String?,
    analysisError: String?,
    requestError: NoticeRequestError? = null,
    networkStatus: NetworkConnectivityStatus = NetworkConnectivityStatus.ONLINE,
    validationGateResult: NoticeValidationGateResult? = null,
    extractionResult: PdfExtractionResult?,
    onRetryAnalysis: () -> Unit,
    onDeleteCurrentNotice: () -> Unit,
    onSelectAnotherPdf: () -> Unit,
    onBackHome: () -> Unit
) {
    val glass = LocalGlassPalette.current
    val radius = LocalNoticeBuddyRadius.current
    val fileName = entry?.pdfFileName?.ifBlank { entry.title } ?: "Selected_Notice.pdf"
    val description = entry?.descriptionResult
    val displayedTitle = description?.displayTitle(selectedLanguage, fileName) ?: fileName
    val isHindi = selectedLanguage == NoticeLanguage.HINDI
    val isGateBlocked = validationGateResult != null &&
        !validationGateResult.isAllowedForNoticeAnalysis &&
        validationGateResult.status != NoticeValidationStatus.CLASSIFICATION_ERROR

    NoticeAnalysisScreenScaffold(
        screenTestTag = "pdf_confirmation_screen",
        sourceBadgeLabel = if (isHindi) "पीडीएफ नोटिस" else "PDF Notice",
        sourceBadgeColor = glass.electricBlue,
        sourceBadgeBackground = glass.electricBlueSoft,
        title = displayedTitle,
        selectedLanguage = selectedLanguage,
        onSelectLanguage = onSelectLanguage,
        canDelete = entry != null && !isProcessing,
        onDelete = onDeleteCurrentNotice,
        isProcessing = isProcessing,
        secondaryActionText = "Another PDF",
        secondaryActionIcon = Icons.Default.PictureAsPdf,
        secondaryActionTestTag = "another_pdf_button",
        onSecondaryAction = onSelectAnotherPdf,
        primaryActionText = "Done",
        primaryActionTestTag = "pdf_confirm_return_home_button",
        onPrimaryAction = onBackHome
    ) {
        if (isProcessing) {
            AnalysisLoadingCard(
                message = processingStageMessage ?: "Reading PDF pages and generating simple summary...",
                selectedLanguage = selectedLanguage
            )
        }

        if (isGateBlocked && !isProcessing) {
            NoticeValidationGateCard(
                gateResult = validationGateResult!!,
                selectedLanguage = selectedLanguage,
                chooseAnotherText = if (isHindi) "दूसरा पीडीएफ चुनें" else "Choose Another PDF",
                onChooseAnother = onSelectAnotherPdf
            )
        }

        if (analysisError != null && !isProcessing && !isGateBlocked) {
            AnalysisErrorCard(
                errorMessage = analysisError,
                requestError = requestError,
                networkStatus = networkStatus,
                hasPreservedResult = description != null,
                selectedLanguage = selectedLanguage,
                onRetry = onRetryAnalysis
            )
        }

        if (!isProcessing && analysisError == null && !isGateBlocked && description == null) {
            AnalysisEmptyStateCard(
                selectedLanguage = selectedLanguage,
                onRetry = onRetryAnalysis
            )
        }

        if (description != null) {
            SimplifiedNoticeResultSection(
                description = description,
                selectedLanguage = selectedLanguage
            )
        }

        // Source PDF Document Metadata Card
        GlassCard(
            cornerRadius = radius.innerCard,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    GlassIconBadge(
                        icon = Icons.Default.PictureAsPdf,
                        contentDescription = null,
                        size = 34.dp,
                        accentTint = glass.electricBlue
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isHindi) "स्रोत पीडीएफ फ़ाइल" else "Source PDF Document",
                            style = MaterialTheme.typography.labelSmall,
                            color = glass.textSecondary
                        )
                        Text(
                            text = fileName,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = glass.textPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("confirmed_pdf_filename")
                        )
                    }
                }
                if (extractionResult != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(glass.electricBlueSoft)
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "${extractionResult.processedPageCount}p",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = glass.electricBlue,
                            modifier = Modifier.testTag("pdf_extraction_summary")
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UploadNoticeImageScreen(
    selectedImage: SelectedImageDraft?,
    validationError: String?,
    isProcessing: Boolean,
    savedDescriptionAvailable: Boolean,
    onViewSavedDescription: () -> Unit,
    onOpenCameraScanner: () -> Unit,
    onLaunchImagePicker: () -> Unit,
    onRemoveImage: () -> Unit,
    onContinue: () -> Unit,
    onBackHome: () -> Unit
) {
    val glass = LocalGlassPalette.current

    GlassCard(
        elevated = true,
        modifier = Modifier
            .fillMaxSize()
            .testTag("upload_notice_image_screen")
    ) {
        Text(
            text = "Scan or Upload Notice Image",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = glass.textPrimary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Scan a physical notice with your camera or choose a photo from your gallery.",
            style = MaterialTheme.typography.bodySmall,
            color = glass.textSecondary
        )

        Spacer(modifier = Modifier.height(14.dp))

        if (selectedImage == null) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Primary Camera Scanner Action Card
                NoticeBuddyActionCard(
                    title = "Scan Notice with Camera",
                    subtitle = "Capture a physical notice board or circular",
                    icon = Icons.Default.CameraAlt,
                    accentColor = glass.electricBlue,
                    badgeText = "Live CameraX",
                    badgeBackgroundColor = glass.electricBlueSoft,
                    onClick = {
                        if (!isProcessing) onOpenCameraScanner()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("open_camera_scanner_button")
                )

                // Gallery Selection Dropzone
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .background(glass.glassSurface)
                        .border(1.4.dp, glass.glassBorder, RoundedCornerShape(22.dp))
                        .clickable(enabled = !isProcessing, onClick = onLaunchImagePicker)
                        .padding(20.dp)
                        .testTag("select_image_dropzone"),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        GlassIconBadge(
                            icon = Icons.Default.Image,
                            contentDescription = "Select Notice Image",
                            size = 56.dp,
                            accentTint = glass.secondaryGlow
                        )
                        Text(
                            text = "Tap to Choose Notice Photo",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = glass.textPrimary
                        )
                        Text(
                            text = "Supports JPG, PNG & WEBP photos or screenshots",
                            style = MaterialTheme.typography.bodySmall,
                            color = glass.textSecondary
                        )
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("selected_image_info_card")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        GlassIconBadge(
                            icon = Icons.Default.Image,
                            contentDescription = "Selected Image",
                            size = 46.dp,
                            accentTint = glass.secondaryGlow
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = selectedImage.fileName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = glass.textPrimary,
                                modifier = Modifier.testTag("selected_image_filename")
                            )
                            Text(
                                text = RecentNoticeStorage.formatFileSize(selectedImage.fileSizeBytes),
                                style = MaterialTheme.typography.bodySmall,
                                color = glass.textSecondary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        GlassButton(
                            text = "Scan",
                            icon = Icons.Default.CameraAlt,
                            isPrimary = false,
                            enabled = !isProcessing,
                            onClick = onOpenCameraScanner,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("rescan_camera_button")
                        )
                        GlassButton(
                            text = "Change",
                            icon = Icons.Default.Refresh,
                            isPrimary = false,
                            enabled = !isProcessing,
                            onClick = onLaunchImagePicker,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("replace_image_button")
                        )
                        GlassButton(
                            text = "Remove",
                            icon = Icons.Default.DeleteOutline,
                            isPrimary = false,
                            enabled = !isProcessing,
                            onClick = onRemoveImage,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("remove_image_button")
                        )
                    }
                }

                if (savedDescriptionAvailable && !isProcessing) {
                    GlassButton(
                        text = "View Saved Explanation",
                        isPrimary = false,
                        onClick = onViewSavedDescription,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("view_saved_image_description_button")
                    )
                }
            }
        }

        if (validationError != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = validationError,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFF87171),
                modifier = Modifier.testTag("image_validation_error")
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            GlassButton(
                text = "Back",
                isPrimary = false,
                enabled = !isProcessing,
                onClick = onBackHome,
                modifier = Modifier
                    .weight(1f)
                    .testTag("back_from_image_button")
            )
            if (selectedImage != null) {
                GlassButton(
                    text = if (isProcessing) "Reading..." else "Explain Image",
                    isPrimary = true,
                    enabled = !isProcessing,
                    onClick = onContinue,
                    modifier = Modifier
                        .weight(1.4f)
                        .testTag("continue_image_button")
                )
            }
        }
    }
}

@Composable
private fun ImageConfirmationScreen(
    entry: RecentNoticeEntry?,
    selectedLanguage: NoticeLanguage,
    onSelectLanguage: (NoticeLanguage) -> Unit,
    isProcessing: Boolean,
    processingStageMessage: String?,
    analysisError: String?,
    requestError: NoticeRequestError? = null,
    networkStatus: NetworkConnectivityStatus = NetworkConnectivityStatus.ONLINE,
    validationGateResult: NoticeValidationGateResult? = null,
    onRetryAnalysis: () -> Unit,
    onDeleteCurrentNotice: () -> Unit,
    onSelectAnotherImage: () -> Unit,
    onBackHome: () -> Unit
) {
    val glass = LocalGlassPalette.current
    val radius = LocalNoticeBuddyRadius.current
    val fileName = entry?.pdfFileName?.ifBlank { entry.title } ?: "Notice_Image.jpg"
    val description = entry?.descriptionResult
    val displayedTitle = description?.displayTitle(selectedLanguage, fileName) ?: fileName
    val isHindi = selectedLanguage == NoticeLanguage.HINDI
    val isGateBlocked = validationGateResult != null &&
        !validationGateResult.isAllowedForNoticeAnalysis &&
        validationGateResult.status != NoticeValidationStatus.CLASSIFICATION_ERROR

    NoticeAnalysisScreenScaffold(
        screenTestTag = "image_confirmation_screen",
        sourceBadgeLabel = if (isHindi) "इमेज नोटिस" else "Image Notice",
        sourceBadgeColor = glass.pinkAccent,
        sourceBadgeBackground = glass.pinkSoft,
        title = displayedTitle,
        selectedLanguage = selectedLanguage,
        onSelectLanguage = onSelectLanguage,
        canDelete = entry != null && !isProcessing,
        onDelete = onDeleteCurrentNotice,
        isProcessing = isProcessing,
        secondaryActionText = "Another Image",
        secondaryActionIcon = Icons.Default.Image,
        secondaryActionTestTag = "another_image_button",
        onSecondaryAction = onSelectAnotherImage,
        primaryActionText = "Done",
        primaryActionTestTag = "image_confirm_return_home_button",
        onPrimaryAction = onBackHome
    ) {
        if (isProcessing) {
            AnalysisLoadingCard(
                message = processingStageMessage ?: "Reading notice image and generating simple summary...",
                selectedLanguage = selectedLanguage
            )
        }

        if (isGateBlocked && !isProcessing) {
            NoticeValidationGateCard(
                gateResult = validationGateResult!!,
                selectedLanguage = selectedLanguage,
                chooseAnotherText = if (isHindi) "दूसरी इमेज चुनें" else "Choose Another Image",
                onChooseAnother = onSelectAnotherImage
            )
        }

        if (analysisError != null && !isProcessing && !isGateBlocked) {
            AnalysisErrorCard(
                errorMessage = analysisError,
                requestError = requestError,
                networkStatus = networkStatus,
                hasPreservedResult = description != null,
                selectedLanguage = selectedLanguage,
                onRetry = onRetryAnalysis
            )
        }

        if (!isProcessing && analysisError == null && !isGateBlocked && description == null) {
            AnalysisEmptyStateCard(
                selectedLanguage = selectedLanguage,
                onRetry = onRetryAnalysis
            )
        }

        if (description != null) {
            SimplifiedNoticeResultSection(
                description = description,
                selectedLanguage = selectedLanguage
            )
        }

        // Source Image Document Metadata Card
        GlassCard(
            cornerRadius = radius.innerCard,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                GlassIconBadge(
                    icon = Icons.Default.Image,
                    contentDescription = null,
                    size = 34.dp,
                    accentTint = glass.pinkAccent
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isHindi) "स्रोत इमेज फ़ाइल" else "Source Notice Image",
                        style = MaterialTheme.typography.labelSmall,
                        color = glass.textSecondary
                    )
                    Text(
                        text = fileName,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = glass.textPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("confirmed_image_filename")
                    )
                }
            }
        }
    }
}

/**
 * Unified NoticeBuddy Notice-Analysis Screen Scaffold:
 * - Keeps the Notice Title and English/Hindi language selector (`LiquidLanguageTogglePill`) easy to find.
 * - Adapts gracefully to compact/landscape heights (`BoxWithConstraints`) so scrollable content is never crushed.
 * - Separates the scrollable analysis cards from the docked bottom action bar (`Another PDF` / `Another Image` / `Edit Text` + `Done`)
 *   so bottom actions never overlap or hide scrollable content and the final card can always be scrolled into full view.
 * - Respects Android navigation bars, IME insets, and safe drawing areas.
 */
@Composable
private fun NoticeAnalysisScreenScaffold(
    screenTestTag: String,
    sourceBadgeLabel: String,
    sourceBadgeColor: Color,
    sourceBadgeBackground: Color,
    title: String,
    selectedLanguage: NoticeLanguage,
    onSelectLanguage: (NoticeLanguage) -> Unit,
    canDelete: Boolean,
    onDelete: () -> Unit,
    isProcessing: Boolean,
    secondaryActionText: String,
    secondaryActionIcon: ImageVector,
    secondaryActionTestTag: String,
    onSecondaryAction: () -> Unit,
    primaryActionText: String,
    primaryActionTestTag: String,
    onPrimaryAction: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val spacing = LocalNoticeBuddySpacing.current
    val radius = LocalNoticeBuddyRadius.current
    val scrollState = rememberScrollState()

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .navigationBarsPadding()
            .testTag(screenTestTag)
    ) {
        val isCompactHeight = maxHeight < 420.dp

        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            NoticeResultHeaderBar(
                sourceBadgeLabel = sourceBadgeLabel,
                sourceBadgeColor = sourceBadgeColor,
                sourceBadgeBackground = sourceBadgeBackground,
                title = title,
                selectedLanguage = selectedLanguage,
                onSelectLanguage = onSelectLanguage,
                canDelete = canDelete,
                onDelete = onDelete,
                isCompact = isCompactHeight
            )

            Spacer(modifier = Modifier.height(if (isCompactHeight) 8.dp else spacing.itemGap))

            // Dedicated scrollable content region — strictly above the bottom action dock
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 2.dp, vertical = 2.dp)
                    .testTag("notice_analysis_scroll_container"),
                verticalArrangement = Arrangement.spacedBy(spacing.itemGap)
            ) {
                content()

                // Bottom breathing space so the last card's shadow and content clear the scroll boundary completely
                Spacer(modifier = Modifier.height(16.dp))
            }

            Spacer(modifier = Modifier.height(if (isCompactHeight) 6.dp else 10.dp))

            // Non-overlapping docked bottom action bar
            GlassCard(
                elevated = true,
                cornerRadius = radius.innerCard,
                contentPadding = PaddingValues(
                    horizontal = 14.dp,
                    vertical = if (isCompactHeight) 8.dp else 12.dp
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = if (isCompactHeight) 4.dp else 8.dp)
                    .testTag("notice_bottom_action_bar")
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GlassButton(
                        text = secondaryActionText,
                        icon = secondaryActionIcon,
                        isPrimary = false,
                        enabled = !isProcessing,
                        onClick = onSecondaryAction,
                        modifier = Modifier
                            .weight(1f)
                            .testTag(secondaryActionTestTag)
                    )
                    GlassButton(
                        text = primaryActionText,
                        icon = Icons.Default.CheckCircle,
                        isPrimary = true,
                        enabled = !isProcessing,
                        onClick = onPrimaryAction,
                        modifier = Modifier
                            .weight(1f)
                            .testTag(primaryActionTestTag)
                    )
                }
            }
        }
    }
}

@Composable
private fun NoticeResultHeaderBar(
    sourceBadgeLabel: String,
    sourceBadgeColor: Color,
    sourceBadgeBackground: Color,
    title: String,
    selectedLanguage: NoticeLanguage,
    onSelectLanguage: (NoticeLanguage) -> Unit,
    canDelete: Boolean,
    onDelete: () -> Unit,
    isCompact: Boolean = false
) {
    val glass = LocalGlassPalette.current
    val radius = LocalNoticeBuddyRadius.current
    var isTitleExpanded by remember(title) { mutableStateOf(false) }
    val isLongTitle = title.length > 110

    GlassCard(
        elevated = true,
        cornerRadius = radius.card,
        contentPadding = PaddingValues(
            horizontal = 16.dp,
            vertical = if (isCompact) 10.dp else 14.dp
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            NoticeBuddyAccentPill(
                text = sourceBadgeLabel,
                accentColor = sourceBadgeColor,
                backgroundColor = sourceBadgeBackground
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LiquidLanguageTogglePill(
                    selectedLanguage = selectedLanguage,
                    onSelectLanguage = onSelectLanguage
                )

                if (canDelete) {
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFEF4444).copy(alpha = 0.10f))
                            .testTag("delete_current_notice_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Delete saved notice",
                            tint = Color(0xFFEF4444)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(if (isCompact) 6.dp else 10.dp))

        val collapsedMaxLines = if (isCompact) 1 else 3
        Text(
            text = title,
            style = if (isCompact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
            color = glass.textPrimary,
            maxLines = if (isTitleExpanded || (!isLongTitle && !isCompact)) Int.MAX_VALUE else collapsedMaxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (isLongTitle || isCompact) {
                        Modifier.clickable { isTitleExpanded = !isTitleExpanded }
                    } else {
                        Modifier
                    }
                )
                .testTag("notice_result_title")
        )

        if (isLongTitle && !isCompact) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (isTitleExpanded) "Show compact title" else "Tap title to read full heading",
                style = MaterialTheme.typography.labelSmall,
                color = glass.electricBlue,
                modifier = Modifier.clickable { isTitleExpanded = !isTitleExpanded }
            )
        }
    }
}

@Composable
private fun AnalysisLoadingCard(
    message: String,
    selectedLanguage: NoticeLanguage = NoticeLanguage.ENGLISH
) {
    val glass = LocalGlassPalette.current
    val isHindi = selectedLanguage == NoticeLanguage.HINDI
    GlassCard(
        elevated = true,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("analysis_loading_card")
    ) {
        NoticeBuddyAccentPill(
            text = if (isHindi) "विश्लेषण जारी है" else "Analyzing Notice",
            accentColor = glass.electricBlue,
            backgroundColor = glass.electricBlueSoft
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(glass.electricBlueSoft),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    color = glass.electricBlue,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(24.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isHindi) "नोटिस पढ़ा जा रहा है..." else "Reading & Summarizing Notice...",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = glass.textPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = glass.textSecondary,
                    modifier = Modifier.testTag("analysis_loading_message")
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider(color = glass.glassBorder.copy(alpha = 0.45f))
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = if (isHindi) {
                "महत्वपूर्ण तिथियाँ, अगले कदम और आवश्यक दस्तावेज़ निकाले जा रहे हैं।"
            } else {
                "Extracting key dates, action steps, eligibility, and required documents."
            },
            style = MaterialTheme.typography.labelSmall,
            color = glass.textSecondary
        )
    }
}

@Composable
private fun NetworkConnectivityBanner(
    networkStatus: NetworkConnectivityStatus,
    selectedLanguage: NoticeLanguage = NoticeLanguage.ENGLISH,
    onRefreshConnectivity: () -> Unit
) {
    val glass = LocalGlassPalette.current
    val radius = LocalNoticeBuddyRadius.current
    val isHindi = selectedLanguage == NoticeLanguage.HINDI
    val bannerAccent = if (networkStatus == NetworkConnectivityStatus.CONNECTED_NO_INTERNET) {
        Color(0xFFD97706)
    } else {
        Color(0xFFE11D48)
    }
    val bannerSoftBg = if (networkStatus == NetworkConnectivityStatus.CONNECTED_NO_INTERNET) {
        Color(0xFFFEF3C7)
    } else {
        Color(0xFFFFE4E6)
    }

    GlassCard(
        elevated = false,
        cornerRadius = radius.innerCard,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("network_status_banner")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            GlassIconBadge(
                icon = Icons.Default.WarningAmber,
                contentDescription = networkStatus.badgeLabel(selectedLanguage),
                size = 36.dp,
                accentTint = bannerAccent
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .semantics(mergeDescendants = true) {}
                            .testTag("network_status_badge")
                    ) {
                        NoticeBuddyAccentPill(
                            text = networkStatus.badgeLabel(selectedLanguage),
                            accentColor = bannerAccent,
                            backgroundColor = bannerSoftBg
                        )
                    }
                    Text(
                        text = networkStatus.bannerTitle(selectedLanguage),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = glass.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("network_status_title")
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = networkStatus.bannerMessage(selectedLanguage),
                    style = MaterialTheme.typography.bodySmall,
                    color = glass.textSecondary,
                    modifier = Modifier.testTag("network_status_message")
                )
            }
            IconButton(
                onClick = onRefreshConnectivity,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("refresh_connectivity_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = if (isHindi) "कनेक्शन जांचें" else "Check connection",
                    tint = bannerAccent
                )
            }
        }
    }
}

@Composable
private fun AnalysisErrorCard(
    errorMessage: String,
    requestError: NoticeRequestError? = null,
    networkStatus: NetworkConnectivityStatus = NetworkConnectivityStatus.ONLINE,
    hasPreservedResult: Boolean = false,
    selectedLanguage: NoticeLanguage = NoticeLanguage.ENGLISH,
    onRetry: () -> Unit
) {
    val glass = LocalGlassPalette.current
    val isHindi = selectedLanguage == NoticeLanguage.HINDI
    val errorAccent = when (requestError?.category) {
        NoticeRequestErrorCategory.REQUEST_TIMEOUT -> Color(0xFFD97706)
        NoticeRequestErrorCategory.UPLOAD_FAILURE -> Color(0xFFDC2626)
        else -> Color(0xFFE11D48)
    }
    val errorSoftBg = when (requestError?.category) {
        NoticeRequestErrorCategory.REQUEST_TIMEOUT -> Color(0xFFFEF3C7)
        else -> Color(0xFFFFE4E6)
    }

    val badgeText = requestError?.displayBadge(selectedLanguage)
        ?: if (isHindi) "विश्लेषण रुका" else "Analysis Interrupted"
    val headingText = requestError?.displayTitle(selectedLanguage)
        ?: if (isHindi) "नोटिस का विश्लेषण नहीं हो सका" else "Could Not Read Notice"
    val displayedMessage = if (isHindi && requestError != null) {
        requestError.displayMessage(selectedLanguage)
    } else {
        requestError?.messageEn?.ifBlank { errorMessage } ?: errorMessage
    }
    val isReconnectedAfterOfflineError =
        requestError?.category == NoticeRequestErrorCategory.NO_INTERNET &&
            networkStatus == NetworkConnectivityStatus.ONLINE

    GlassCard(
        elevated = true,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("analysis_error_card")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .semantics(mergeDescendants = true) {}
                    .testTag("request_error_category_badge")
            ) {
                NoticeBuddyAccentPill(
                    text = badgeText,
                    accentColor = errorAccent,
                    backgroundColor = errorSoftBg
                )
            }
            if (isReconnectedAfterOfflineError) {
                Box(
                    modifier = Modifier
                        .semantics(mergeDescendants = true) {}
                        .testTag("connection_restored_badge")
                ) {
                    NoticeBuddyAccentPill(
                        text = if (isHindi) "इंटरनेट वापस आ गया है" else "Connection Restored",
                        accentColor = glass.tealAccent,
                        backgroundColor = glass.tealSoft
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            GlassIconBadge(
                icon = Icons.Default.ErrorOutline,
                contentDescription = "Error",
                size = 40.dp,
                accentTint = errorAccent
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = headingText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = glass.textPrimary,
                    modifier = Modifier.testTag("request_error_title")
                )
                Text(
                    text = if (isReconnectedAfterOfflineError) {
                        if (isHindi) "इंटरनेट सक्रिय है — अब सुरक्षित रूप से पुनः प्रयास करें" else "Internet is back online — tap Try Again to resume"
                    } else {
                        if (isHindi) "आपका दस्तावेज़ सुरक्षित है; कृपया नीचे विवरण देखें" else "Your selected document is preserved; see details below"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = glass.textSecondary
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(errorSoftBg.copy(alpha = 0.45f))
                .padding(12.dp)
        ) {
            Text(
                text = displayedMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = glass.textPrimary,
                modifier = Modifier.testTag("analysis_error_message")
            )
        }
        if (requestError != null || hasPreservedResult) {
            Spacer(modifier = Modifier.height(8.dp))
            val guidanceText = buildString {
                if (requestError != null) {
                    append(requestError.displayGuidance(selectedLanguage))
                }
                if (hasPreservedResult) {
                    if (isNotEmpty()) append(" ")
                    append(
                        if (isHindi) {
                            "आपका पूर्व सहेजा गया विश्लेषण नीचे सुरक्षित रूप से दिखाया गया है।"
                        } else {
                            "Your previously generated notice summary is still shown below."
                        }
                    )
                }
            }
            Text(
                text = guidanceText,
                style = MaterialTheme.typography.bodySmall,
                color = glass.textSecondary,
                modifier = Modifier.testTag("request_error_guidance")
            )
        }
        Spacer(modifier = Modifier.height(14.dp))
        GlassButton(
            text = "Try Again",
            icon = Icons.Default.Refresh,
            isPrimary = true,
            enabled = requestError?.canRetrySafely ?: true,
            onClick = onRetry,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("retry_analysis_button")
        )
    }
}

@Composable
private fun AnalysisEmptyStateCard(
    selectedLanguage: NoticeLanguage = NoticeLanguage.ENGLISH,
    onRetry: () -> Unit
) {
    val glass = LocalGlassPalette.current
    val isHindi = selectedLanguage == NoticeLanguage.HINDI

    GlassCard(
        elevated = true,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("analysis_empty_card")
    ) {
        NoticeBuddyAccentPill(
            text = if (isHindi) "कोई सारांश नहीं" else "Ready to Analyze",
            accentColor = glass.lavenderAccent,
            backgroundColor = glass.lavenderSoft
        )
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            GlassIconBadge(
                icon = Icons.Default.AutoAwesome,
                contentDescription = null,
                size = 40.dp,
                accentTint = glass.lavenderAccent
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isHindi) "अभी तक कोई विश्लेषण उपलब्ध नहीं है" else "No Notice Analysis Available Yet",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = glass.textPrimary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (isHindi) {
                        "महत्वपूर्ण तिथियाँ, अगले कदम और आवश्यक दस्तावेज़ देखने के लिए विश्लेषण शुरू करें।"
                    } else {
                        "Generate a structured breakdown of important dates, next steps, and required documents."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = glass.textSecondary
                )
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        GlassButton(
            text = if (isHindi) "नोटिस का विश्लेषण करें" else "Analyze Notice",
            icon = Icons.Default.Refresh,
            isPrimary = true,
            onClick = onRetry,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("empty_state_analyze_button")
        )
    }
}

/**
 * Phase 5 Notice-Only Validation Gate Card:
 * Displayed when a document is blocked before normal notice analysis:
 * - `NOT_A_NOTICE`: Confidently identified non-notice (essay, novel, invoice, resume, irrelevant document).
 * - `NEEDS_CLEARER_INPUT`: Uncertain classification or blurry/insufficient text.
 * - `UNREADABLE_DOCUMENT`: Blank, corrupted, or unreadable scan (distinct from a confirmed non-notice).
 */
@Composable
private fun NoticeValidationGateCard(
    gateResult: NoticeValidationGateResult,
    selectedLanguage: NoticeLanguage = NoticeLanguage.ENGLISH,
    chooseAnotherText: String,
    onChooseAnother: () -> Unit
) {
    val glass = LocalGlassPalette.current
    val isHindi = selectedLanguage == NoticeLanguage.HINDI
    val accentColor = when (gateResult.status) {
        NoticeValidationStatus.NOT_A_NOTICE -> glass.pinkAccent
        NoticeValidationStatus.NEEDS_CLEARER_INPUT -> Color(0xFFD97706)
        NoticeValidationStatus.UNREADABLE_DOCUMENT -> Color(0xFFE11D48)
        else -> glass.electricBlue
    }
    val softBackground = when (gateResult.status) {
        NoticeValidationStatus.NOT_A_NOTICE -> glass.pinkSoft
        NoticeValidationStatus.NEEDS_CLEARER_INPUT -> Color(0xFFFEF3C7)
        NoticeValidationStatus.UNREADABLE_DOCUMENT -> Color(0xFFFFE4E6)
        else -> glass.electricBlueSoft
    }

    GlassCard(
        elevated = true,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("notice_validation_gate_card")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.testTag("validation_gate_status_badge")) {
                NoticeBuddyAccentPill(
                    text = gateResult.displayStatusBadge(selectedLanguage),
                    accentColor = accentColor,
                    backgroundColor = softBackground
                )
            }
            Box(modifier = Modifier.testTag("validation_gate_category_badge")) {
                NoticeBuddyAccentPill(
                    text = gateResult.displayCategoryLabel(selectedLanguage),
                    accentColor = glass.lavenderAccent,
                    backgroundColor = glass.lavenderSoft
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            GlassIconBadge(
                icon = when (gateResult.status) {
                    NoticeValidationStatus.NOT_A_NOTICE -> Icons.Default.WarningAmber
                    NoticeValidationStatus.NEEDS_CLEARER_INPUT -> Icons.Default.WarningAmber
                    else -> Icons.Default.ErrorOutline
                },
                contentDescription = gateResult.displayStatusBadge(selectedLanguage),
                size = 42.dp,
                accentTint = accentColor
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = gateResult.displayHeading(selectedLanguage),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = glass.textPrimary,
                    modifier = Modifier.testTag("validation_gate_heading")
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (isHindi) {
                        "सरकारी अधिसूचनाएं, स्कूल/कॉलेज सर्कुलर, छात्रवृत्ति नोटिस और कार्यालय निर्देश समर्थित हैं।"
                    } else {
                        "Supports government notifications, school/college circulars, scholarship notices, office notices, and public announcements."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = glass.textSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(softBackground.copy(alpha = 0.45f))
                .padding(14.dp)
        ) {
            Text(
                text = gateResult.displayMessage(selectedLanguage),
                style = MaterialTheme.typography.bodyMedium,
                lineHeight = 22.sp,
                color = glass.textPrimary,
                modifier = Modifier.testTag("validation_gate_message")
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        GlassButton(
            text = chooseAnotherText,
            icon = Icons.Default.UploadFile,
            isPrimary = true,
            onClick = onChooseAnother,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("validation_gate_choose_another_button")
        )
    }
}

/**
 * NoticeBuddy Structured Notice-Analysis Cards (Phase 4):
 * Presents information consistently in the required 7-section order:
 * 1. In Short — concise explanation of the notice (`description_summary`)
 * 2. Important Dates & Deadlines (`description_dates`)
 * 3. Who Can Apply / Eligibility (`description_eligibility`)
 * 4. Required Documents (`description_documents`)
 * 5. What To Do Next — actionable numbered steps (`description_next_steps`)
 * 6. Other Important Conditions, if present (`description_other_conditions` + `description_contact_details`)
 * 7. Ask NoticeBuddy — relevant follow-up questions, if present (`description_ask_noticebuddy`)
 */
@Composable
private fun SimplifiedNoticeResultSection(
    description: NoticeDescriptionResult,
    selectedLanguage: NoticeLanguage
) {
    val context = LocalContext.current
    val glass = LocalGlassPalette.current
    val spacing = LocalNoticeBuddySpacing.current
    val radius = LocalNoticeBuddyRadius.current
    val isHindi = selectedLanguage == NoticeLanguage.HINDI

    val summaryText = description.displaySummary(selectedLanguage)
    val datesList = description.displayImportantDates(selectedLanguage)
    val datesNotSpecified = NoticeDescriptionResult.isNotSpecifiedList(datesList)
    val eligibilityText = description.displayEligibility(selectedLanguage)
    val eligibilityNotSpecified = NoticeDescriptionResult.isNotSpecifiedValue(eligibilityText)
    val docsList = description.displayRequiredDocuments(selectedLanguage)
    val docsNotSpecified = NoticeDescriptionResult.isNotSpecifiedList(docsList)
    val nextStepsList = description.displayNextSteps(selectedLanguage)
    val stepsNotSpecified = NoticeDescriptionResult.isNotSpecifiedList(nextStepsList)
    val otherConditionsList = description.displayOtherConditions(selectedLanguage)
    val missingInfoList = description.displayMissingOrAmbiguousInfo(selectedLanguage)
    val hasContact = description.hasStatedContactDetails()
    val contactText = description.displayContactDetails(selectedLanguage)
    val askQuestionsList = description.displayAskNoticeBuddyQuestions(selectedLanguage)

    val notSpecifiedBannerText = if (isHindi) {
        "नोटिस में उल्लेख नहीं है — यह जानकारी मूल नोटिस में निर्दिष्ट नहीं की गई है (यह पठन/OCR त्रुटि नहीं है)।"
    } else {
        "Not specified in the notice — This detail is absent from the source notice (not a reading or OCR error)."
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("notice_description_card"),
        verticalArrangement = Arrangement.spacedBy(spacing.itemGap)
    ) {
        if (!description.limitWarning.isNullOrBlank()) {
            GlassCard(
                cornerRadius = radius.innerCard,
                contentPadding = PaddingValues(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("limit_warning_banner")
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.WarningAmber,
                        contentDescription = "Limit Warning",
                        tint = Color(0xFFD97706),
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = description.limitWarning,
                        style = MaterialTheme.typography.bodySmall,
                        color = glass.textPrimary,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // 1. In Short — Concise explanation of the notice
        GlassCard(
            elevated = true,
            cornerRadius = radius.card,
            contentPadding = PaddingValues(spacing.cardPadding),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    GlassIconBadge(
                        icon = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        size = 38.dp,
                        accentTint = glass.electricBlue
                    )
                    Text(
                        text = if (isHindi) "संक्षेप में (In Short)" else "In Short",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = glass.textPrimary
                    )
                }
                NoticeBuddyAccentPill(
                    text = if (isHindi) "मुख्य सारांश" else "Key Takeaway",
                    accentColor = glass.electricBlue,
                    backgroundColor = glass.electricBlueSoft
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = glass.glassBorder.copy(alpha = 0.45f))
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = summaryText,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                lineHeight = 24.sp,
                color = glass.textPrimary,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("description_summary")
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(glass.electricBlueSoft.copy(alpha = 0.45f))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .testTag("description_extraction_status"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = glass.electricBlue,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = if (isHindi) {
                        "स्रोत सफलतापूर्वक पढ़ा गया (${description.extractionMethod.displayLabel}) • केवल नोटिस में दिए गए तथ्यों पर आधारित"
                    } else {
                        "Notice read successfully (${description.extractionMethod.displayLabel}) • Grounded strictly in notice text"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = glass.textSecondary
                )
            }
        }

        // 2. Important Dates & Deadlines
        GlassCard(
            cornerRadius = radius.card,
            contentPadding = PaddingValues(spacing.cardPadding),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("description_dates")
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    GlassIconBadge(
                        icon = Icons.Default.CalendarMonth,
                        contentDescription = null,
                        size = 38.dp,
                        accentTint = glass.electricBlue
                    )
                    Text(
                        text = if (isHindi) "महत्वपूर्ण तिथियाँ और समय-सीमा" else "Important Dates & Deadlines",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = glass.textPrimary
                    )
                }
                NoticeBuddyAccentPill(
                    text = if (datesNotSpecified) {
                        if (isHindi) "नोटिस में नहीं" else "Not Specified"
                    } else {
                        "${datesList.size}"
                    },
                    accentColor = glass.electricBlue,
                    backgroundColor = glass.electricBlueSoft
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = glass.glassBorder.copy(alpha = 0.45f))
            Spacer(modifier = Modifier.height(10.dp))
            if (datesNotSpecified) {
                NotSpecifiedFieldNoticeBox(
                    text = notSpecifiedBannerText,
                    accentColor = glass.electricBlue,
                    backgroundColor = glass.electricBlueSoft
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    datesList.forEach { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(glass.electricBlueSoft.copy(alpha = 0.45f))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .padding(top = 6.dp)
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(glass.electricBlue)
                            )
                            Text(
                                text = item,
                                style = MaterialTheme.typography.bodyMedium,
                                lineHeight = 21.sp,
                                color = glass.textPrimary,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        // 3. Who Can Apply / Eligibility
        GlassCard(
            cornerRadius = radius.card,
            contentPadding = PaddingValues(spacing.cardPadding),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    GlassIconBadge(
                        icon = Icons.Default.Person,
                        contentDescription = null,
                        size = 38.dp,
                        accentTint = glass.lavenderAccent
                    )
                    Text(
                        text = if (isHindi) "कौन आवेदन कर सकता है / पात्रता" else "Who Can Apply / Eligibility",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = glass.textPrimary
                    )
                }
                NoticeBuddyAccentPill(
                    text = if (eligibilityNotSpecified) {
                        if (isHindi) "नोटिस में नहीं" else "Not Specified"
                    } else {
                        if (isHindi) "पात्रता" else "Eligibility"
                    },
                    accentColor = glass.lavenderAccent,
                    backgroundColor = glass.lavenderSoft
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = glass.glassBorder.copy(alpha = 0.45f))
            Spacer(modifier = Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(glass.lavenderSoft.copy(alpha = 0.45f))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text(
                    text = if (eligibilityNotSpecified) notSpecifiedBannerText else eligibilityText,
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 21.sp,
                    color = if (eligibilityNotSpecified) glass.textSecondary else glass.textPrimary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("description_eligibility")
                )
            }
        }

        // 4. Required Documents
        GlassCard(
            cornerRadius = radius.card,
            contentPadding = PaddingValues(spacing.cardPadding),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("description_documents")
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    GlassIconBadge(
                        icon = Icons.Default.Checklist,
                        contentDescription = null,
                        size = 38.dp,
                        accentTint = glass.pinkAccent
                    )
                    Text(
                        text = if (isHindi) "आवश्यक दस्तावेज़ (Required Documents)" else "Required Documents",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = glass.textPrimary
                    )
                }
                NoticeBuddyAccentPill(
                    text = if (docsNotSpecified) {
                        if (isHindi) "नोटिस में नहीं" else "Not Specified"
                    } else {
                        if (isHindi) "${docsList.size} दस्तावेज़" else "${docsList.size} Docs"
                    },
                    accentColor = glass.pinkAccent,
                    backgroundColor = glass.pinkSoft
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = glass.glassBorder.copy(alpha = 0.45f))
            Spacer(modifier = Modifier.height(10.dp))
            if (docsNotSpecified) {
                NotSpecifiedFieldNoticeBox(
                    text = notSpecifiedBannerText,
                    accentColor = glass.pinkAccent,
                    backgroundColor = glass.pinkSoft
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    docsList.forEach { doc ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(glass.pinkSoft.copy(alpha = 0.45f))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = glass.pinkAccent,
                                modifier = Modifier
                                    .size(18.dp)
                                    .padding(top = 2.dp)
                            )
                            Text(
                                text = doc,
                                style = MaterialTheme.typography.bodyMedium,
                                lineHeight = 21.sp,
                                color = glass.textPrimary,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        // 5. What To Do Next — Actionable numbered steps
        GlassCard(
            cornerRadius = radius.card,
            contentPadding = PaddingValues(spacing.cardPadding),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("description_next_steps")
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    GlassIconBadge(
                        icon = Icons.AutoMirrored.Filled.FormatListBulleted,
                        contentDescription = null,
                        size = 38.dp,
                        accentTint = glass.tealAccent
                    )
                    Text(
                        text = if (isHindi) "आगे क्या करें (What To Do Next)" else "What To Do Next",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = glass.textPrimary
                    )
                }
                NoticeBuddyAccentPill(
                    text = if (stepsNotSpecified) {
                        if (isHindi) "नोटिस में नहीं" else "Not Specified"
                    } else {
                        if (isHindi) "${nextStepsList.size} चरण" else "${nextStepsList.size} Steps"
                    },
                    accentColor = glass.tealAccent,
                    backgroundColor = glass.tealSoft
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = glass.glassBorder.copy(alpha = 0.45f))
            Spacer(modifier = Modifier.height(10.dp))
            if (stepsNotSpecified) {
                NotSpecifiedFieldNoticeBox(
                    text = notSpecifiedBannerText,
                    accentColor = glass.tealAccent,
                    backgroundColor = glass.tealSoft
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    nextStepsList.forEachIndexed { idx, step ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(glass.tealSoft.copy(alpha = 0.45f))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(glass.tealSoft),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "${idx + 1}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = glass.tealAccent
                                )
                            }
                            Text(
                                text = step,
                                style = MaterialTheme.typography.bodyMedium,
                                lineHeight = 21.sp,
                                color = glass.textPrimary,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        // 6. Other Important Conditions, if present (conditions, stated contact info, or unstated details alert)
        if (otherConditionsList.isNotEmpty() || missingInfoList.isNotEmpty() || hasContact) {
            GlassCard(
                cornerRadius = radius.card,
                contentPadding = PaddingValues(spacing.cardPadding),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("description_other_conditions")
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        GlassIconBadge(
                            icon = Icons.Default.Description,
                            contentDescription = null,
                            size = 38.dp,
                            accentTint = glass.lavenderAccent
                        )
                        Text(
                            text = if (isHindi) "अन्य महत्वपूर्ण शर्तें (Other Conditions)" else "Other Important Conditions",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = glass.textPrimary
                        )
                    }
                    NoticeBuddyAccentPill(
                        text = if (isHindi) "नियम व विवरण" else "Rules & Notes",
                        accentColor = glass.lavenderAccent,
                        backgroundColor = glass.lavenderSoft
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = glass.glassBorder.copy(alpha = 0.45f))
                Spacer(modifier = Modifier.height(10.dp))

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    otherConditionsList.forEach { condition ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(glass.lavenderSoft.copy(alpha = 0.45f))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .padding(top = 6.dp)
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(glass.lavenderAccent)
                            )
                            Text(
                                text = condition,
                                style = MaterialTheme.typography.bodyMedium,
                                lineHeight = 21.sp,
                                color = glass.textPrimary,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    if (hasContact) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(glass.electricBlueSoft.copy(alpha = 0.45f))
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                                .testTag("description_contact_details"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            NoticeBuddyAccentPill(
                                text = if (isHindi) "संपर्क विवरण" else "Contact Info",
                                accentColor = glass.electricBlue,
                                backgroundColor = glass.electricBlueSoft
                            )
                            Text(
                                text = contactText,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = glass.textPrimary,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    if (missingInfoList.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(glass.pinkSoft.copy(alpha = 0.38f))
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                                .testTag("description_missing_info_section"),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = if (isHindi) {
                                    "नोटिस में अनुपस्थित विवरण (ओसीआर त्रुटि नहीं):"
                                } else {
                                    "Details Not Specified in Notice (Not an Extraction Error):"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = glass.pinkAccent
                            )
                            missingInfoList.forEach { missingItem ->
                                Row(
                                    verticalAlignment = Alignment.Top,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "•",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        color = glass.pinkAccent
                                    )
                                    Text(
                                        text = missingItem,
                                        style = MaterialTheme.typography.bodySmall,
                                        lineHeight = 19.sp,
                                        color = glass.textPrimary,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 7. Ask NoticeBuddy — Relevant follow-up questions, if present
        if (askQuestionsList.isNotEmpty()) {
            GlassCard(
                cornerRadius = radius.card,
                contentPadding = PaddingValues(spacing.cardPadding),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("description_ask_noticebuddy")
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        GlassIconBadge(
                            icon = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            size = 38.dp,
                            accentTint = glass.electricBlue
                        )
                        Column {
                            Text(
                                text = if (isHindi) "नोटिसबडी से पूछें (Ask NoticeBuddy)" else "Ask NoticeBuddy",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = glass.textPrimary
                            )
                            Text(
                                text = if (isHindi) {
                                    "संबंधित कार्यालय से पूछने या जांचने योग्य प्रश्न (टैप करके कॉपी करें)"
                                } else {
                                    "Relevant follow-up questions to verify (tap any question to copy)"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = glass.textSecondary
                            )
                        }
                    }
                    NoticeBuddyAccentPill(
                        text = if (isHindi) "${askQuestionsList.size} प्रश्न" else "${askQuestionsList.size} Q&A",
                        accentColor = glass.electricBlue,
                        backgroundColor = glass.electricBlueSoft
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = glass.glassBorder.copy(alpha = 0.45f))
                Spacer(modifier = Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    askQuestionsList.forEachIndexed { idx, question ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(glass.electricBlueSoft.copy(alpha = 0.45f))
                                .clickable {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    clipboard?.setPrimaryClip(ClipData.newPlainText("NoticeBuddy Question", question))
                                    Toast.makeText(
                                        context,
                                        if (isHindi) "प्रश्न कॉपी किया गया" else "Follow-up question copied",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                                .testTag("ask_noticebuddy_question_$idx"),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(glass.electricBlueSoft),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Q${idx + 1}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = glass.electricBlue
                                )
                            }
                            Text(
                                text = question,
                                style = MaterialTheme.typography.bodyMedium,
                                lineHeight = 21.sp,
                                color = glass.textPrimary,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotSpecifiedFieldNoticeBox(
    text: String,
    accentColor: Color,
    backgroundColor: Color
) {
    val glass = LocalGlassPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor.copy(alpha = 0.35f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(accentColor.copy(alpha = 0.7f))
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            lineHeight = 21.sp,
            color = glass.textSecondary,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SettingsMainScreen(
    themeMode: AppThemeMode,
    onToggleTheme: () -> Unit,
    onOpenApiKeys: () -> Unit,
    onOpenModel: () -> Unit,
    onOpenDebugLogs: () -> Unit
) {
    val glass = LocalGlassPalette.current
    val isNight = themeMode == AppThemeMode.NIGHT
    val diagnosticLogs by AppDiagnosticLogger.logs.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()

    GlassCard(
        elevated = true,
        modifier = Modifier
            .fillMaxSize()
            .testTag("settings_screen")
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = glass.textPrimary
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Profile  ›  Settings",
            style = MaterialTheme.typography.labelMedium,
            color = glass.textSecondary
        )

        Spacer(modifier = Modifier.height(18.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Dynamic Day / Night Theme Toggle
            GlassCard(
                onClick = onToggleTheme,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("theme_toggle_button")
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        GlassIconBadge(
                            icon = if (isNight) Icons.Default.DarkMode else Icons.Default.LightMode,
                            contentDescription = "Theme Mode",
                            size = 42.dp
                        )
                        Column {
                            Text(
                                text = "Day / Night Theme",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = glass.textPrimary
                            )
                            Text(
                                text = themeMode.label,
                                style = MaterialTheme.typography.bodySmall,
                                color = glass.textSecondary,
                                modifier = Modifier.testTag("current_theme_label")
                            )
                        }
                    }
                    Switch(
                        checked = isNight,
                        onCheckedChange = { onToggleTheme() }
                    )
                }
            }

            // Settings -> API Keys
            GlassCard(
                onClick = onOpenApiKeys,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_api_keys_button")
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        GlassIconBadge(
                            icon = Icons.Default.Key,
                            contentDescription = "API Keys",
                            size = 42.dp
                        )
                        Column {
                            Text(
                                text = "API Keys",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = glass.textPrimary
                            )
                            Text(
                                text = "Configure service keys",
                                style = MaterialTheme.typography.bodySmall,
                                color = glass.textSecondary
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Open API Keys",
                        tint = glass.textSecondary
                    )
                }
            }

            // Settings -> Model
            GlassCard(
                onClick = onOpenModel,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_model_button")
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        GlassIconBadge(
                            icon = Icons.Default.Memory,
                            contentDescription = "Model",
                            size = 42.dp
                        )
                        Column {
                            Text(
                                text = "Model",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = glass.textPrimary
                            )
                            Text(
                                text = HomePhaseViewModel.DISPLAY_MODEL_NAME,
                                style = MaterialTheme.typography.bodySmall,
                                color = glass.textSecondary
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Open Model",
                        tint = glass.textSecondary
                    )
                }
            }

            // Settings -> Debug Logs
            GlassCard(
                onClick = onOpenDebugLogs,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_debug_logs_button")
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        GlassIconBadge(
                            icon = Icons.Default.Description,
                            contentDescription = "Debug Logs",
                            size = 42.dp,
                            accentTint = glass.secondaryGlow
                        )
                        Column {
                            Text(
                                text = "Debug Logs",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = glass.textPrimary
                            )
                            Text(
                                text = "View runtime logs for PDF, OCR & AI (${diagnosticLogs.size})",
                                style = MaterialTheme.typography.bodySmall,
                                color = glass.textSecondary,
                                modifier = Modifier.testTag("settings_debug_logs_count")
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Open Debug Logs",
                        tint = glass.textSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsApiKeysScreen(
    onBackToSettings: () -> Unit
) {
    val glass = LocalGlassPalette.current

    GlassCard(
        elevated = true,
        modifier = Modifier
            .fillMaxSize()
            .testTag("settings_api_keys_screen")
    ) {
        Text(
            text = "API Keys",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = glass.textPrimary
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Profile  ›  Settings  ›  API Keys",
            style = MaterialTheme.typography.labelMedium,
            color = glass.textSecondary
        )

        Spacer(modifier = Modifier.height(18.dp))

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Cloud Vision & AI Configuration",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = glass.textPrimary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "NoticeBuddy uses integrated Firebase AI Logic (Gemini 2.5 Flash) by default and optional environment keys (.env) without exposing secrets in local storage.",
                style = MaterialTheme.typography.bodyMedium,
                color = glass.textSecondary
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        GlassButton(
            text = "Back to Settings",
            isPrimary = false,
            onClick = onBackToSettings,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun SettingsModelScreen(
    onBackToSettings: () -> Unit
) {
    val glass = LocalGlassPalette.current

    GlassCard(
        elevated = true,
        modifier = Modifier
            .fillMaxSize()
            .testTag("settings_model_screen")
    ) {
        Text(
            text = "Model",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = glass.textPrimary
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Profile  ›  Settings  ›  Model",
            style = MaterialTheme.typography.labelMedium,
            color = glass.textSecondary
        )

        Spacer(modifier = Modifier.height(18.dp))

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Selected Model",
                style = MaterialTheme.typography.labelMedium,
                color = glass.textSecondary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = HomePhaseViewModel.DISPLAY_MODEL_NAME,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = glass.textPrimary,
                modifier = Modifier.testTag("model_name_value")
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        GlassButton(
            text = "Back to Settings",
            isPrimary = false,
            onClick = onBackToSettings,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun SettingsDebugLogsScreen(
    onBackToSettings: () -> Unit
) {
    val glass = LocalGlassPalette.current
    val context = LocalContext.current
    val logs by AppDiagnosticLogger.logs.collectAsStateWithLifecycle()
    val reversedLogs = remember(logs) { logs.asReversed() }
    var copiedFeedback by remember { mutableStateOf(false) }

    GlassCard(
        elevated = true,
        modifier = Modifier
            .fillMaxSize()
            .testTag("settings_debug_logs_screen")
    ) {
        Text(
            text = "Debug Logs",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = glass.textPrimary
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Profile  ›  Settings  ›  Debug Logs",
            style = MaterialTheme.typography.labelMedium,
            color = glass.textSecondary
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            GlassButton(
                text = if (copiedFeedback) "Copied!" else "Copy Logs",
                isPrimary = true,
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    val formatted = AppDiagnosticLogger.formatAllLogsAsText()
                    clipboard?.setPrimaryClip(ClipData.newPlainText("NoticeBuddy Debug Logs", formatted))
                    copiedFeedback = true
                    runCatching {
                        Toast.makeText(context, "Debug logs copied to clipboard", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .testTag("copy_debug_logs_button")
            )
            GlassButton(
                text = "Clear Logs",
                icon = Icons.Default.DeleteOutline,
                isPrimary = false,
                onClick = {
                    copiedFeedback = false
                    AppDiagnosticLogger.clear()
                },
                modifier = Modifier
                    .weight(1f)
                    .testTag("clear_debug_logs_button")
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag("debug_logs_list"),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(
                items = reversedLogs,
                key = { it.id }
            ) { entry ->
                DiagnosticLogItemRow(entry = entry)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        GlassButton(
            text = "Back to Settings",
            isPrimary = false,
            onClick = onBackToSettings,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("back_from_debug_logs_button")
        )
    }
}

@Composable
private fun DiagnosticLogItemRow(
    entry: DiagnosticLogEntry
) {
    val glass = LocalGlassPalette.current
    val levelColor = when (entry.level) {
        DiagnosticLogLevel.INFO -> glass.accentGlow
        DiagnosticLogLevel.WARN -> Color(0xFFFBBF24)
        DiagnosticLogLevel.ERROR -> Color(0xFFF87171)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(glass.glassSurface)
            .border(1.dp, glass.glassBorder.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = entry.level.name,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = levelColor
                )
                Text(
                    text = "•",
                    style = MaterialTheme.typography.labelSmall,
                    color = glass.textSecondary
                )
                Text(
                    text = entry.tag,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = glass.secondaryGlow
                )
            }
            Text(
                text = entry.timestampFormatted,
                style = MaterialTheme.typography.labelSmall,
                color = glass.textSecondary
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = entry.message,
            style = MaterialTheme.typography.bodySmall,
            color = glass.textPrimary
        )
    }
}

@Composable
private fun BottomGlassHomeBar(
    isHomeActive: Boolean,
    onHomeClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        GlassButton(
            text = "Home",
            icon = Icons.Default.Home,
            isPrimary = isHomeActive,
            onClick = onHomeClick,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("home_bottom_button")
        )
    }
}

@Composable
private fun rememberFormattedTimestamp(timestamp: Long): String {
    if (timestamp <= 0L) return "Just now"
    val formatter = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.US)
    return formatter.format(Date(timestamp))
}

private fun resolveUriMetadata(
    context: Context,
    uri: Uri
): Triple<String?, String?, Long> {
    val contentResolver = context.contentResolver
    val mimeType = contentResolver.getType(uri)
    var displayName: String? = null
    var sizeBytes = -1L

    runCatching {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                    displayName = cursor.getString(nameIndex)
                }
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    sizeBytes = cursor.getLong(sizeIndex)
                }
            }
        }
    }

    if (displayName.isNullOrBlank()) {
        displayName = uri.lastPathSegment?.substringAfterLast('/')
    }
    return Triple(displayName, mimeType, sizeBytes)
}
