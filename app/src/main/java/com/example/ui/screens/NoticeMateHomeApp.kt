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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import com.example.data.model.PdfExtractionResult
import com.example.ui.components.BokehGlassBackground
import com.example.ui.components.GlassButton
import com.example.ui.components.GlassCard
import com.example.ui.components.GlassIconBadge
import com.example.ui.components.GlassModalDialog
import com.example.ui.components.LiquidLanguageTogglePill
import com.example.ui.theme.LocalGlassPalette
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
    viewModel: HomePhaseViewModel
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
                            onLaunchImagePicker = {
                                imagePickerLauncher.launch(arrayOf("image/*"))
                            },
                            onRemoveImage = viewModel::removeSelectedImage,
                            onContinue = { viewModel.submitSelectedImage() },
                            onBackHome = { viewModel.onHomeButtonClicked() }
                        )

                        NoticeAppRoute.UPLOAD_IMAGE_CONFIRMATION -> ImageConfirmationScreen(
                            entry = uiState.activeConfirmedEntry,
                            selectedLanguage = uiState.selectedLanguage,
                            onSelectLanguage = viewModel::setNoticeLanguage,
                            isProcessing = uiState.isProcessing,
                            processingStageMessage = uiState.processingStageMessage,
                            analysisError = uiState.analysisError,
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

                // Bottom Liquid Glass Home Dock
                BottomGlassHomeBar(
                    isHomeActive = uiState.currentRoute == NoticeAppRoute.HOME,
                    onHomeClick = { viewModel.onHomeButtonClicked() }
                )
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
                                text = "NoticeMate AI Preferences",
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (currentRoute != NoticeAppRoute.HOME) {
                GlassIconBadge(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    size = 44.dp,
                    onClick = onBackClick,
                    modifier = Modifier.testTag("top_back_button")
                )
            } else {
                GlassIconBadge(
                    icon = Icons.Default.AutoAwesome,
                    contentDescription = "NoticeMate Liquid Glass",
                    size = 44.dp,
                    accentTint = glass.secondaryGlow
                )
            }
            Column {
                Text(
                    text = "NoticeMate AI",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
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
            size = 48.dp,
            onClick = onProfileClick,
            modifier = Modifier.testTag("profile_icon_button")
        )
    }
}

/**
 * Ultra-Minimal Liquid Glass Home Screen:
 * Contains ONLY the 3 separate input buttons (PDF, Image, Text) and the Recent Notices button.
 * Does NOT clutter the home screen with an inline recent notices list.
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
    val savedCount = uiState.recentNotices.size
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .testTag("homepage_root"),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Live Liquid Glass Quick-Glance Hero Widget
        GlassCard(
            cornerRadius = 22.dp,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF34D399))
                )
                Text(
                    text = "Upload any PDF, photo, or text notice for an instant, easy summary.",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = glass.textPrimary
                )
            }
        }

        // Button 1: Upload Notice PDF (supports both text PDFs and scanned image PDFs)
        LiquidHomeActionTile(
            title = "Upload Notice PDF",
            subtitle = "Read any PDF notice or scanned circular",
            icon = Icons.Default.PictureAsPdf,
            accentColor = glass.accentGlow,
            onClick = onUploadNoticePdfClick,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("upload_notice_pdf_button")
        )

        // Button 2: Upload Notice Image (direct photo or screenshot of a notice)
        LiquidHomeActionTile(
            title = "Upload Notice Image",
            subtitle = "Pick a photo or screenshot of a notice",
            icon = Icons.Default.Image,
            accentColor = glass.secondaryGlow,
            onClick = onUploadNoticeImageClick,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("upload_notice_image_button")
        )

        // Button 3: Enter Notice Text
        LiquidHomeActionTile(
            title = "Enter Notice Text",
            subtitle = "Paste or type notice text directly",
            icon = Icons.Default.EditNote,
            accentColor = Color(0xFF34D399),
            onClick = onEnterNoticeTextClick,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("enter_notice_text_button")
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Dedicated Recent Notices Button (opens separate Recent Notices Screen)
        GlassCard(
            elevated = false,
            cornerRadius = 22.dp,
            onClick = onOpenRecentNoticesClick,
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("open_recent_notices_button")
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    GlassIconBadge(
                        icon = Icons.Default.History,
                        contentDescription = "Recent Notices",
                        size = 44.dp,
                        accentTint = glass.accentGlow
                    )
                    Column {
                        Text(
                            text = if (savedCount > 0) "Recent Notices ($savedCount)" else "Recent Notices",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = glass.textPrimary
                        )
                        Text(
                            text = if (savedCount > 0) {
                                "Tap to view or delete saved notices"
                            } else {
                                "No saved notices yet"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = glass.textSecondary
                        )
                    }
                }

                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Open Recent Notices",
                    tint = glass.textPrimary
                )
            }
        }
    }
}

@Composable
private fun LiquidHomeActionTile(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = LocalGlassPalette.current
    GlassCard(
        elevated = true,
        cornerRadius = 24.dp,
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 18.dp),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            GlassIconBadge(
                icon = icon,
                contentDescription = title,
                size = 52.dp,
                accentTint = accentColor
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = glass.textPrimary
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = glass.textSecondary
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = glass.textSecondary
            )
        }
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
    onRetryAnalysis: () -> Unit,
    onDeleteCurrentNotice: () -> Unit,
    onEditNotice: () -> Unit,
    onBackHome: () -> Unit
) {
    val glass = LocalGlassPalette.current
    val displayedText = entry?.textContent?.ifBlank { draftText } ?: draftText
    val fallbackTitle = entry?.title ?: RecentNoticeStorage.deriveTitleFromNoticeText(displayedText)
    val description = entry?.descriptionResult
    val displayedTitle = description?.displayTitle(selectedLanguage, fallbackTitle) ?: fallbackTitle
    val scrollState = rememberScrollState()
    var showOriginalText by remember { mutableStateOf(false) }

    GlassCard(
        elevated = true,
        modifier = Modifier
            .fillMaxSize()
            .testTag("notice_text_confirmation_screen")
    ) {
        NoticeResultHeaderBar(
            title = displayedTitle,
            selectedLanguage = selectedLanguage,
            onSelectLanguage = onSelectLanguage,
            canDelete = entry != null && !isProcessing,
            onDelete = onDeleteCurrentNotice
        )

        Spacer(modifier = Modifier.height(12.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (isProcessing) {
                AnalysisLoadingCard(
                    message = processingStageMessage ?: "Summarizing notice in simple words..."
                )
            }

            if (analysisError != null && !isProcessing) {
                AnalysisErrorCard(
                    errorMessage = analysisError,
                    onRetry = onRetryAnalysis
                )
            }

            if (description != null) {
                SimplifiedNoticeResultSection(
                    description = description,
                    selectedLanguage = selectedLanguage
                )
            }

            // Clean collapsible Original Text section (also exposes confirmed_notice_text_body)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(glass.glassSurface)
                    .border(1.dp, glass.glassBorder.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                    .clickable { showOriginalText = !showOriginalText }
                    .padding(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (selectedLanguage == NoticeLanguage.HINDI) {
                            "मूल नोटिस टेक्स्ट (Original Notice Text)"
                        } else {
                            "Original Notice Text"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = glass.accentGlow
                    )
                    Text(
                        text = if (showOriginalText) "Hide" else "Show",
                        style = MaterialTheme.typography.labelSmall,
                        color = glass.textSecondary
                    )
                }
                if (showOriginalText || description == null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = displayedText,
                        style = MaterialTheme.typography.bodySmall,
                        color = glass.textPrimary,
                        modifier = Modifier.testTag("confirmed_notice_text_body")
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            GlassButton(
                text = "Edit Text",
                isPrimary = false,
                enabled = !isProcessing,
                onClick = onEditNotice,
                modifier = Modifier
                    .weight(1f)
                    .testTag("edit_notice_text_button")
            )
            GlassButton(
                text = "Done",
                isPrimary = true,
                enabled = !isProcessing,
                onClick = onBackHome,
                modifier = Modifier
                    .weight(1f)
                    .testTag("confirm_return_home_button")
            )
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
    extractionResult: PdfExtractionResult?,
    onRetryAnalysis: () -> Unit,
    onDeleteCurrentNotice: () -> Unit,
    onSelectAnotherPdf: () -> Unit,
    onBackHome: () -> Unit
) {
    val glass = LocalGlassPalette.current
    val fileName = entry?.pdfFileName?.ifBlank { entry.title } ?: "Selected_Notice.pdf"
    val description = entry?.descriptionResult
    val displayedTitle = description?.displayTitle(selectedLanguage, fileName) ?: fileName
    val scrollState = rememberScrollState()

    GlassCard(
        elevated = true,
        modifier = Modifier
            .fillMaxSize()
            .testTag("pdf_confirmation_screen")
    ) {
        NoticeResultHeaderBar(
            title = displayedTitle,
            selectedLanguage = selectedLanguage,
            onSelectLanguage = onSelectLanguage,
            canDelete = entry != null && !isProcessing,
            onDelete = onDeleteCurrentNotice
        )

        Spacer(modifier = Modifier.height(12.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (isProcessing) {
                AnalysisLoadingCard(
                    message = processingStageMessage ?: "Reading PDF pages and generating simple summary..."
                )
            }

            if (analysisError != null && !isProcessing) {
                AnalysisErrorCard(
                    errorMessage = analysisError,
                    onRetry = onRetryAnalysis
                )
            }

            if (description != null) {
                SimplifiedNoticeResultSection(
                    description = description,
                    selectedLanguage = selectedLanguage
                )
            }

            // Minimal file footer chip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(glass.glassSurface)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.labelSmall,
                    color = glass.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("confirmed_pdf_filename")
                )
                if (extractionResult != null) {
                    Text(
                        text = "${extractionResult.processedPageCount}p",
                        style = MaterialTheme.typography.labelSmall,
                        color = glass.accentGlow,
                        modifier = Modifier.testTag("pdf_extraction_summary")
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            GlassButton(
                text = "Another PDF",
                isPrimary = false,
                enabled = !isProcessing,
                onClick = onSelectAnotherPdf,
                modifier = Modifier.weight(1f)
            )
            GlassButton(
                text = "Done",
                isPrimary = true,
                enabled = !isProcessing,
                onClick = onBackHome,
                modifier = Modifier
                    .weight(1f)
                    .testTag("pdf_confirm_return_home_button")
            )
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
            text = "Upload Notice Image",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = glass.textPrimary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Select a photo or screenshot of any notice from your phone.",
            style = MaterialTheme.typography.bodySmall,
            color = glass.textSecondary
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (selectedImage == null) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(glass.glassSurface)
                    .border(1.4.dp, glass.glassBorder, RoundedCornerShape(22.dp))
                    .clickable(enabled = !isProcessing, onClick = onLaunchImagePicker)
                    .padding(24.dp)
                    .testTag("select_image_dropzone"),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    GlassIconBadge(
                        icon = Icons.Default.Image,
                        contentDescription = "Select Notice Image",
                        size = 62.dp,
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
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
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
    onRetryAnalysis: () -> Unit,
    onDeleteCurrentNotice: () -> Unit,
    onSelectAnotherImage: () -> Unit,
    onBackHome: () -> Unit
) {
    val fileName = entry?.pdfFileName?.ifBlank { entry.title } ?: "Notice_Image.jpg"
    val description = entry?.descriptionResult
    val displayedTitle = description?.displayTitle(selectedLanguage, fileName) ?: fileName
    val scrollState = rememberScrollState()

    GlassCard(
        elevated = true,
        modifier = Modifier
            .fillMaxSize()
            .testTag("image_confirmation_screen")
    ) {
        NoticeResultHeaderBar(
            title = displayedTitle,
            selectedLanguage = selectedLanguage,
            onSelectLanguage = onSelectLanguage,
            canDelete = entry != null && !isProcessing,
            onDelete = onDeleteCurrentNotice
        )

        Spacer(modifier = Modifier.height(12.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (isProcessing) {
                AnalysisLoadingCard(
                    message = processingStageMessage ?: "Reading notice image and generating simple summary..."
                )
            }

            if (analysisError != null && !isProcessing) {
                AnalysisErrorCard(
                    errorMessage = analysisError,
                    onRetry = onRetryAnalysis
                )
            }

            if (description != null) {
                SimplifiedNoticeResultSection(
                    description = description,
                    selectedLanguage = selectedLanguage
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            GlassButton(
                text = "Another Image",
                isPrimary = false,
                enabled = !isProcessing,
                onClick = onSelectAnotherImage,
                modifier = Modifier.weight(1f)
            )
            GlassButton(
                text = "Done",
                isPrimary = true,
                enabled = !isProcessing,
                onClick = onBackHome,
                modifier = Modifier
                    .weight(1f)
                    .testTag("image_confirm_return_home_button")
            )
        }
    }
}

@Composable
private fun NoticeResultHeaderBar(
    title: String,
    selectedLanguage: NoticeLanguage,
    onSelectLanguage: (NoticeLanguage) -> Unit,
    canDelete: Boolean,
    onDelete: () -> Unit
) {
    val glass = LocalGlassPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
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
                        .testTag("delete_current_notice_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Delete saved notice",
                        tint = Color(0xFFF87171)
                    )
                }
            }
        }

        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
            color = glass.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun AnalysisLoadingCard(
    message: String
) {
    val glass = LocalGlassPalette.current
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("analysis_loading_card")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            CircularProgressIndicator(
                color = glass.accentGlow,
                strokeWidth = 3.dp,
                modifier = Modifier.size(28.dp)
            )
            Column {
                Text(
                    text = "Reading Notice...",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = glass.textPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = glass.textSecondary,
                    modifier = Modifier.testTag("analysis_loading_message")
                )
            }
        }
    }
}

@Composable
private fun AnalysisErrorCard(
    errorMessage: String,
    onRetry: () -> Unit
) {
    val glass = LocalGlassPalette.current
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("analysis_error_card")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = "Error",
                tint = Color(0xFFF87171),
                modifier = Modifier.size(26.dp)
            )
            Text(
                text = "Could Not Read Notice",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFF87171)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = errorMessage,
            style = MaterialTheme.typography.bodySmall,
            color = glass.textPrimary,
            modifier = Modifier.testTag("analysis_error_message")
        )
        Spacer(modifier = Modifier.height(12.dp))
        GlassButton(
            text = "Try Again",
            icon = Icons.Default.Refresh,
            isPrimary = false,
            onClick = onRetry,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("retry_analysis_button")
        )
    }
}

/**
 * Simplified, Non-Confusing Notice Result View:
 * Shows a short 1-2 line overview + ONLY the 3 user-selected sections:
 * 1. Important Dates & Deadlines
 * 2. What To Do Next
 * 3. Who Can Apply & Required Documents
 */
@Composable
private fun SimplifiedNoticeResultSection(
    description: NoticeDescriptionResult,
    selectedLanguage: NoticeLanguage
) {
    val glass = LocalGlassPalette.current
    val isHindi = selectedLanguage == NoticeLanguage.HINDI

    val summaryText = description.displaySummary(selectedLanguage)
    val datesList = description.displayImportantDates(selectedLanguage)
    val nextStepsList = description.displayNextSteps(selectedLanguage)
    val eligibilityText = description.displayEligibility(selectedLanguage)
    val docsList = description.displayRequiredDocuments(selectedLanguage)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("notice_description_card"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!description.limitWarning.isNullOrBlank()) {
            GlassCard(
                cornerRadius = 18.dp,
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
                        tint = Color(0xFFFBBF24),
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = description.limitWarning,
                        style = MaterialTheme.typography.bodySmall,
                        color = glass.textPrimary
                    )
                }
            }
        }

        // Short Plain Summary Card
        GlassCard(
            cornerRadius = 20.dp,
            contentPadding = PaddingValues(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = if (isHindi) "संक्षिप्त सारांश (In Short)" else "In Short",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = glass.accentGlow
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = summaryText,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = glass.textPrimary,
                modifier = Modifier.testTag("description_summary")
            )
        }

        // Section 1: Important Dates & Deadlines
        GlassCard(
            cornerRadius = 20.dp,
            contentPadding = PaddingValues(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("description_dates")
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                GlassIconBadge(
                    icon = Icons.Default.CalendarMonth,
                    contentDescription = null,
                    size = 38.dp,
                    accentTint = glass.accentGlow
                )
                Text(
                    text = if (isHindi) "महत्वपूर्ण तिथियाँ (Important Dates)" else "Important Dates & Deadlines",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = glass.textPrimary
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            datesList.forEach { item ->
                Row(
                    modifier = Modifier.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = "• ",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = glass.accentGlow
                    )
                    Text(
                        text = item,
                        style = MaterialTheme.typography.bodyMedium,
                        color = glass.textPrimary
                    )
                }
            }
        }

        // Section 2: What To Do Next (Action Steps)
        GlassCard(
            cornerRadius = 20.dp,
            contentPadding = PaddingValues(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("description_next_steps")
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                GlassIconBadge(
                    icon = Icons.AutoMirrored.Filled.FormatListBulleted,
                    contentDescription = null,
                    size = 38.dp,
                    accentTint = Color(0xFF34D399)
                )
                Text(
                    text = if (isHindi) "आगे क्या करें (What To Do Next)" else "What To Do Next",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = glass.textPrimary
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            nextStepsList.forEachIndexed { idx, step ->
                Row(
                    modifier = Modifier.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = "${idx + 1}. ",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF34D399)
                    )
                    Text(
                        text = step,
                        style = MaterialTheme.typography.bodyMedium,
                        color = glass.textPrimary
                    )
                }
            }
        }

        // Section 3: Who Can Apply & Required Documents
        GlassCard(
            cornerRadius = 20.dp,
            contentPadding = PaddingValues(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                GlassIconBadge(
                    icon = Icons.Default.Checklist,
                    contentDescription = null,
                    size = 38.dp,
                    accentTint = glass.secondaryGlow
                )
                Text(
                    text = if (isHindi) {
                        "पात्रता और आवश्यक दस्तावेज़"
                    } else {
                        "Who Can Apply & Required Documents"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = glass.textPrimary
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = if (isHindi) "कौन आवेदन कर सकता है (Who Can Apply):" else "Who Can Apply:",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = glass.accentGlow
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = eligibilityText,
                style = MaterialTheme.typography.bodyMedium,
                color = glass.textPrimary,
                modifier = Modifier.testTag("description_eligibility")
            )

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = glass.glassBorder.copy(alpha = 0.3f))
            Spacer(modifier = Modifier.height(10.dp))

            Column(modifier = Modifier.testTag("description_documents")) {
                Text(
                    text = if (isHindi) "आवश्यक दस्तावेज़ (Required Documents):" else "Required Documents:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = glass.secondaryGlow
                )
                Spacer(modifier = Modifier.height(4.dp))
                docsList.forEach { doc ->
                    Row(
                        modifier = Modifier.padding(vertical = 3.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = glass.secondaryGlow,
                            modifier = Modifier
                                .size(16.dp)
                                .padding(top = 2.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = doc,
                            style = MaterialTheme.typography.bodyMedium,
                            color = glass.textPrimary
                        )
                    }
                }
            }
        }
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
                text = "NoticeMate AI uses integrated Firebase AI Logic (Gemini 2.5 Flash) by default and optional environment keys (.env) without exposing secrets in local storage.",
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
                    clipboard?.setPrimaryClip(ClipData.newPlainText("NoticeMate Debug Logs", formatted))
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
