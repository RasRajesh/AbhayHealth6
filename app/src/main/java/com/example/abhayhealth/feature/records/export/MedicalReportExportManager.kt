package com.example.abhayhealth.feature.records.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.print.PrintAttributes
import android.print.PrintManager
import androidx.core.content.FileProvider
import com.example.abhayhealth.data.remote.FirestoreHealthRepository
import com.example.abhayhealth.data.remote.FirestoreHealthSnapshot
import com.example.abhayhealth.data.remote.MedicalRecordDoc
import com.example.abhayhealth.data.remote.MedicationDoc
import com.example.abhayhealth.data.remote.MedicineIntakeLogDoc
import com.example.abhayhealth.data.remote.SymptomLogDoc
import com.example.abhayhealth.data.remote.VitalReadingDoc
import com.example.abhayhealth.domain.model.PatientProfile
import com.example.abhayhealth.domain.repository.PatientProfileRepository
import com.example.abhayhealth.feature.medicines.MedicationDao
import com.example.abhayhealth.feature.medicines.MedicineIntakeLogEntity
import com.example.abhayhealth.feature.medicines.PrescriptionMedicationEntity
import com.example.abhayhealth.feature.records.ExtractedMedicalInfo
import com.example.abhayhealth.feature.records.MedicalRecord
import com.example.abhayhealth.feature.records.MedicalRecordRepository
import com.example.abhayhealth.feature.symptoms.SymptomLogEntity
import com.example.abhayhealth.feature.trends.DashboardTimeRange
import com.example.abhayhealth.feature.vitals.VitalReadingEntity
import com.example.abhayhealth.feature.vitals.VitalReadingRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manager that coordinates pulling medical records, medications, vitals, and profile
 * from the Room database and compiling them into an exported PDF report.
 */
class MedicalReportExportManager(
    private val context: Context,
    private val medicalRecordRepository: MedicalRecordRepository,
    private val medicationDao: MedicationDao,
    private val vitalReadingRepository: VitalReadingRepository,
    private val patientProfileRepository: PatientProfileRepository
) {

    private val generator = MedicalReportPdfGenerator(context)

    suspend fun generateReport(accountUid: String): File = withContext(Dispatchers.IO) {
        val profile = try {
            patientProfileRepository.getProfile(accountUid).first()
        } catch (_: Exception) {
            null
        }

        val medications = try {
            medicationDao.getMedicationsByAccount(accountUid).first()
        } catch (_: Exception) {
            emptyList()
        }

        val records = try {
            medicalRecordRepository.getRecords(accountUid).first()
        } catch (_: Exception) {
            emptyList()
        }

        val extractedList = try {
            medicalRecordRepository.getAllExtractedInfo(accountUid).first()
        } catch (_: Exception) {
            emptyList()
        }
        val extractedMap = extractedList.associateBy { it.recordId }

        val vitals = try {
            vitalReadingRepository.getAllReadings(accountUid).first()
        } catch (_: Exception) {
            emptyList()
        }

        val symptoms = try {
            com.example.abhayhealth.feature.records.MedicalRecordDatabase.getInstance(context).symptomLogDao().getAllSymptoms(accountUid).first()
        } catch (_: Exception) {
            emptyList()
        }

        val reportData = MedicalReportPdfGenerator.ReportData(
            patientProfile = profile,
            medications = medications,
            records = records,
            extractedInfoMap = extractedMap,
            vitals = vitals,
            symptoms = symptoms,
            generatedAt = System.currentTimeMillis()
        )

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val reportsDir = File(context.cacheDir, "reports").apply { mkdirs() }
        val targetFile = File(reportsDir, "JeevaSutra_Medical_Summary_$timeStamp.pdf")

        generator.generatePdfReport(reportData, targetFile)
    }

    fun getReportUri(file: File): Uri = getReportUri(context, file)

    fun shareReport(file: File, patientName: String) {
        sharePdfFile(
            context = context,
            file = file,
            subject = "Medical Summary & Medication Schedule - $patientName",
            message = "Sharing verified medical history, active medications, and vital logs from JeevaSutra for $patientName.",
            chooserTitle = "Share Medical Report with Doctor / Provider"
        )
    }

    fun uploadBackupToGmail(
        file: File,
        targetGmail: String?,
        patientName: String,
        summaryText: String
    ) {
        val uri = getReportUri(context, file)
        val cleanRecipient = targetGmail?.trim()?.takeIf { it.isNotEmpty() }
        val subject = "JeevaSutra Cloud & PDF Backup - $patientName"

        val baseIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, summaryText)
            if (cleanRecipient != null) {
                putExtra(Intent.EXTRA_EMAIL, arrayOf(cleanRecipient))
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val gmailIntent = Intent(baseIntent).apply {
            setPackage("com.google.android.gm")
        }

        try {
            context.startActivity(gmailIntent)
        } catch (_: Exception) {
            val chooser = Intent.createChooser(baseIntent, "Upload JeevaSutra Backup to Gmail").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        }
    }

    fun viewReport(file: File) {
        viewPdfFile(context, file)
    }

    fun printReport(file: File) {
        printPdfFile(context, file)
    }

    companion object {
        fun getReportUri(context: Context, file: File): Uri {
            return FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        }

        fun sharePdfFile(
            context: Context,
            file: File,
            subject: String,
            message: String,
            chooserTitle: String = "Share PDF Report"
        ) {
            val uri = getReportUri(context, file)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, message)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooser = Intent.createChooser(shareIntent, chooserTitle).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        }

        fun viewPdfFile(context: Context, file: File) {
            val uri = getReportUri(context, file)
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val chooser = Intent.createChooser(viewIntent, "Open PDF Report").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        }

        fun printPdfFile(context: Context, file: File) {
            try {
                val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager
                if (printManager != null) {
                    val printAdapter = PdfPrintDocumentAdapter(file)
                    printManager.print("JeevaSutra_Medical_Report", printAdapter, PrintAttributes.Builder().build())
                } else {
                    viewPdfFile(context, file)
                }
            } catch (_: Exception) {
                viewPdfFile(context, file)
            }
        }

        fun copyFileToUri(context: Context, sourceFile: File, destinationUri: Uri) {
            context.contentResolver.openOutputStream(destinationUri)?.use { out ->
                sourceFile.inputStream().use { input ->
                    input.copyTo(out)
                }
            } ?: throw IllegalStateException("Unable to open destination file for writing.")
        }

        suspend fun generateFirestoreMetricsPdfReport(
            context: Context,
            firestoreHealthRepository: FirestoreHealthRepository?,
            userId: String,
            timeRange: DashboardTimeRange,
            fallbackProfile: PatientProfile? = null,
            fallbackVitals: List<VitalReadingEntity> = emptyList(),
            fallbackMedications: List<PrescriptionMedicationEntity> = emptyList(),
            fallbackIntakeLogs: List<MedicineIntakeLogEntity> = emptyList(),
            fallbackSymptoms: List<SymptomLogEntity> = emptyList(),
            fallbackRecords: List<MedicalRecord> = emptyList(),
            fallbackExtractedInfos: List<ExtractedMedicalInfo> = emptyList()
        ): HealthMetricsPdfReportResult = withContext(Dispatchers.IO) {
            val baseSnapshot = try {
                firestoreHealthRepository?.fetchFirestoreHealthSnapshot(userId)
            } catch (_: Exception) {
                null
            } ?: FirestoreHealthSnapshot(isFromCache = true)

            val mergedSnapshot = mergeWithLocalFallback(
                snapshot = baseSnapshot,
                userId = userId,
                fallbackVitals = fallbackVitals,
                fallbackMedications = fallbackMedications,
                fallbackIntakeLogs = fallbackIntakeLogs,
                fallbackSymptoms = fallbackSymptoms,
                fallbackRecords = fallbackRecords,
                fallbackExtractedInfos = fallbackExtractedInfos
            )

            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val reportsDir = File(context.cacheDir, "reports").apply { mkdirs() }
            val targetFile = File(reportsDir, "JeevaSutra_Doctor_Metrics_$timeStamp.pdf")

            HealthMetricsPdfGenerator(context).generatePdf(
                snapshot = mergedSnapshot,
                timeRange = timeRange,
                destinationFile = targetFile,
                fallbackProfile = fallbackProfile
            )
        }

        fun mergeWithLocalFallback(
            snapshot: FirestoreHealthSnapshot,
            userId: String,
            fallbackVitals: List<VitalReadingEntity> = emptyList(),
            fallbackMedications: List<PrescriptionMedicationEntity> = emptyList(),
            fallbackIntakeLogs: List<MedicineIntakeLogEntity> = emptyList(),
            fallbackSymptoms: List<SymptomLogEntity> = emptyList(),
            fallbackRecords: List<MedicalRecord> = emptyList(),
            fallbackExtractedInfos: List<ExtractedMedicalInfo> = emptyList()
        ): FirestoreHealthSnapshot {
            val vitalIds = snapshot.vitals.mapNotNull { doc ->
                if (doc.localVitalId > 0L) doc.localVitalId else doc.id.substringAfterLast('_').toLongOrNull()
            }.toMutableSet()
            val mergedVitals = snapshot.vitals.toMutableList()
            fallbackVitals.forEach { local ->
                if (local.id <= 0L || local.id !in vitalIds) {
                    if (local.id > 0L) vitalIds.add(local.id)
                    mergedVitals.add(
                        VitalReadingDoc(
                            id = "vital_${local.id}",
                            userId = userId,
                            localVitalId = local.id,
                            type = local.type,
                            systolic = local.systolic,
                            diastolic = local.diastolic,
                            weightKg = local.weightKg,
                            glucoseMgDl = local.glucoseMgDl,
                            timestamp = local.timestamp,
                            notes = local.notes
                        )
                    )
                }
            }

            val medIds = snapshot.medications.map { it.id }.toMutableSet()
            val mergedMeds = snapshot.medications.toMutableList()
            fallbackMedications.forEach { local ->
                if (local.id !in medIds) {
                    medIds.add(local.id)
                    mergedMeds.add(
                        MedicationDoc(
                            id = local.id,
                            userId = userId,
                            name = local.name,
                            dosage = local.dosage,
                            timeSlot = local.timeSlot,
                            isTaken = local.isTaken,
                            createdAtTimestamp = local.createdAtTimestamp
                        )
                    )
                }
            }

            val logIds = snapshot.medicineIntakeLogs.map { it.id }.toMutableSet()
            val mergedLogs = snapshot.medicineIntakeLogs.toMutableList()
            fallbackIntakeLogs.forEach { local ->
                if (local.id !in logIds) {
                    logIds.add(local.id)
                    mergedLogs.add(
                        MedicineIntakeLogDoc(
                            id = local.id,
                            userId = userId,
                            medicationId = local.medicationId,
                            medicationName = local.medicationName,
                            dosage = local.dosage,
                            timeSlot = local.timeSlot,
                            takenAtTimestamp = local.takenAtTimestamp,
                            status = local.status,
                            notes = local.notes
                        )
                    )
                }
            }

            val symptomIds = snapshot.symptoms.mapNotNull { doc ->
                if (doc.localSymptomId > 0L) doc.localSymptomId else doc.id.substringAfterLast('_').toLongOrNull()
            }.toMutableSet()
            val mergedSymptoms = snapshot.symptoms.toMutableList()
            fallbackSymptoms.forEach { local ->
                if (local.id <= 0L || local.id !in symptomIds) {
                    if (local.id > 0L) symptomIds.add(local.id)
                    mergedSymptoms.add(
                        SymptomLogDoc(
                            id = "symptom_${local.id}",
                            userId = userId,
                            localSymptomId = local.id,
                            symptomName = local.symptomName,
                            severity = local.severity,
                            severityScore = local.severityScore,
                            timestamp = local.timestamp,
                            notes = local.notes,
                            triggers = local.triggers,
                            durationMinutes = local.durationMinutes
                        )
                    )
                }
            }

            val recIds = snapshot.medicalRecords.mapNotNull { doc ->
                if (doc.localRecordId > 0L) doc.localRecordId else doc.id.substringAfterLast('_').toLongOrNull()
            }.toMutableSet()
            val extractedMap = fallbackExtractedInfos.associateBy { it.recordId }
            val mergedRecords = snapshot.medicalRecords.toMutableList()
            fallbackRecords.forEach { local ->
                if (local.id !in recIds) {
                    recIds.add(local.id)
                    val info = extractedMap[local.id]
                    mergedRecords.add(
                        MedicalRecordDoc(
                            id = "record_${local.id}",
                            userId = userId,
                            localRecordId = local.id,
                            title = local.title,
                            category = local.category,
                            recordDate = local.recordDate,
                            mimeType = local.mimeType,
                            createdAtTimestamp = local.createdAtTimestamp,
                            documentTitle = info?.documentTitle,
                            reportDate = info?.reportDate,
                            rawText = info?.rawText,
                            structuredResultsJson = info?.structuredResultsJson,
                            reviewStatus = info?.reviewStatus ?: "UNREVIEWED",
                            processedPages = info?.processedPages ?: 1,
                            updatedAtTimestamp = info?.updatedAtTimestamp ?: local.createdAtTimestamp
                        )
                    )
                }
            }

            return snapshot.copy(
                vitals = mergedVitals,
                medications = mergedMeds,
                medicineIntakeLogs = mergedLogs,
                symptoms = mergedSymptoms,
                medicalRecords = mergedRecords
            )
        }
    }
}
