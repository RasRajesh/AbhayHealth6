package com.example.abhayhealth.feature.me

import android.content.Context
import android.net.Uri
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.abhayhealth.core.common.DefaultDispatcherProvider
import com.example.abhayhealth.core.common.DispatcherProvider
import com.example.abhayhealth.data.remote.FirestoreHealthRepository
import com.example.abhayhealth.data.remote.FirestoreHealthSnapshot
import com.example.abhayhealth.data.repository.FirebaseAuthRepository
import com.example.abhayhealth.data.sync.FirestoreHealthSyncManager
import com.example.abhayhealth.domain.model.AuthState
import com.example.abhayhealth.domain.repository.AuthRepository
import com.example.abhayhealth.domain.repository.PatientProfileRepository
import com.example.abhayhealth.domain.repository.UserPreferencesRepository
import com.example.abhayhealth.feature.medicines.MedicationDao
import com.example.abhayhealth.feature.medicines.MedicineIntakeLogDao
import com.example.abhayhealth.feature.medicines.MedicineIntakeLogEntity
import com.example.abhayhealth.feature.records.DocumentImportManager
import com.example.abhayhealth.feature.records.MedicalRecordRepository
import com.example.abhayhealth.feature.records.export.ExportReportState
import com.example.abhayhealth.feature.records.export.HealthMetricsPdfGenerator
import com.example.abhayhealth.feature.records.export.HealthMetricsPdfPreviewCounts
import com.example.abhayhealth.feature.records.export.MedicalReportExportManager
import com.example.abhayhealth.feature.trends.DashboardTimeRange
import com.example.abhayhealth.feature.vitals.VitalReadingRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MeViewModel(
    private val userPreferencesRepository: UserPreferencesRepository,
    private val authRepository: AuthRepository,
    private val medicalRecordRepository: MedicalRecordRepository,
    private val patientProfileRepository: PatientProfileRepository,
    private val dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
    private val medicationDao: MedicationDao? = null,
    private val vitalReadingRepository: VitalReadingRepository? = null,
    private val firestoreHealthRepository: FirestoreHealthRepository? = null,
    private val firestoreHealthSyncManager: FirestoreHealthSyncManager? = null,
    private val medicineIntakeLogDao: MedicineIntakeLogDao? = null
) : ViewModel() {

    val patientName: StateFlow<String?> = userPreferencesRepository.patientName.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = null
    )

    val appLanguage: StateFlow<String> = userPreferencesRepository.appLanguage.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = "English"
    )

    val isMedRemindersEnabled: StateFlow<Boolean> = userPreferencesRepository.isMedRemindersEnabled.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = true
    )

    val isAiSummaryEnabled: StateFlow<Boolean> = userPreferencesRepository.isAiSummaryEnabled.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = false
    )

    val accountPhoneNumber: StateFlow<String?> = authRepository.authState.map { state ->
        when (state) {
            is AuthState.Authenticated -> state.user.phoneNumber
            else -> authRepository.currentUser?.phoneNumber
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = authRepository.currentUser?.phoneNumber
    )

    val isGoogleLinked: StateFlow<Boolean> = authRepository.authState.map { state ->
        when (state) {
            is AuthState.Authenticated -> !state.user.email.isNullOrBlank()
            else -> !authRepository.currentUser?.email.isNullOrBlank()
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = !authRepository.currentUser?.email.isNullOrBlank()
    )

    val optionalBackupGmail: StateFlow<String?> = combine(
        userPreferencesRepository.optionalBackupGmail,
        authRepository.authState
    ) { storedEmail, state ->
        val authEmail = (state as? AuthState.Authenticated)?.user?.email
            ?: authRepository.currentUser?.email
        storedEmail?.takeIf { it.isNotBlank() } ?: authEmail?.takeIf { it.isNotBlank() }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = authRepository.currentUser?.email
    )

    fun setAppLanguage(language: String) {
        viewModelScope.launch(dispatcherProvider.main) {
            userPreferencesRepository.setAppLanguage(language)
        }
    }

    fun setMedRemindersEnabled(enabled: Boolean) {
        viewModelScope.launch(dispatcherProvider.main) {
            userPreferencesRepository.setMedRemindersEnabled(enabled)
        }
    }

    fun setAiSummaryEnabled(enabled: Boolean) {
        viewModelScope.launch(dispatcherProvider.main) {
            userPreferencesRepository.setAiSummaryEnabled(enabled)
        }
    }

    private val _isDeleting = MutableStateFlow(false)
    val isDeleting: StateFlow<Boolean> = _isDeleting.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _exportState = MutableStateFlow<ExportReportState>(ExportReportState.Idle)
    val exportState: StateFlow<ExportReportState> = _exportState.asStateFlow()

    private val _showExportDialog = MutableStateFlow(false)
    val showExportDialog: StateFlow<Boolean> = _showExportDialog.asStateFlow()

    private val _recordCount = MutableStateFlow(0)
    val recordCount: StateFlow<Int> = _recordCount.asStateFlow()

    private val _medicationCount = MutableStateFlow(0)
    val medicationCount: StateFlow<Int> = _medicationCount.asStateFlow()

    private val _vitalCount = MutableStateFlow(0)
    val vitalCount: StateFlow<Int> = _vitalCount.asStateFlow()

    private val _intakeLogCount = MutableStateFlow(0)
    val intakeLogCount: StateFlow<Int> = _intakeLogCount.asStateFlow()

    private val _showMetricsPdfDialog = MutableStateFlow(false)
    val showMetricsPdfDialog: StateFlow<Boolean> = _showMetricsPdfDialog.asStateFlow()

    private val _metricsPdfState = MutableStateFlow<ExportReportState>(ExportReportState.Idle)
    val metricsPdfState: StateFlow<ExportReportState> = _metricsPdfState.asStateFlow()

    private val _pdfSelectedRange = MutableStateFlow(DashboardTimeRange.ALL)
    val pdfSelectedRange: StateFlow<DashboardTimeRange> = _pdfSelectedRange.asStateFlow()

    private val _pdfPreviewCounts = MutableStateFlow(HealthMetricsPdfPreviewCounts())
    val pdfPreviewCounts: StateFlow<HealthMetricsPdfPreviewCounts> = _pdfPreviewCounts.asStateFlow()

    private val _patientProfile = MutableStateFlow<com.example.abhayhealth.domain.model.PatientProfile?>(null)
    val patientProfile: StateFlow<com.example.abhayhealth.domain.model.PatientProfile?> = _patientProfile.asStateFlow()

    private val _isLinkingOrUploadingGmail = MutableStateFlow(false)
    val isLinkingOrUploadingGmail: StateFlow<Boolean> = _isLinkingOrUploadingGmail.asStateFlow()

    private val _gmailBackupMessage = MutableStateFlow<String?>(null)
    val gmailBackupMessage: StateFlow<String?> = _gmailBackupMessage.asStateFlow()

    init {
        observeProfile()
        authRepository.currentUser?.uid?.ifBlank { null }?.let { refreshCounts(it) }
    }

    private fun observeProfile() {
        val uid = authRepository.currentUser?.uid?.ifBlank { null } ?: return
        viewModelScope.launch(dispatcherProvider.main) {
            patientProfileRepository.getProfile(uid).collect {
                _patientProfile.value = it
            }
        }
    }

    fun saveOptionalBackupGmail(rawEmail: String, onSaved: () -> Unit = {}) {
        val trimmed = rawEmail.trim()
        if (!FirebaseAuthRepository.isValidOptionalEmail(trimmed)) {
            _errorMessage.value = "Please enter a valid Gmail address (e.g. name@gmail.com)."
            return
        }
        val user = authRepository.currentUser
        if (user == null) {
            _errorMessage.value = "Please sign in first to save a backup Gmail."
            return
        }

        viewModelScope.launch(dispatcherProvider.main) {
            _errorMessage.value = null
            userPreferencesRepository.setOptionalBackupGmail(trimmed)
            firestoreHealthRepository?.enqueueUserAccount(
                userId = user.uid,
                email = trimmed,
                displayName = user.displayName,
                phoneNumber = user.phoneNumber
            )
            _gmailBackupMessage.value = "Optional Gmail saved: $trimmed"
            onSaved()
        }
    }

    fun linkGoogleAccount(activityContext: Context) {
        if (_isLinkingOrUploadingGmail.value) return
        val user = authRepository.currentUser
        if (user == null) {
            _errorMessage.value = "Please sign in first before linking a Gmail account."
            return
        }

        viewModelScope.launch(dispatcherProvider.main) {
            _isLinkingOrUploadingGmail.value = true
            _errorMessage.value = null
            val result = authRepository.linkGmailAccount(activityContext)
            _isLinkingOrUploadingGmail.value = false

            result.onSuccess { linkedEmail ->
                userPreferencesRepository.setOptionalBackupGmail(linkedEmail)
                firestoreHealthRepository?.enqueueUserAccount(
                    userId = user.uid,
                    email = linkedEmail,
                    displayName = user.displayName,
                    phoneNumber = user.phoneNumber
                )
                _gmailBackupMessage.value = "Gmail linked & synced: $linkedEmail"
            }.onFailure { throwable ->
                if (throwable !is GetCredentialCancellationException) {
                    _errorMessage.value = throwable.localizedMessage
                        ?: "Could not link Google account. You can type your Gmail manually instead."
                }
            }
        }
    }

    fun uploadHealthDataToGmail(context: Context, explicitTargetGmail: String? = null) {
        if (_isLinkingOrUploadingGmail.value) return
        val user = authRepository.currentUser
        if (user == null || user.uid.isBlank()) {
            _errorMessage.value = "Please sign in to upload your health backup."
            return
        }

        val medDao = medicationDao
        val vitalRepo = vitalReadingRepository
        if (medDao == null || vitalRepo == null) {
            _errorMessage.value = "Health database unavailable. Please try again."
            return
        }

        viewModelScope.launch(dispatcherProvider.main) {
            _isLinkingOrUploadingGmail.value = true
            _errorMessage.value = null
            try {
                val candidateGmail = explicitTargetGmail?.trim()?.takeIf { it.isNotEmpty() }
                    ?: optionalBackupGmail.value?.trim()?.takeIf { it.isNotEmpty() }
                    ?: user.email?.trim()?.takeIf { it.isNotEmpty() }

                if (!candidateGmail.isNullOrBlank() &&
                    FirebaseAuthRepository.isValidOptionalEmail(candidateGmail)
                ) {
                    userPreferencesRepository.setOptionalBackupGmail(candidateGmail)
                }

                // 1. Sync all local records, meds, vitals, symptoms, and profile to Firebase Firestore
                firestoreHealthSyncManager?.syncAllLocalToCloud(
                    userId = user.uid,
                    email = candidateGmail,
                    displayName = user.displayName,
                    phoneNumber = user.phoneNumber
                )

                // 2. Build comprehensive clinical PDF backup & readable text digest
                val exportManager = MedicalReportExportManager(
                    context = context.applicationContext,
                    medicalRecordRepository = medicalRecordRepository,
                    medicationDao = medDao,
                    vitalReadingRepository = vitalRepo,
                    patientProfileRepository = patientProfileRepository
                )
                val pdfFile = exportManager.generateReport(user.uid)

                val (patientName, summaryText) = withContext(dispatcherProvider.io) {
                    val profile = runCatching { patientProfileRepository.getProfile(user.uid).first() }.getOrNull()
                    val records = runCatching { medicalRecordRepository.getRecords(user.uid).first() }.getOrElse { emptyList() }
                    val meds = runCatching { medDao.getMedicationsByAccount(user.uid).first() }.getOrElse { emptyList() }
                    val vitals = runCatching { vitalRepo.getAllReadings(user.uid).first() }.getOrElse { emptyList() }
                    val takenDoses = runCatching {
                        medicineIntakeLogDao?.getLogsByAccountSync(user.uid)
                            ?.count { it.status == MedicineIntakeLogEntity.STATUS_TAKEN } ?: 0
                    }.getOrDefault(0)

                    val resolvedName = profile?.fullName?.ifBlank { null } ?: "Patient"
                    val summary = buildString {
                        appendLine("JeevaSutra Personal Health Backup")
                        appendLine("Patient: $resolvedName")
                        user.phoneNumber?.takeIf { it.isNotBlank() }?.let {
                            appendLine("Verified Mobile: $it")
                        }
                        candidateGmail?.let {
                            appendLine("Backup Gmail: $it")
                        }
                        appendLine()
                        appendLine("Summary Counts:")
                        appendLine("- Medical Records: ${records.size}")
                        appendLine("- Active Medications: ${meds.size}")
                        appendLine("- Stored Doses Taken: $takenDoses")
                        appendLine("- Logged Vitals: ${vitals.size}")
                        profile?.bloodGroup?.takeIf { it.isNotBlank() }?.let {
                            appendLine("- Blood Group: $it")
                        }
                        profile?.allergies?.takeIf { it.isNotBlank() }?.let {
                            appendLine("- Allergies: $it")
                        }
                        appendLine()
                        appendLine("Attached: Complete clinical PDF report synced with your JeevaSutra vault.")
                    }
                    resolvedName to summary
                }

                exportManager.uploadBackupToGmail(
                    file = pdfFile,
                    targetGmail = candidateGmail,
                    patientName = patientName,
                    summaryText = summaryText
                )

                _gmailBackupMessage.value = if (!candidateGmail.isNullOrBlank()) {
                    "Synced to Cloud Firestore & opened Gmail backup for $candidateGmail."
                } else {
                    "Synced to Cloud Firestore & opened Gmail to upload your backup PDF."
                }
            } catch (e: Exception) {
                _errorMessage.value = e.localizedMessage ?: "Failed to prepare Gmail health backup."
            } finally {
                _isLinkingOrUploadingGmail.value = false
            }
        }
    }

    fun clearGmailBackupMessage() {
        _gmailBackupMessage.value = null
    }

    fun openExportDialog() {
        val uid = authRepository.currentUser?.uid?.ifBlank { null } ?: return
        refreshCounts(uid)
        _exportState.value = ExportReportState.Idle
        _showExportDialog.value = true
    }

    fun dismissExportDialog() {
        _showExportDialog.value = false
        _exportState.value = ExportReportState.Idle
    }

    private fun refreshCounts(uid: String) {
        viewModelScope.launch(dispatcherProvider.main) {
            try {
                medicalRecordRepository.getRecords(uid).first().let {
                    _recordCount.value = it.size
                }
                medicationDao?.getMedicationsByAccount(uid)?.first()?.let {
                    _medicationCount.value = it.size
                }
                vitalReadingRepository?.getAllReadings(uid)?.first()?.let {
                    _vitalCount.value = it.size
                }
                withContext(dispatcherProvider.io) {
                    medicineIntakeLogDao?.getLogsByAccountSync(uid)?.count {
                        it.status == MedicineIntakeLogEntity.STATUS_TAKEN
                    } ?: 0
                }.let { taken ->
                    _intakeLogCount.value = taken
                }
                refreshMetricsPdfPreview(uid)
            } catch (_: Exception) {}
        }
    }

    fun openMetricsPdfDialog() {
        val uid = authRepository.currentUser?.uid?.ifBlank { null } ?: return
        _metricsPdfState.value = ExportReportState.Idle
        _showMetricsPdfDialog.value = true
        refreshMetricsPdfPreview(uid)
    }

    fun dismissMetricsPdfDialog() {
        _showMetricsPdfDialog.value = false
        _metricsPdfState.value = ExportReportState.Idle
    }

    fun setPdfSelectedRange(range: DashboardTimeRange) {
        _pdfSelectedRange.value = range
        authRepository.currentUser?.uid?.ifBlank { null }?.let { refreshMetricsPdfPreview(it) }
    }

    private fun refreshMetricsPdfPreview(uid: String) {
        viewModelScope.launch(dispatcherProvider.main) {
            val counts = withContext(dispatcherProvider.io) {
                val vitals = runCatching { vitalReadingRepository?.getAllReadings(uid)?.first() }.getOrElse { emptyList() }.orEmpty()
                val meds = runCatching { medicationDao?.getMedicationsByAccount(uid)?.first() }.getOrElse { emptyList() }.orEmpty()
                val logs = runCatching { medicineIntakeLogDao?.getLogsByAccountSync(uid) }.getOrElse { emptyList() }.orEmpty()
                val extracted = runCatching { medicalRecordRepository.getAllExtractedInfo(uid).first() }.getOrElse { emptyList() }
                val recs = runCatching { medicalRecordRepository.getRecords(uid).first() }.getOrElse { emptyList() }
                val synthetic = MedicalReportExportManager.mergeWithLocalFallback(
                    snapshot = FirestoreHealthSnapshot(),
                    userId = uid,
                    fallbackVitals = vitals,
                    fallbackMedications = meds,
                    fallbackIntakeLogs = logs,
                    fallbackRecords = recs,
                    fallbackExtractedInfos = extracted
                )
                HealthMetricsPdfGenerator.computePreviewCounts(synthetic, _pdfSelectedRange.value)
            }
            _pdfPreviewCounts.value = counts
        }
    }

    fun shareDoctorMetricsPdf(context: Context) {
        generateMetricsPdf(context) { result ->
            MedicalReportExportManager.sharePdfFile(
                context = context,
                file = result.file,
                subject = "JeevaSutra Doctor Progress Summary",
                message = "Sharing Firestore health metrics (Blood Pressure, Glucose, Weight) & medicine intake history from JeevaSutra.",
                chooserTitle = "Share Progress PDF with Doctor"
            )
        }
    }

    fun viewDoctorMetricsPdf(context: Context) {
        generateMetricsPdf(context) { result ->
            MedicalReportExportManager.viewPdfFile(context, result.file)
        }
    }

    fun printDoctorMetricsPdf(context: Context) {
        generateMetricsPdf(context) { result ->
            MedicalReportExportManager.printPdfFile(context, result.file)
        }
    }

    fun saveDoctorMetricsPdfToUri(
        context: Context,
        @Suppress("UNUSED_PARAMETER") destinationUri: Uri,
        onReady: (File) -> Unit
    ) {
        generateMetricsPdf(context) { result ->
            onReady(result.file)
        }
    }

    private fun generateMetricsPdf(
        context: Context,
        onReady: (com.example.abhayhealth.feature.records.export.HealthMetricsPdfReportResult) -> Unit
    ) {
        val uid = authRepository.currentUser?.uid?.ifBlank { null }
        if (uid == null) {
            _metricsPdfState.value = ExportReportState.Error("Please sign in to export health metrics summary.")
            return
        }
        viewModelScope.launch(dispatcherProvider.main) {
            _metricsPdfState.value = ExportReportState.Generating
            try {
                val (profile, vitals, meds, logs, recs, extracted) = withContext(dispatcherProvider.io) {
                    val p = runCatching { patientProfileRepository.getProfile(uid).first() }.getOrNull()
                    val v = runCatching { vitalReadingRepository?.getAllReadings(uid)?.first() }.getOrElse { emptyList() }.orEmpty()
                    val m = runCatching { medicationDao?.getMedicationsByAccount(uid)?.first() }.getOrElse { emptyList() }.orEmpty()
                    val l = runCatching { medicineIntakeLogDao?.getLogsByAccountSync(uid) }.getOrElse { emptyList() }.orEmpty()
                    val r = runCatching { medicalRecordRepository.getRecords(uid).first() }.getOrElse { emptyList() }
                    val e = runCatching { medicalRecordRepository.getAllExtractedInfo(uid).first() }.getOrElse { emptyList() }
                    MeMetricsBundle(p, v, m, l, r, e)
                }

                val result = MedicalReportExportManager.generateFirestoreMetricsPdfReport(
                    context = context.applicationContext,
                    firestoreHealthRepository = firestoreHealthRepository,
                    userId = uid,
                    timeRange = _pdfSelectedRange.value,
                    fallbackProfile = profile,
                    fallbackVitals = vitals,
                    fallbackMedications = meds,
                    fallbackIntakeLogs = logs,
                    fallbackRecords = recs,
                    fallbackExtractedInfos = extracted
                )
                _pdfPreviewCounts.value = result.counts
                _metricsPdfState.value = ExportReportState.Success(result.file)
                onReady(result)
            } catch (e: Exception) {
                _metricsPdfState.value = ExportReportState.Error(
                    e.localizedMessage ?: "Failed to generate health metrics PDF summary."
                )
            }
        }
    }

    private data class MeMetricsBundle(
        val profile: com.example.abhayhealth.domain.model.PatientProfile?,
        val vitals: List<com.example.abhayhealth.feature.vitals.VitalReadingEntity>,
        val meds: List<com.example.abhayhealth.feature.medicines.PrescriptionMedicationEntity>,
        val logs: List<MedicineIntakeLogEntity>,
        val records: List<com.example.abhayhealth.feature.records.MedicalRecord>,
        val extracted: List<com.example.abhayhealth.feature.records.ExtractedMedicalInfo>
    )

    fun exportAndShareReport(context: Context) {
        performExport(context) { manager, file, patientName ->
            manager.shareReport(file, patientName)
        }
    }

    fun exportAndViewReport(context: Context) {
        performExport(context) { manager, file, _ ->
            manager.viewReport(file)
        }
    }

    fun exportAndPrintReport(context: Context) {
        performExport(context) { manager, file, _ ->
            manager.printReport(file)
        }
    }

    private fun performExport(context: Context, onReady: (MedicalReportExportManager, File, String) -> Unit) {
        val uid = authRepository.currentUser?.uid
        if (uid.isNullOrEmpty()) {
            _exportState.value = ExportReportState.Error("Please sign in to export medical history.")
            return
        }

        val medDao = medicationDao
        val vitalRepo = vitalReadingRepository
        if (medDao == null || vitalRepo == null) {
            _exportState.value = ExportReportState.Error("Database services unavailable.")
            return
        }

        viewModelScope.launch(dispatcherProvider.main) {
            _exportState.value = ExportReportState.Generating
            try {
                val exportManager = MedicalReportExportManager(
                    context = context.applicationContext,
                    medicalRecordRepository = medicalRecordRepository,
                    medicationDao = medDao,
                    vitalReadingRepository = vitalRepo,
                    patientProfileRepository = patientProfileRepository
                )
                val file = exportManager.generateReport(uid)
                val profile = try { patientProfileRepository.getProfile(uid).first() } catch (_: Exception) { null }
                val patientName = profile?.fullName ?: "Patient"

                _exportState.value = ExportReportState.Success(file)
                onReady(exportManager, file, patientName)
            } catch (e: Exception) {
                _exportState.value = ExportReportState.Error(e.message ?: "Failed to generate PDF report.")
            }
        }
    }

    fun deleteAccountAndAllData(context: Context, onDeleted: () -> Unit) {
        if (_isDeleting.value) return

        val uid = authRepository.currentUser?.uid?.ifBlank { null } ?: "local_user"
        val hasFirebaseAuth = authRepository.currentUser != null

        _isDeleting.value = true
        _errorMessage.value = null

        val purgeAllLocalHealthData: suspend () -> Unit = {
            withContext(dispatcherProvider.io) {
                try {
                    // 1. Cancel all scheduled medication alarms and wipe medications table
                    val allMeds = medicationDao?.getAllMedicationsSync() ?: emptyList()
                    allMeds.forEach { med ->
                        try {
                            com.example.abhayhealth.feature.medicines.MedicationAlarmScheduler.cancelMedicationAlarm(context, med.id)
                        } catch (_: Exception) {}
                    }
                    medicationDao?.deleteAllByAccount(uid)
                    medicationDao?.deleteAllByAccount("local_user")
                    medicationDao?.deleteAllMedications()

                    // Wipe medicine intake history logs
                    medicineIntakeLogDao?.deleteAllByAccount(uid)
                    medicineIntakeLogDao?.deleteAllByAccount("local_user")
                    medicineIntakeLogDao?.deleteAllLogs()

                    // 2. Wipe vital readings
                    vitalReadingRepository?.deleteAllByAccount(uid)
                    vitalReadingRepository?.deleteAllByAccount("local_user")
                    vitalReadingRepository?.clearAllReadings()

                    // 3. Delete records and physical image/document files
                    val targetUids = setOf(uid, "local_user")
                    for (targetUid in targetUids) {
                        try {
                            val records = medicalRecordRepository.getRecords(targetUid).first()
                            records.forEach { record ->
                                medicalRecordRepository.deleteRecord(record)
                                DocumentImportManager.deleteFile(record.localFilePath, context, record, targetUid)
                            }
                            patientProfileRepository.deleteProfile(targetUid)
                        } catch (_: Exception) {}
                    }

                    // 4. Reset patient name & optional backup gmail preferences
                    userPreferencesRepository.setPatientName("")
                    userPreferencesRepository.setOptionalBackupGmail("")

                    // 5. Delete saved AI chat session files
                    try {
                        File(context.filesDir, "ai_chat_session_$uid.json").delete()
                        File(context.filesDir, "ai_chat_session_local_user.json").delete()
                    } catch (_: Exception) {}

                    // 6. Reset counts
                    _recordCount.value = 0
                    _medicationCount.value = 0
                    _vitalCount.value = 0
                    _intakeLogCount.value = 0
                    _patientProfile.value = null
                } catch (_: Exception) {}
            }
        }

        if (hasFirebaseAuth) {
            // Step 1: Delete cloud Firestore documents while FirebaseAuth.currentUser is still valid
            viewModelScope.launch(dispatcherProvider.main) {
                runCatching {
                    kotlinx.coroutines.withTimeoutOrNull(8_000L) {
                        firestoreHealthRepository?.deleteAllUserData(uid)
                    }
                }
                // Step 2: Delete remote Firebase Auth account
                authRepository.deleteAccount { _, _ ->
                    viewModelScope.launch(dispatcherProvider.main) {
                        // Step 3: Purge all local health data thoroughly
                        purgeAllLocalHealthData()
                        authRepository.signOut()
                        _isDeleting.value = false
                        onDeleted()
                    }
                }
            }
        } else {
            // Local account: Purge all data immediately and sign out
            viewModelScope.launch(dispatcherProvider.main) {
                purgeAllLocalHealthData()
                authRepository.signOut()
                _isDeleting.value = false
                onDeleted()
            }
        }
    }

    fun clearMessages() {
        _errorMessage.value = null
        _gmailBackupMessage.value = null
    }
}
