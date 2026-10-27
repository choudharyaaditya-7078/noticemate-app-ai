package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.CalendarContract
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BookmarkAdded
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.R
import com.example.data.model.NoticeDocument
import com.example.data.model.SampleNoticeCase
import com.example.data.model.SampleNotices
import com.example.data.remote.FastRouterService
import com.example.data.repository.NoticeRepository
import com.example.data.validation.GroundingValidator
import com.example.ui.auth.signOutUser
import com.example.ui.components.BilingualActionChecklistCard
import com.example.ui.components.ExtractedFieldsAndGroundingCard
import com.example.ui.components.GapDetectionAlertCard
import com.example.ui.components.GroundedQaCard
import com.example.ui.components.PipelineStepperBar
import com.example.ui.components.SourceEvidenceCard
import com.example.ui.theme.NotoSansDevanagariFontFamily
import com.example.ui.viewmodel.NoticeMateViewModel
import com.example.ui.viewmodel.NoticeWorkspaceState
import com.example.ui.viewmodel.NoticesUiState
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Calendar
import java.util.Locale

enum class NoticeMateTab(val labelRes: Int) {
    WORKSPACE(R.string.workspace_tab),
    TEST_SUITE(R.string.test_suite_tab),
    SAVED_CLOUD(R.string.saved_notices_tab)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainFeatureScreen(
    currentUserId: String,
    onSignedOut: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember { CredentialManager.create(context) }

    val viewModel: NoticeMateViewModel = viewModel(
        key = currentUserId,
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) {
                    "APPLICATION_KEY missing from CreationExtras"
                }
                val databaseId = app.getString(R.string.firestore_database_id)
                val db = FirebaseFirestore.getInstance(databaseId)
                NoticeMateViewModel(
                    repository = NoticeRepository(db),
                    fastRouterService = FastRouterService(),
                    currentUserId = currentUserId
                )
            }
        }
    )

    val workspaceState by viewModel.workspaceState.collectAsStateWithLifecycle()
    val savedNoticesState by viewModel.savedNoticesState.collectAsStateWithLifecycle()
    var activeTab by rememberSaveable { mutableStateOf(NoticeMateTab.WORKSPACE) }

    BackHandler(enabled = activeTab != NoticeMateTab.WORKSPACE) {
        activeTab = NoticeMateTab.WORKSPACE
    }

    // Android Text-to-Speech (EN-IN and HI-IN)
    var isSpeaking by remember { mutableStateOf(false) }
    val ttsRef = remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(context) {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                engine?.language = Locale.forLanguageTag("en-IN")
            }
        }
        ttsRef.value = engine
        onDispose {
            engine?.stop()
            engine?.shutdown()
        }
    }

    fun speakText(text: String, localeTag: String) {
        val engine = ttsRef.value ?: return
        engine.language = Locale.forLanguageTag(localeTag)
        isSpeaking = true
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "noticemate_tts")
    }

    fun stopSpeaking() {
        ttsRef.value?.stop()
        isSpeaking = false
    }

    // Zero-permission Android Photo Picker
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            decodeUriToBitmap(context, uri)?.let { bmp ->
                viewModel.onNoticeImageSelected(bmp)
            }
        }
    }

    // Full-screen CameraX Physical Notice Scanner state
    var showCameraXScanner by rememberSaveable { mutableStateOf(false) }

    if (showCameraXScanner) {
        com.example.ui.camera.CameraXNoticeScannerScreen(
            onNoticeCapturedAndConfirmed = { bitmap ->
                showCameraXScanner = false
                viewModel.onNoticeImageSelected(bitmap)
            },
            onDismiss = {
                showCameraXScanner = false
            }
        )
        return
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWideScreen = maxWidth >= 720.dp

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = stringResource(R.string.app_name),
                                style = MaterialTheme.typography.titleLarge
                            )
                            Text(
                                text = "Gemma 4 Grounded Notice Assistant · हिंदी & English",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = NotoSansDevanagariFontFamily
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                stopSpeaking()
                                signOutUser(
                                    credentialManager = credentialManager,
                                    onSignOutComplete = onSignedOut,
                                    scope = scope
                                )
                            },
                            modifier = Modifier.testTag("sign_out_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                                contentDescription = stringResource(R.string.sign_out)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            },
            bottomBar = {
                if (!isWideScreen) {
                    NavigationBar {
                        NoticeMateTab.entries.forEach { tab ->
                            NavigationBarItem(
                                selected = activeTab == tab,
                                onClick = { activeTab = tab },
                                icon = {
                                    Icon(
                                        imageVector = when (tab) {
                                            NoticeMateTab.WORKSPACE -> Icons.Default.Description
                                            NoticeMateTab.TEST_SUITE -> Icons.Default.Science
                                            NoticeMateTab.SAVED_CLOUD -> Icons.Default.BookmarkAdded
                                        },
                                        contentDescription = stringResource(tab.labelRes)
                                    )
                                },
                                label = { Text(stringResource(tab.labelRes)) },
                                modifier = Modifier.testTag("nav_tab_${tab.name.lowercase()}")
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (isWideScreen) {
                    NavigationRail(modifier = Modifier.fillMaxHeight()) {
                        NoticeMateTab.entries.forEach { tab ->
                            NavigationRailItem(
                                selected = activeTab == tab,
                                onClick = { activeTab = tab },
                                icon = {
                                    Icon(
                                        imageVector = when (tab) {
                                            NoticeMateTab.WORKSPACE -> Icons.Default.Description
                                            NoticeMateTab.TEST_SUITE -> Icons.Default.Science
                                            NoticeMateTab.SAVED_CLOUD -> Icons.Default.BookmarkAdded
                                        },
                                        contentDescription = stringResource(tab.labelRes)
                                    )
                                },
                                label = { Text(stringResource(tab.labelRes)) },
                                modifier = Modifier.testTag("rail_tab_${tab.name.lowercase()}")
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    when (activeTab) {
                        NoticeMateTab.WORKSPACE -> WorkspaceTabContent(
                            state = workspaceState,
                            onPickPhoto = {
                                photoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            onCaptureCamera = { showCameraXScanner = true },
                            onOpenTestSuite = { activeTab = NoticeMateTab.TEST_SUITE },
                            onSelectStep = viewModel::selectPipelineStep,
                            onRawTextChange = viewModel::updateRawInputText,
                            onAnalyze = viewModel::analyzeCurrentNotice,
                            onClearQuote = { viewModel.inspectEvidenceQuote(null) },
                            onInspectQuote = viewModel::inspectEvidenceQuote,
                            onBilingualModeChange = viewModel::setBilingualDisplayMode,
                            onToggleChecklist = viewModel::toggleChecklistTask,
                            onSaveToCloud = viewModel::saveActiveNoticeToCloud,
                            onAskQuestion = viewModel::askGroundedQuestion,
                            onExportCalendar = { doc -> launchCalendarDeadlineIntent(context, doc) },
                            isSpeaking = isSpeaking,
                            onSpeakEnglish = {
                                val doc = workspaceState.activeNotice
                                val speech = buildString {
                                    append("Notice summary: ").append(doc.purposeEn).append(". ")
                                    append("Deadline: ").append(doc.deadline).append(". ")
                                    append("Action checklist: ")
                                    doc.checklistEn.forEachIndexed { idx, s ->
                                        append("Step ${idx + 1}: $s. ")
                                    }
                                    if (doc.flaggedMissingEn.isNotEmpty()) {
                                        append("Flagged missing details: ")
                                        doc.flaggedMissingEn.forEach { append("$it ") }
                                    }
                                }
                                speakText(speech, "en-IN")
                            },
                            onSpeakHindi = {
                                val doc = workspaceState.activeNotice
                                val speech = buildString {
                                    append("नोटिस सारांश: ").append(doc.purposeHi).append("। ")
                                    append("अंतिम तिथि: ").append(doc.deadline).append("। ")
                                    append("हिंदी चेकलिस्ट: ")
                                    doc.checklistHi.forEachIndexed { idx, s ->
                                        append("चरण ${idx + 1}: $s। ")
                                    }
                                    if (doc.flaggedMissingHi.isNotEmpty()) {
                                        append("अनुपस्थित जानकारी: ")
                                        doc.flaggedMissingHi.forEach { append("$it ") }
                                    }
                                }
                                speakText(speech, "hi-IN")
                            },
                            onStopSpeaking = ::stopSpeaking
                        )

                        NoticeMateTab.TEST_SUITE -> AmbiguousTestSuiteTabContent(
                            samples = SampleNotices.ALL,
                            onLoadSample = { sample ->
                                viewModel.loadSampleNotice(sample)
                                activeTab = NoticeMateTab.WORKSPACE
                            }
                        )

                        NoticeMateTab.SAVED_CLOUD -> SavedNoticesTabContent(
                            uiState = savedNoticesState,
                            onOpenNotice = { doc ->
                                viewModel.openSavedNotice(doc)
                                activeTab = NoticeMateTab.WORKSPACE
                            },
                            onDeleteNotice = viewModel::deleteSavedNotice
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceTabContent(
    state: NoticeWorkspaceState,
    onPickPhoto: () -> Unit,
    onCaptureCamera: () -> Unit,
    onOpenTestSuite: () -> Unit,
    onSelectStep: (Int) -> Unit,
    onRawTextChange: (String) -> Unit,
    onAnalyze: () -> Unit,
    onClearQuote: () -> Unit,
    onInspectQuote: (String) -> Unit,
    onBilingualModeChange: (com.example.data.model.BilingualDisplayMode) -> Unit,
    onToggleChecklist: (Int) -> Unit,
    onSaveToCloud: () -> Unit,
    onAskQuestion: (String) -> Unit,
    onExportCalendar: (NoticeDocument) -> Unit,
    isSpeaking: Boolean,
    onSpeakEnglish: () -> Unit,
    onSpeakHindi: () -> Unit,
    onStopSpeaking: () -> Unit
) {
    val checklistTasks = remember(state.activeNotice) {
        GroundingValidator.buildChecklistTasks(state.activeNotice)
    }
    val missingGaps = remember(state.activeNotice) {
        GroundingValidator.buildMissingGaps(state.activeNotice)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .widthIn(max = 760.dp),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 5-Step Pipeline Progress Header
        item {
            PipelineStepperBar(
                currentStep = state.activePipelineStep,
                onStepSelected = onSelectStep
            )
        }

        // FastRouter Gemma 4 Status Banner
        item {
            Surface(
                color = if (state.isFastRouterKeyConfigured) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.primaryContainer
                },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Verified,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = if (state.isFastRouterKeyConfigured) {
                            "FastRouter Gemma 4 API active (google/gemma-4-31b-it) + Deterministic Evidence Validator."
                        } else {
                            "Grounded Benchmark Mode active. Set FASTROUTER_API_KEY in the AI Studio Secrets panel for live FastRouter Gemma 4 API calls."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        // Status Toast / Loading Progress
        if (state.isAnalyzing || state.isExtractingOcr || state.statusMessage != null) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (state.isAnalyzing || state.isExtractingOcr) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        if (state.statusMessage != null) {
                            Text(
                                text = state.statusMessage,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }

        // Step 1: Multi-Modal Notice Input Controls
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "01 · MULTI-MODAL NOTICE INPUT",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Upload Circular Photo, Scan Poster, or Load Test Suite",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onPickPhoto,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("pick_photo_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.AddPhotoAlternate,
                                contentDescription = "Upload photo",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Gallery", style = MaterialTheme.typography.labelMedium)
                        }

                        OutlinedButton(
                            onClick = onCaptureCamera,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("capture_camera_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.CameraAlt,
                                contentDescription = "Scan camera",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Camera", style = MaterialTheme.typography.labelMedium)
                        }

                        OutlinedButton(
                            onClick = onOpenTestSuite,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("open_test_suite_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Science,
                                contentDescription = "Test Suite",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Samples", style = MaterialTheme.typography.labelMedium)
                        }
                    }

                    if (state.attachedBitmap != null) {
                        Image(
                            bitmap = state.attachedBitmap.asImageBitmap(),
                            contentDescription = "Attached notice image",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(150.dp)
                                .clip(RoundedCornerShape(12.dp))
                        )
                    }

                    Button(
                        onClick = onAnalyze,
                        enabled = !state.isAnalyzing,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("analyze_notice_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.analyze_notice_cta),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Step 2: Source Notice & Evidence Quote Highlighter
        item {
            SourceEvidenceCard(
                rawNoticeText = state.rawInputText,
                highlightedQuote = state.selectedQuoteHighlight,
                onTextChange = onRawTextChange,
                onClearHighlight = onClearQuote
            )
        }

        // Step 3: Extracted Fields & Verbatim Evidence Quotes
        item {
            ExtractedFieldsAndGroundingCard(
                notice = state.activeNotice,
                selectedQuote = state.selectedQuoteHighlight,
                onInspectQuote = onInspectQuote,
                onExportCalendar = onExportCalendar
            )
        }

        // Step 4: Grounding & Gap Detection Alert Card
        item {
            GapDetectionAlertCard(
                gaps = missingGaps,
                onAskSuggestedQuestion = onAskQuestion
            )
        }

        // Step 5: Bilingual Action Checklist (English + Hindi) + TTS + Cloud Save
        item {
            BilingualActionChecklistCard(
                tasks = checklistTasks,
                displayMode = state.bilingualMode,
                isSpeaking = isSpeaking,
                isSaving = state.isSaving,
                isSavedToCloud = state.activeNotice.id.isNotBlank(),
                onDisplayModeChange = onBilingualModeChange,
                onToggleTask = onToggleChecklist,
                onInspectQuote = onInspectQuote,
                onSpeakEnglish = onSpeakEnglish,
                onSpeakHindi = onSpeakHindi,
                onStopSpeaking = onStopSpeaking,
                onSaveToCloud = onSaveToCloud
            )
        }

        // Step 5+: Grounded Follow-Up Q&A
        item {
            GroundedQaCard(
                qaEntriesSerialized = state.activeNotice.qaEntries,
                isAsking = state.isAskingQa,
                onAskQuestion = onAskQuestion,
                onInspectQuote = onInspectQuote
            )
        }
    }
}

@Composable
private fun AmbiguousTestSuiteTabContent(
    samples: List<SampleNoticeCase>,
    onLoadSample: (SampleNoticeCase) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .widthIn(max = 760.dp),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "HACKATHON BENCHMARK SUITE · PS 02",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Ambiguous Campus Notices Test Suite",
                    style = MaterialTheme.typography.headlineMedium
                )
                Text(
                    text = "Curated real-world campus circulars with buried requirements and intentional information gaps to verify strict grounding and refusal to hallucinate.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        items(samples, key = { it.id }) { sample ->
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onLoadSample(sample) }
                    .testTag("sample_notice_card_${sample.id}")
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = sample.badge,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                        Text(
                            text = sample.department,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Text(
                        text = sample.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = "\"${sample.rawNoticeText}\"",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f))
                            .padding(10.dp)
                    ) {
                        Text(
                            text = "Expected Gap Audit: ${sample.expectedAmbiguityNote}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }

                    Button(
                        onClick = { onLoadSample(sample) },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.testTag("load_sample_button_${sample.id}")
                    ) {
                        Text("Load into 5-Step Pipeline")
                    }
                }
            }
        }
    }
}

@Composable
private fun SavedNoticesTabContent(
    uiState: NoticesUiState,
    onOpenNotice: (NoticeDocument) -> Unit,
    onDeleteNotice: (String) -> Unit
) {
    when (uiState) {
        is NoticesUiState.Loading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        is NoticesUiState.Error -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = uiState.message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        is NoticesUiState.Success -> {
            if (uiState.notices.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.BookmarkAdded,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = "No Cloud-Saved Notices Yet",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = "Tap 'Save to Cloud' on any analyzed notice in the Workspace to sync your bilingual checklist across devices.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 760.dp),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(uiState.notices, key = { it.id }) { doc ->
                        Card(
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenNotice(doc) }
                                .testTag("saved_notice_card_${doc.id}")
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = doc.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = { onDeleteNotice(doc.id) },
                                        modifier = Modifier.testTag("delete_saved_notice_${doc.id}")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.DeleteOutline,
                                            contentDescription = "Delete notice"
                                        )
                                    }
                                }

                                Text(
                                    text = doc.purposeEn,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Deadline: ${doc.deadline}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = "Checklist: ${doc.completedIndices.size}/${doc.checklistEn.size} done",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun decodeUriToBitmap(context: Context, uri: Uri): Bitmap? {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.isMutableRequired = true
            }
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
        }
    } catch (e: Exception) {
        null
    }
}

private fun launchCalendarDeadlineIntent(context: Context, notice: NoticeDocument) {
    val startMillis = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, 1)
        set(Calendar.HOUR_OF_DAY, 10)
        set(Calendar.MINUTE, 0)
    }.timeInMillis

    val intent = Intent(Intent.ACTION_INSERT).apply {
        data = CalendarContract.Events.CONTENT_URI
        putExtra(CalendarContract.Events.TITLE, "[NoticeBuddy] ${notice.title} (${notice.deadline})")
        putExtra(
            CalendarContract.Events.DESCRIPTION,
            buildString {
                append("Purpose: ").append(notice.purposeEn).append("\n")
                append("Hindi: ").append(notice.purposeHi).append("\n\n")
                append("Documents Required:\n").append(notice.documentsRequired).append("\n\n")
                append("Checklist:\n")
                notice.checklistEn.forEachIndexed { idx, item ->
                    append("${idx + 1}. $item\n")
                }
            }
        )
        putExtra(CalendarContract.Events.EVENT_LOCATION, notice.contactDetails)
        putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
        putExtra(CalendarContract.EXTRA_EVENT_END_TIME, startMillis + 3600_000L)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}
