package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.example.data.model.MissingGapUi
import com.example.data.model.NoticeDocument
import com.example.data.model.QuoteGroundingStatus
import com.example.data.validation.GroundingValidator
import com.example.ui.theme.NotoSansDevanagariFontFamily
import java.util.Locale

@Composable
fun PipelineStepperBar(
    currentStep: Int,
    onStepSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val steps = listOf(
        1 to "01 · INPUT",
        2 to "02 · EXTRACT",
        3 to "03 · INTERPRET",
        4 to "04 · VALIDATE",
        5 to "05 · ACT"
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        steps.forEach { (stepNum, label) ->
            val isActive = stepNum == currentStep
            val isDone = stepNum < currentStep
            val bgColor = when {
                isActive -> MaterialTheme.colorScheme.primary
                isDone -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
            val contentColor = when {
                isActive -> MaterialTheme.colorScheme.onPrimary
                isDone -> MaterialTheme.colorScheme.onSecondaryContainer
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }

            Surface(
                color = bgColor,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onStepSelected(stepNum) }
                    .testTag("pipeline_step_$stepNum")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (isDone) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Completed $label",
                            tint = contentColor,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun SourceEvidenceCard(
    rawNoticeText: String,
    highlightedQuote: String?,
    onTextChange: (String) -> Unit,
    onClearHighlight: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
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
                Column {
                    Text(
                        text = "02 · SOURCE NOTICE & EVIDENCE TRACE",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Original / OCR Circular Text",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                if (!highlightedQuote.isNullOrBlank()) {
                    IconButton(
                        onClick = onClearHighlight,
                        modifier = Modifier.testTag("clear_quote_highlight_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Clear quote highlight"
                        )
                    }
                }
            }

            if (!highlightedQuote.isNullOrBlank()) {
                val annotatedSource = buildHighlightedSourceText(
                    sourceText = rawNoticeText,
                    quote = highlightedQuote,
                    highlightBackground = MaterialTheme.colorScheme.secondaryContainer,
                    highlightColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.secondary,
                            RoundedCornerShape(12.dp)
                        )
                        .padding(14.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Verified,
                                contentDescription = "Active evidence quote",
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Verbatim Evidence Highlighted in Source Notice:",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.secondary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = annotatedSource,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            OutlinedTextField(
                value = rawNoticeText,
                onValueChange = onTextChange,
                label = { Text("Paste or edit extracted notice text") },
                minLines = 3,
                maxLines = 7,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("notice_raw_text_input")
            )
        }
    }
}

@Composable
private fun buildHighlightedSourceText(
    sourceText: String,
    quote: String,
    highlightBackground: androidx.compose.ui.graphics.Color,
    highlightColor: androidx.compose.ui.graphics.Color
) = buildAnnotatedString {
    val cleanQuote = quote.trim().trim('"', '“', '”')
    val idx = sourceText.lowercase(Locale.ROOT).indexOf(cleanQuote.lowercase(Locale.ROOT))
    if (cleanQuote.isNotBlank() && idx >= 0) {
        append(sourceText.substring(0, idx))
        withStyle(
            SpanStyle(
                background = highlightBackground,
                color = highlightColor,
                fontWeight = FontWeight.Bold
            )
        ) {
            append(sourceText.substring(idx, idx + cleanQuote.length))
        }
        append(sourceText.substring(idx + cleanQuote.length))
    } else {
        append(sourceText)
    }
}

@Composable
fun ExtractedFieldsAndGroundingCard(
    notice: NoticeDocument,
    selectedQuote: String?,
    onInspectQuote: (String) -> Unit,
    onExportCalendar: (NoticeDocument) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "03 · STRUCTURED EVIDENCE EXTRACTION",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = notice.title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Verified,
                            contentDescription = "Grounding score",
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "${notice.groundingScore}% Grounded",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))

            // Purpose (Bilingual)
            EvidenceFieldItem(
                label = "PURPOSE / मुख्य उद्देश्य",
                valueEn = notice.purposeEn,
                valueHi = notice.purposeHi,
                quote = notice.purposeQuote,
                rawSource = notice.rawNoticeText,
                isSelected = selectedQuote == notice.purposeQuote && notice.purposeQuote.isNotBlank(),
                onInspectQuote = onInspectQuote
            )

            // Deadline + Calendar Action
            EvidenceFieldItem(
                label = "DEADLINE / अंतिम तिथि",
                valueEn = notice.deadline,
                valueHi = null,
                quote = notice.deadlineQuote,
                rawSource = notice.rawNoticeText,
                isSelected = selectedQuote == notice.deadlineQuote && notice.deadlineQuote.isNotBlank(),
                onInspectQuote = onInspectQuote,
                trailingAction = {
                    OutlinedButton(
                        onClick = { onExportCalendar(notice) },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.testTag("export_calendar_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.CalendarMonth,
                            contentDescription = "Add deadline to calendar",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Add to Calendar",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            )

            // Eligibility (Bilingual)
            EvidenceFieldItem(
                label = "ELIGIBILITY / पात्रता",
                valueEn = notice.eligibilityEn,
                valueHi = notice.eligibilityHi,
                quote = notice.eligibilityQuote,
                rawSource = notice.rawNoticeText,
                isSelected = selectedQuote == notice.eligibilityQuote && notice.eligibilityQuote.isNotBlank(),
                onInspectQuote = onInspectQuote
            )

            // Required Documents & Contact
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "DOCUMENTS / दस्तावेज़",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = notice.documentsRequired.ifBlank { "None specified" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .clickable(enabled = notice.contactQuote.isNotBlank()) {
                            onInspectQuote(notice.contactQuote)
                        }
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "CONTACT / संपर्क कार्यालय",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = notice.contactDetails.ifBlank { "Not specified" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun EvidenceFieldItem(
    label: String,
    valueEn: String,
    valueHi: String?,
    quote: String,
    rawSource: String,
    isSelected: Boolean,
    onInspectQuote: (String) -> Unit,
    trailingAction: (@Composable () -> Unit)? = null
) {
    val groundingStatus = GroundingValidator.verifyQuoteAgainstSource(quote, rawSource)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isSelected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            )
            .border(
                width = 1.dp,
                color = if (isSelected) MaterialTheme.colorScheme.secondary
                else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                shape = RoundedCornerShape(12.dp)
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            GroundingStatusPill(status = groundingStatus)
        }

        Text(
            text = valueEn,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )

        if (!valueHi.isNullOrBlank()) {
            Text(
                text = valueHi,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = NotoSansDevanagariFontFamily
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (quote.isNotBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable { onInspectQuote(quote) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.FormatQuote,
                    contentDescription = "Source quote",
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "Source quote: \"$quote\" (Tap to locate in circular)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }

        if (trailingAction != null) {
            Spacer(modifier = Modifier.height(2.dp))
            trailingAction()
        }
    }
}

@Composable
fun GroundingStatusPill(status: QuoteGroundingStatus) {
    val containerColor = when (status) {
        QuoteGroundingStatus.VERIFIED_EXACT -> MaterialTheme.colorScheme.secondaryContainer
        QuoteGroundingStatus.PARTIAL_MATCH -> MaterialTheme.colorScheme.primaryContainer
        QuoteGroundingStatus.UNVERIFIED -> MaterialTheme.colorScheme.tertiaryContainer
    }
    val textColor = when (status) {
        QuoteGroundingStatus.VERIFIED_EXACT -> MaterialTheme.colorScheme.onSecondaryContainer
        QuoteGroundingStatus.PARTIAL_MATCH -> MaterialTheme.colorScheme.onPrimaryContainer
        QuoteGroundingStatus.UNVERIFIED -> MaterialTheme.colorScheme.onTertiaryContainer
    }
    Surface(
        color = containerColor,
        shape = CircleShape
    ) {
        Text(
            text = status.labelEn,
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
fun GapDetectionAlertCard(
    gaps: List<MissingGapUi>,
    onAskSuggestedQuestion: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("gap_detection_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    1.5.dp,
                    MaterialTheme.colorScheme.tertiary,
                    RoundedCornerShape(16.dp)
                )
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.WarningAmber,
                    contentDescription = "Gap detection warning",
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(22.dp)
                )
                Column {
                    Text(
                        text = "04 · GROUNDING & GAP DETECTION (PS 02)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Text(
                        text = "Flagged Missing Details · अस्पष्ट या अनुपस्थित जानकारी",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = NotoSansDevanagariFontFamily
                        ),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }

            Text(
                text = "NoticeMate AI refuses to invent unstated facts. Verify these missing requirements with the issuing office:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )

            gaps.forEach { gap ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "• ${gap.descriptionEn}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "• ${gap.descriptionHi}",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = NotoSansDevanagariFontFamily
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    AssistChip(
                        onClick = { onAskSuggestedQuestion(gap.followUpQuestionEn) },
                        label = {
                            Text(
                                text = "Verify: ${gap.followUpQuestionEn}",
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = androidx.compose.material.icons.Icons.AutoMirrored.Filled.HelpOutline,
                                contentDescription = "Ask question",
                                modifier = Modifier.size(15.dp)
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                        )
                    )
                }
            }
        }
    }
}
