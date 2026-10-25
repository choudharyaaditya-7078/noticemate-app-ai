package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.BilingualDisplayMode
import com.example.data.model.ChecklistTaskUi
import com.example.data.model.GroundedQaTurn
import com.example.ui.theme.NotoSansDevanagariFontFamily

@Composable
fun BilingualActionChecklistCard(
    tasks: List<ChecklistTaskUi>,
    displayMode: BilingualDisplayMode,
    isSpeaking: Boolean,
    isSaving: Boolean,
    isSavedToCloud: Boolean,
    onDisplayModeChange: (BilingualDisplayMode) -> Unit,
    onToggleTask: (Int) -> Unit,
    onInspectQuote: (String) -> Unit,
    onSpeakEnglish: () -> Unit,
    onSpeakHindi: () -> Unit,
    onStopSpeaking: () -> Unit,
    onSaveToCloud: () -> Unit,
    modifier: Modifier = Modifier
) {
    val completedCount = tasks.count { it.isCompleted }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("bilingual_checklist_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "05 · BILINGUAL ACTION CHECKLIST",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "English Checklist & हिंदी चेकलिस्ट ($completedCount/${tasks.size})",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = NotoSansDevanagariFontFamily
                        ),
                        fontWeight = FontWeight.Bold
                    )
                }

                Button(
                    onClick = onSaveToCloud,
                    enabled = !isSaving,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.testTag("save_notice_cloud_button")
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.CloudUpload,
                            contentDescription = "Save to cloud",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isSavedToCloud) "Synced" else stringResource(R.string.save_to_cloud),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }

            // Bilingual Mode Toggle + TTS Read-Aloud Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BilingualDisplayMode.entries.forEach { mode ->
                    FilterChip(
                        selected = displayMode == mode,
                        onClick = { onDisplayModeChange(mode) },
                        label = {
                            Text(
                                text = mode.label,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = NotoSansDevanagariFontFamily
                                )
                            )
                        },
                        modifier = Modifier.testTag("bilingual_mode_${mode.name.lowercase()}")
                    )
                }
            }

            // Audio Read-Aloud Accessibility Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onSpeakEnglish,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("tts_english_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = "Listen in English",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.listen_english),
                        style = MaterialTheme.typography.labelMedium
                    )
                }

                OutlinedButton(
                    onClick = onSpeakHindi,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("tts_hindi_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = "Listen in Hindi",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.listen_hindi),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = NotoSansDevanagariFontFamily
                        )
                    )
                }

                if (isSpeaking) {
                    OutlinedButton(
                        onClick = onStopSpeaking,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.testTag("tts_stop_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.StopCircle,
                            contentDescription = "Stop audio",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))

            tasks.forEach { task ->
                ChecklistTaskItemRow(
                    task = task,
                    displayMode = displayMode,
                    onToggle = { onToggleTask(task.index) },
                    onInspectQuote = { onInspectQuote(task.sourceQuote) }
                )
            }
        }
    }
}

@Composable
private fun ChecklistTaskItemRow(
    task: ChecklistTaskUi,
    displayMode: BilingualDisplayMode,
    onToggle: () -> Unit,
    onInspectQuote: () -> Unit
) {
    val strikeThrough = if (task.isCompleted) TextDecoration.LineThrough else TextDecoration.None

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (task.isCompleted) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            )
            .border(
                width = 1.dp,
                color = if (task.isCompleted) MaterialTheme.colorScheme.secondary
                else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                shape = RoundedCornerShape(12.dp)
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            Checkbox(
                checked = task.isCompleted,
                onCheckedChange = { onToggle() },
                modifier = Modifier.testTag("checklist_checkbox_${task.index}")
            )
            Spacer(modifier = Modifier.width(6.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (displayMode == BilingualDisplayMode.SIDE_BY_SIDE || displayMode == BilingualDisplayMode.ENGLISH_ONLY) {
                    Text(
                        text = "${task.index + 1}. ${task.textEn}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        textDecoration = strikeThrough,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                if (displayMode == BilingualDisplayMode.SIDE_BY_SIDE || displayMode == BilingualDisplayMode.HINDI_ONLY) {
                    Text(
                        text = "• ${task.textHi}",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = NotoSansDevanagariFontFamily
                        ),
                        textDecoration = strikeThrough,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (task.sourceQuote.isNotBlank()) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onInspectQuote() }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FormatQuote,
                            contentDescription = "Inspect quote",
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "Evidence: \"${task.sourceQuote}\"",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun GroundedQaCard(
    qaEntriesSerialized: List<String>,
    isAsking: Boolean,
    onAskQuestion: (String) -> Unit,
    onInspectQuote: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var questionText by rememberSaveable { mutableStateOf("") }
    val qaTurns = qaEntriesSerialized.map { GroundedQaTurn.deserialize(it) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("grounded_qa_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "EVIDENCE-GROUNDED Q&A · साक्ष्य-आधारित प्रश्नोत्तर",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = NotoSansDevanagariFontFamily
                ),
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Ask About This Notice (Strict Grounding)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Gemma 4 answers strictly using facts stated in the notice or explicitly flags when the circular does not specify the answer.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = questionText,
                    onValueChange = { questionText = it },
                    placeholder = { Text("e.g., What documents are required? / What is the income cutoff?") },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("grounded_qa_input")
                )
                Button(
                    onClick = {
                        if (questionText.isNotBlank()) {
                            onAskQuestion(questionText)
                            questionText = ""
                        }
                    },
                    enabled = !isAsking && questionText.isNotBlank(),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .height(54.dp)
                        .testTag("grounded_qa_send_button")
                ) {
                    if (isAsking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Ask question"
                        )
                    }
                }
            }

            qaTurns.forEach { turn ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Q: ${turn.question}",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        Surface(
                            color = if (turn.isAnsweredFromText) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.tertiaryContainer
                            },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = if (turn.isAnsweredFromText) Icons.Default.Verified else Icons.Default.WarningAmber,
                                    contentDescription = null,
                                    modifier = Modifier.size(13.dp)
                                )
                                Text(
                                    text = if (turn.isAnsweredFromText) "Grounded" else "Unstated in Notice",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }

                    Text(
                        text = turn.answerEn,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = turn.answerHi,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = NotoSansDevanagariFontFamily
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (turn.supportingQuote.isNotBlank()) {
                        Text(
                            text = "Source quote: \"${turn.supportingQuote}\"",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.clickable { onInspectQuote(turn.supportingQuote) }
                        )
                    }
                }
            }
        }
    }
}
