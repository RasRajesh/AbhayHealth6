package com.example.abhayhealth.feature.me

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import android.content.Intent
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Bloodtype
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ContactPhone
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.net.toUri
import androidx.compose.foundation.Image
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import java.io.File
import com.example.abhayhealth.R
import com.example.abhayhealth.core.common.findActivity
import com.example.abhayhealth.core.designsystem.components.AbhayHealthBrandLogo
import com.example.abhayhealth.feature.records.export.ExportReportDialog
import com.example.abhayhealth.feature.records.export.ShareHealthMetricsPdfDialog

@Composable
fun MeScreen(
    viewModel: MeViewModel,
    onEditProfileClick: () -> Unit,
    onSignOutClick: () -> Unit,
    modifier: Modifier = Modifier,
    onNavigateToRecords: () -> Unit = {},
    onNavigateToMedicines: () -> Unit = {}
) {
    val context = LocalContext.current
    val patientName by viewModel.patientName.collectAsState()
    val patientProfile by viewModel.patientProfile.collectAsState()
    val isDeleting by viewModel.isDeleting.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val exportState by viewModel.exportState.collectAsState()
    val showExportDialog by viewModel.showExportDialog.collectAsState()
    val showMetricsPdfDialog by viewModel.showMetricsPdfDialog.collectAsState()
    val metricsPdfState by viewModel.metricsPdfState.collectAsState()
    val pdfSelectedRange by viewModel.pdfSelectedRange.collectAsState()
    val pdfPreviewCounts by viewModel.pdfPreviewCounts.collectAsState()
    val recordCount by viewModel.recordCount.collectAsState()
    val medicationCount by viewModel.medicationCount.collectAsState()
    val vitalCount by viewModel.vitalCount.collectAsState()
    val intakeLogCount by viewModel.intakeLogCount.collectAsState()
    val appLanguage by viewModel.appLanguage.collectAsState()
    val isMedRemindersEnabled by viewModel.isMedRemindersEnabled.collectAsState()
    val isAiSummaryEnabled by viewModel.isAiSummaryEnabled.collectAsState()
    val accountPhoneNumber by viewModel.accountPhoneNumber.collectAsState()
    val optionalBackupGmail by viewModel.optionalBackupGmail.collectAsState()
    val isGoogleLinked by viewModel.isGoogleLinked.collectAsState()
    val isLinkingOrUploadingGmail by viewModel.isLinkingOrUploadingGmail.collectAsState()
    val gmailBackupMessage by viewModel.gmailBackupMessage.collectAsState()

    val displayName = patientProfile?.fullName?.ifBlank { null }
        ?: if (!patientName.isNullOrBlank()) patientName!! else stringResource(id = R.string.me_profile_default)

    val profileBitmap = remember(patientProfile?.photoUri) {
        val uriStr = patientProfile?.photoUri
        if (uriStr.isNullOrBlank()) null
        else {
            try {
                if (uriStr.startsWith("/")) {
                    val file = File(uriStr)
                    if (file.exists()) BitmapFactory.decodeFile(uriStr) else null
                } else {
                    val uri = uriStr.toUri()
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        BitmapFactory.decodeStream(stream)
                    }
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    var showDeleteAccountDialog by remember { mutableStateOf(false) }
    var showSecurityPrivacyDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showPreferencesDialog by remember { mutableStateOf(false) }
    var showGmailBackupDialog by remember { mutableStateOf(false) }

    if (showExportDialog) {
        ExportReportDialog(
            state = exportState,
            recordCount = recordCount,
            medicationCount = medicationCount,
            vitalCount = vitalCount,
            onDismiss = { viewModel.dismissExportDialog() },
            onShareClick = { viewModel.exportAndShareReport(context) },
            onViewClick = { viewModel.exportAndViewReport(context) },
            onPrintClick = { viewModel.exportAndPrintReport(context) }
        )
    }

    if (showMetricsPdfDialog) {
        ShareHealthMetricsPdfDialog(
            state = metricsPdfState,
            selectedRange = pdfSelectedRange,
            counts = pdfPreviewCounts,
            onSelectRange = { viewModel.setPdfSelectedRange(it) },
            onDismiss = { viewModel.dismissMetricsPdfDialog() },
            onShareClick = { viewModel.shareDoctorMetricsPdf(context) },
            onViewClick = { viewModel.viewDoctorMetricsPdf(context) },
            onPrintClick = { viewModel.printDoctorMetricsPdf(context) },
            onSaveToUri = { uri, onReady ->
                viewModel.saveDoctorMetricsPdfToUri(context, uri, onReady)
            }
        )
    }

    if (showGmailBackupDialog) {
        var gmailInput by rememberSaveable(optionalBackupGmail) {
            mutableStateOf(optionalBackupGmail.orEmpty())
        }
        AlertDialog(
            onDismissRequest = { if (!isLinkingOrUploadingGmail) showGmailBackupDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Email,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = {
                Text(
                    text = stringResource(id = R.string.me_gmail_dialog_title),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(id = R.string.me_gmail_dialog_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (!isGoogleLinked) {
                        OutlinedButton(
                            onClick = {
                                val activity = context.findActivity() ?: context
                                viewModel.linkGoogleAccount(activity)
                            },
                            enabled = !isLinkingOrUploadingGmail,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("link_google_account_button")
                        ) {
                            Text(
                                text = stringResource(id = R.string.me_gmail_btn_link_google),
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            HorizontalDivider(modifier = Modifier.weight(1f))
                            Text(
                                text = "  ${stringResource(id = R.string.me_gmail_or_enter)}  ",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            HorizontalDivider(modifier = Modifier.weight(1f))
                        }
                    }

                    OutlinedTextField(
                        value = gmailInput,
                        onValueChange = { gmailInput = it },
                        label = { Text(stringResource(id = R.string.me_gmail_input_label)) },
                        placeholder = { Text(stringResource(id = R.string.me_gmail_input_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("optional_gmail_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.saveOptionalBackupGmail(gmailInput) {
                            showGmailBackupDialog = false
                        }
                    },
                    enabled = !isLinkingOrUploadingGmail && gmailInput.isNotBlank(),
                    modifier = Modifier.testTag("save_optional_gmail_button")
                ) {
                    Text(stringResource(id = R.string.me_gmail_btn_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showGmailBackupDialog = false },
                    enabled = !isLinkingOrUploadingGmail
                ) {
                    Text(stringResource(id = R.string.common_cancel))
                }
            }
        )
    }

    if (showSecurityPrivacyDialog) {
        AlertDialog(
            onDismissRequest = { showSecurityPrivacyDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = {
                Text("Security & Privacy", fontWeight = FontWeight.Bold)
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SecurityPrivacyItem(
                        title = "+91 OTP & Per-User Isolation",
                        description = "All health records, vitals, medications, and symptoms are strictly isolated to your authenticated Firebase UID."
                    )
                    SecurityPrivacyItem(
                        title = "Real-Time Cloud Firestore & Optional Gmail",
                        description = "Your health vault stays synced across devices, with optional Gmail linking to email backups anytime."
                    )
                    SecurityPrivacyItem(
                        title = "App Check & Offline Room Cache",
                        description = "Requests are protected by Firebase App Check while keeping offline access cached securely on your device."
                    )
                    SecurityPrivacyItem(
                        title = "Full Data Ownership & Erasure",
                        description = "Export clinical PDF reports anytime or permanently delete both your cloud vault and local device records."
                    )
                }
            },
            confirmButton = {
                Button(onClick = { showSecurityPrivacyDialog = false }) {
                    Text("Done")
                }
            }
        )
    }

    if (showLanguageDialog) {
        val languages = listOf("English", "Telugu (తెలుగు)")
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            icon = {
                Icon(Icons.Default.Language, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            },
            title = {
                Text(
                    text = if (appLanguage.contains("Telugu", ignoreCase = true)) "భాషను ఎంచుకోండి" else "Select Language",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    languages.forEach { lang ->
                        val isSelected = lang == appLanguage || (lang == "English" && appLanguage.isBlank())
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.setAppLanguage(lang)
                                    showLanguageDialog = false
                                    val toastMsg = if (lang.contains("Telugu", ignoreCase = true)) {
                                        "భాష తెలుగుకు మార్చబడింది"
                                    } else {
                                        "Language changed to English"
                                    }
                                    Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
                                }
                                .padding(vertical = 12.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = {
                                    viewModel.setAppLanguage(lang)
                                    showLanguageDialog = false
                                    val toastMsg = if (lang.contains("Telugu", ignoreCase = true)) {
                                        "భాష తెలుగుకు మార్చబడింది"
                                    } else {
                                        "Language changed to English"
                                    }
                                    Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
                                }
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(text = lang, style = MaterialTheme.typography.bodyLarge, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLanguageDialog = false }) {
                    Text(if (appLanguage.contains("Telugu", ignoreCase = true)) "మూసివేయి" else "Close")
                }
            }
        )
    }

    if (showPreferencesDialog) {
        AlertDialog(
            onDismissRequest = { showPreferencesDialog = false },
            icon = {
                Icon(Icons.Default.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            },
            title = {
                Text("App Preferences", fontWeight = FontWeight.Bold)
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Medication Reminders", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                            Text("Schedule alerts for pending daily doses", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = isMedRemindersEnabled,
                            onCheckedChange = { viewModel.setMedRemindersEnabled(it) }
                        )
                    }

                    HorizontalDivider()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("AI Assist Summaries", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                            Text("Generate automated clinical insights on Home screen", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = isAiSummaryEnabled,
                            onCheckedChange = { viewModel.setAiSummaryEnabled(it) }
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showPreferencesDialog = false }) {
                    Text("Done")
                }
            }
        )
    }

    if (showDeleteAccountDialog) {
        AlertDialog(
            onDismissRequest = { if (!isDeleting) showDeleteAccountDialog = false },
            title = { Text("Delete Account & Health Data?") },
            text = {
                Text("Deleting your account permanently removes your patient profile, medical records, medications, vitals, and symptom history from both Firebase Cloud Firestore and this device. This action cannot be undone.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteAccountDialog = false
                        viewModel.deleteAccountAndAllData(context, onDeleted = onSignOutClick)
                    },
                    enabled = !isDeleting,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    if (isDeleting) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.onError,
                            modifier = Modifier.size(20.dp)
                        )
                    } else {
                        Text("Delete Account")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteAccountDialog = false },
                    enabled = !isDeleting
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (errorMessage != null) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = errorMessage!!,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { viewModel.clearMessages() }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close error message",
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }
        }

        if (gmailBackupMessage != null) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = gmailBackupMessage!!,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { viewModel.clearGmailBackupMessage() }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close status message",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(id = R.string.nav_me),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(id = R.string.me_profile_tagline),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))
                // Official AbhayHealth brand launcher logo
                AbhayHealthBrandLogo(
                    size = 46.dp,
                    elevation = 2.dp
                )
            }
        }

        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onEditProfileClick),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (profileBitmap != null) {
                        Image(
                            bitmap = profileBitmap.asImageBitmap(),
                            contentDescription = "Profile picture",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(54.dp)
                                .clip(CircleShape)
                        )
                    } else {
                        // Official AbhayHealth Logo as verified medical profile badge
                        AbhayHealthBrandLogo(
                            size = 54.dp,
                            elevation = 3.dp
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        val subtitleContact = listOfNotNull(
                            accountPhoneNumber?.takeIf { it.isNotBlank() },
                            optionalBackupGmail?.takeIf { it.isNotBlank() }
                        ).joinToString(" • ").ifBlank {
                            stringResource(id = R.string.me_profile_tagline)
                        }
                        Text(
                            text = subtitleContact,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = stringResource(id = R.string.profile_btn_edit),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        // Optional Gmail Backup & Cloud Upload Card
        item {
            Text(
                text = stringResource(id = R.string.me_gmail_section_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Email,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(id = R.string.me_gmail_card_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = optionalBackupGmail?.takeIf { it.isNotBlank() }
                                    ?: stringResource(id = R.string.me_gmail_not_added),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (!optionalBackupGmail.isNullOrBlank()) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                        if (!accountPhoneNumber.isNullOrBlank()) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PhoneAndroid,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Text(
                                        text = accountPhoneNumber!!,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showGmailBackupDialog = true },
                            enabled = !isLinkingOrUploadingGmail,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("add_optional_gmail_button")
                        ) {
                            Text(
                                text = if (optionalBackupGmail.isNullOrBlank()) {
                                    stringResource(id = R.string.me_gmail_btn_add_edit)
                                } else {
                                    stringResource(id = R.string.me_gmail_btn_change)
                                },
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                        }

                        Button(
                            onClick = { viewModel.uploadHealthDataToGmail(context) },
                            enabled = !isLinkingOrUploadingGmail,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1.2f)
                                .testTag("upload_to_gmail_button")
                        ) {
                            if (isLinkingOrUploadingGmail) {
                                CircularProgressIndicator(
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(id = R.string.me_gmail_uploading),
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.CloudUpload,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(id = R.string.me_gmail_btn_upload),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }

        // 1. Clinical Identity & Emergency Passport Card (Replaces redundant bottom-tab redirects)
        item {
            Text(
                text = "Clinical Identity & Emergency",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onEditProfileClick),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Blood Group & Allergies Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f))
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Bloodtype,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(22.dp)
                                )
                                Column {
                                    Text(
                                        text = "BLOOD GROUP",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                    Text(
                                        text = patientProfile?.bloodGroup?.ifBlank { null } ?: "Not Set",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }

                        Surface(
                            modifier = Modifier.weight(1.3f),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.35f))
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.WarningAmber,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.size(22.dp)
                                )
                                Column {
                                    Text(
                                        text = "ALLERGIES",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.tertiary
                                    )
                                    Text(
                                        text = patientProfile?.allergies?.ifBlank { null } ?: "None reported",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }

                    // Known Conditions
                    if (!patientProfile?.healthConditions.isNullOrBlank()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.MedicalServices,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "Conditions: ${patientProfile!!.healthConditions}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // Emergency Contact
                    val contactName = patientProfile?.emergencyContactName?.ifBlank { null }
                    val contactPhone = patientProfile?.emergencyContactPhone?.ifBlank { null }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.ContactPhone,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            Column {
                                Text(
                                    text = "EMERGENCY CONTACT",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = contactName ?: "No contact saved",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                if (contactPhone != null) {
                                    Text(
                                        text = contactPhone,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        if (!contactPhone.isNullOrBlank()) {
                            Button(
                                onClick = {
                                    try {
                                        val intent = Intent(Intent.ACTION_DIAL, "tel:$contactPhone".toUri())
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Cannot dial: $contactPhone", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                shape = RoundedCornerShape(20.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError
                                )
                            ) {
                                Icon(Icons.Default.Call, contentDescription = "Call", modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Call", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                            }
                        } else {
                            TextButton(onClick = onEditProfileClick) {
                                Text("+ Add Contact", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        // 2. Health Passport Export & Device Security
        item {
            Text(
                text = "Clinical Documents & Security",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column {
                    ProfileMenuItem(
                        icon = Icons.Default.PictureAsPdf,
                        title = stringResource(id = R.string.me_item_metrics_pdf_title),
                        subtitle = "$vitalCount vitals • $intakeLogCount doses logged",
                        onClick = { viewModel.openMetricsPdfDialog() },
                        modifier = Modifier.testTag("me_share_metrics_pdf_item")
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ProfileMenuItem(
                        icon = Icons.Default.IosShare,
                        title = stringResource(id = R.string.me_item_export_title),
                        subtitle = "$recordCount reports • $medicationCount meds • $vitalCount vitals",
                        onClick = { viewModel.openExportDialog() }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ProfileMenuItem(
                        icon = Icons.Default.Shield,
                        title = stringResource(id = R.string.me_item_privacy_title),
                        subtitle = "Isolated zero-knowledge app sandbox storage",
                        onClick = { showSecurityPrivacyDialog = true }
                    )
                }
            }
        }

        // 3. App Settings & Reminders
        item {
            Text(
                text = stringResource(id = R.string.me_sec_config),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column {
                    ProfileMenuItem(
                        icon = Icons.Default.Language,
                        title = stringResource(id = R.string.me_item_language_title),
                        subtitle = appLanguage,
                        onClick = { showLanguageDialog = true }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Medication Reminders", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.titleMedium)
                            Text("Active schedule alerts for daily doses", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = isMedRemindersEnabled,
                            onCheckedChange = { viewModel.setMedRemindersEnabled(it) }
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("AI Telemetry Insights", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.titleMedium)
                            Text("Automated clinical summaries on Home", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = isAiSummaryEnabled,
                            onCheckedChange = { viewModel.setAiSummaryEnabled(it) }
                        )
                    }
                }
            }
        }

        // 4. Account Management & Sign Out
        item {
            Text(
                text = "Account Management",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column {
                    ProfileMenuItem(
                        icon = Icons.Default.DeleteForever,
                        title = "Delete Account & Health Data",
                        subtitle = "Permanently remove profile and local records from device",
                        onClick = { showDeleteAccountDialog = true }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ProfileMenuItem(
                        icon = Icons.AutoMirrored.Filled.ExitToApp,
                        title = stringResource(id = R.string.me_item_sign_out_title),
                        subtitle = stringResource(id = R.string.me_item_sign_out_sub),
                        onClick = onSignOutClick
                    )
                }
            }
        }
    }
}

@Composable
private fun SecurityPrivacyItem(title: String, description: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
        }
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 26.dp, top = 2.dp)
        )
    }
}

@Composable
private fun ProfileMenuItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
