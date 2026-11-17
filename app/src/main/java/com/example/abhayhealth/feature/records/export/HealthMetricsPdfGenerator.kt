package com.example.abhayhealth.feature.records.export

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.graphics.toColorInt
import com.example.abhayhealth.data.remote.FirestoreHealthSnapshot
import com.example.abhayhealth.data.remote.MedicationDoc
import com.example.abhayhealth.data.remote.MedicineIntakeLogDoc
import com.example.abhayhealth.data.remote.PatientProfileDoc
import com.example.abhayhealth.data.remote.SymptomLogDoc
import com.example.abhayhealth.data.remote.VitalReadingDoc
import com.example.abhayhealth.domain.model.PatientProfile
import com.example.abhayhealth.feature.medicines.MedicineIntakeLogEntity
import com.example.abhayhealth.feature.records.MedicalFieldParser
import com.example.abhayhealth.feature.trends.DashboardTimeRange
import com.example.abhayhealth.feature.vitals.VitalType
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Counts previewed inside [ShareHealthMetricsPdfDialog] before & after PDF generation.
 */
data class HealthMetricsPdfPreviewCounts(
    val bpCount: Int = 0,
    val glucoseCount: Int = 0,
    val weightCount: Int = 0,
    val takenMedicineCount: Int = 0,
    val symptomCount: Int = 0,
    val activeMedicationCount: Int = 0
)

/**
 * Result returned after generating the Firestore-backed Doctor Progress PDF summary.
 */
data class HealthMetricsPdfReportResult(
    val file: File,
    val counts: HealthMetricsPdfPreviewCounts,
    val isFromCache: Boolean
)

internal interface PdfWriterAdapter {
    fun startPage(width: Int, height: Int, pageNumber: Int): Canvas
    fun finishCurrentPage()
    fun writeTo(outputStream: OutputStream)
    fun close()
}

private class AndroidPdfDocumentWriter : PdfWriterAdapter {
    private val document = PdfDocument()
    private var currentPage: PdfDocument.Page? = null

    override fun startPage(width: Int, height: Int, pageNumber: Int): Canvas {
        val pageInfo = PdfDocument.PageInfo.Builder(width, height, pageNumber).create()
        val page = document.startPage(pageInfo)
        currentPage = page
        return page.canvas
    }

    override fun finishCurrentPage() {
        currentPage?.let { document.finishPage(it) }
        currentPage = null
    }

    override fun writeTo(outputStream: OutputStream) {
        document.writeTo(outputStream)
    }

    override fun close() {
        document.close()
    }
}

/**
 * Generates an A4 multi-page clinical progress summary PDF directly from a
 * [FirestoreHealthSnapshot] (synced via Cloud Firestore + offline SQLite cache).
 * Includes patient identity, KPI executive cards, embedded vector Blood Pressure & Blood Glucose
 * time-series line charts, stored Medicine Intake History (`medicine_intake_logs`), active
 * prescriptions, detailed metric logs, symptom history, and a physician notes block.
 */
class HealthMetricsPdfGenerator internal constructor(
    private val context: Context,
    private val pdfWriterFactory: () -> PdfWriterAdapter
) {
    constructor(context: Context) : this(context, { AndroidPdfDocumentWriter() })

    private data class FilteredMetrics(
        val bpReadings: List<BpEntry>,
        val glucoseReadings: List<GlucoseEntry>,
        val weightReadings: List<WeightEntry>,
        val intakeLogs: List<MedicineIntakeLogDoc>,
        val medications: List<MedicationDoc>,
        val symptoms: List<SymptomLogDoc>,
        val labRecordSummaries: List<LabSummaryEntry>
    )

    private data class BpEntry(
        val timestamp: Long,
        val systolic: Double,
        val diastolic: Double,
        val notes: String
    )

    private data class GlucoseEntry(
        val timestamp: Long,
        val glucoseMgDl: Double,
        val notes: String
    )

    private data class WeightEntry(
        val timestamp: Long,
        val weightKg: Double,
        val notes: String
    )

    private data class LabSummaryEntry(
        val title: String,
        val category: String,
        val dateText: String,
        val markerCount: Int,
        val abnormalCount: Int
    )

    companion object {
        private const val PAGE_WIDTH = 595
        private const val PAGE_HEIGHT = 842
        private const val MARGIN_LEFT = 36f
        private const val MARGIN_RIGHT = 559f
        private const val MARGIN_TOP = 36f
        private const val MARGIN_BOTTOM = 798f
        private const val CONTENT_WIDTH = MARGIN_RIGHT - MARGIN_LEFT

        private const val COLOR_PRIMARY = 0xFF0D5C75.toInt()
        private const val COLOR_SECONDARY = 0xFF0F766E.toInt()
        private const val COLOR_TERTIARY = 0xFFD97706.toInt()
        private const val COLOR_BG_LIGHT = 0xFFF8FAFC.toInt()
        private const val COLOR_CARD_BORDER = 0xFFE2E8F0.toInt()
        private const val COLOR_TEXT_PRIMARY = 0xFF0F172A.toInt()
        private const val COLOR_TEXT_SECONDARY = 0xFF475569.toInt()
        private const val COLOR_TEXT_MUTED = 0xFF64748B.toInt()
        private const val COLOR_ACCENT_RED = 0xFFDC2626.toInt()
        private const val COLOR_ACCENT_GREEN = 0xFF16A34A.toInt()
        private const val COLOR_ROW_ALT = 0xFFF1F5F9.toInt()

        fun computePreviewCounts(
            snapshot: FirestoreHealthSnapshot,
            timeRange: DashboardTimeRange,
            referenceNowMillis: Long = System.currentTimeMillis()
        ): HealthMetricsPdfPreviewCounts {
            val filtered = extractFilteredMetrics(snapshot, timeRange, referenceNowMillis)
            return HealthMetricsPdfPreviewCounts(
                bpCount = filtered.bpReadings.size,
                glucoseCount = filtered.glucoseReadings.size,
                weightCount = filtered.weightReadings.size,
                takenMedicineCount = filtered.intakeLogs.count {
                    it.status.equals(MedicineIntakeLogEntity.STATUS_TAKEN, ignoreCase = true)
                },
                symptomCount = filtered.symptoms.size,
                activeMedicationCount = filtered.medications.size
            )
        }

        private fun extractFilteredMetrics(
            snapshot: FirestoreHealthSnapshot,
            timeRange: DashboardTimeRange,
            referenceNowMillis: Long
        ): FilteredMetrics {
            val cutoffMillis = timeRange.days?.let { days ->
                referenceNowMillis - TimeUnit.DAYS.toMillis(days.toLong())
            } ?: Long.MIN_VALUE

            val bp = mutableListOf<BpEntry>()
            val glucose = mutableListOf<GlucoseEntry>()
            val weight = mutableListOf<WeightEntry>()

            snapshot.vitals.sortedBy { it.timestamp }.forEach { vital ->
                if (vital.timestamp < cutoffMillis) return@forEach
                when (vital.type) {
                    VitalType.BLOOD_PRESSURE.name -> {
                        val s = vital.systolic
                        val d = vital.diastolic
                        if (s != null && d != null) {
                            bp.add(BpEntry(vital.timestamp, s, d, vital.notes))
                        }
                    }
                    VitalType.BLOOD_GLUCOSE.name -> {
                        val g = vital.glucoseMgDl
                        if (g != null) {
                            glucose.add(GlucoseEntry(vital.timestamp, g, vital.notes))
                        }
                    }
                    VitalType.WEIGHT.name -> {
                        val w = vital.weightKg
                        if (w != null) {
                            weight.add(WeightEntry(vital.timestamp, w, vital.notes))
                        }
                    }
                }
            }

            // Include glucose & BP values extracted from archived lab records within the timeframe
            val shortFmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
            val labSummaries = mutableListOf<LabSummaryEntry>()
            snapshot.medicalRecords.forEach { rec ->
                val json = rec.structuredResultsJson
                val recTs = rec.reportDate?.let { MedicalFieldParser.parseDateToTimestamp(it) }
                    ?: if (rec.recordDate > 0L) rec.recordDate else rec.updatedAtTimestamp
                if (recTs < cutoffMillis) return@forEach

                val results = if (!json.isNullOrBlank() && json != "[]") {
                    MedicalFieldParser.deserializeResults(json)
                } else emptyList()

                labSummaries.add(
                    LabSummaryEntry(
                        title = rec.documentTitle?.takeIf { it.isNotBlank() } ?: rec.title,
                        category = rec.category.ifBlank { "RECORD" },
                        dateText = rec.reportDate?.takeIf { it.isNotBlank() } ?: shortFmt.format(Date(recTs)),
                        markerCount = results.size,
                        abnormalCount = results.count { it.isOutOfRange }
                    )
                )

                results.forEach { marker ->
                    val lower = marker.testName.lowercase()
                    val num = Regex("""(\d+(?:\.\d+)?)""").find(marker.value)?.groupValues?.get(1)?.toDoubleOrNull()
                    if ((lower.contains("glucose") || lower.contains("blood sugar") ||
                            lower.contains("fbs") || lower.contains("ppbs") || lower.contains("rbs")) &&
                        num != null && num in 20.0..800.0
                    ) {
                        val dup = glucose.any {
                            abs(it.timestamp - recTs) < TimeUnit.MINUTES.toMillis(5) &&
                                abs(it.glucoseMgDl - num) < 0.5
                        }
                        if (!dup) {
                            glucose.add(GlucoseEntry(recTs, num, "${marker.testName} (Lab)"))
                        }
                    } else if (lower.contains("blood pressure") || lower == "bp") {
                        val parts = marker.value.split(Regex("""[/,\s]+""")).mapNotNull { it.toDoubleOrNull() }
                        if (parts.size >= 2) {
                            val dup = bp.any {
                                abs(it.timestamp - recTs) < TimeUnit.MINUTES.toMillis(5) &&
                                    abs(it.systolic - parts[0]) < 0.5
                            }
                            if (!dup) {
                                bp.add(BpEntry(recTs, parts[0], parts[1], "Lab Record"))
                            }
                        }
                    }
                }
            }

            val intakeFiltered = snapshot.medicineIntakeLogs
                .filter { it.takenAtTimestamp >= cutoffMillis }
                .sortedByDescending { it.takenAtTimestamp }

            val symptomsFiltered = snapshot.symptoms
                .filter { it.timestamp >= cutoffMillis }
                .sortedByDescending { it.timestamp }

            return FilteredMetrics(
                bpReadings = bp.sortedBy { it.timestamp },
                glucoseReadings = glucose.sortedBy { it.timestamp },
                weightReadings = weight.sortedBy { it.timestamp },
                intakeLogs = intakeFiltered,
                medications = snapshot.medications,
                symptoms = symptomsFiltered,
                labRecordSummaries = labSummaries.take(8)
            )
        }
    }

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 14.5f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#E0F2FE".toColorInt()
        textSize = 8.5f
    }

    private val sectionHeaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_PRIMARY
        textSize = 10.5f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val bodyBoldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_TEXT_PRIMARY
        textSize = 8.8f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val bodyRegularPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_TEXT_PRIMARY
        textSize = 8.3f
    }

    private val bodySecondaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_TEXT_SECONDARY
        textSize = 7.8f
    }

    private val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_TEXT_MUTED
        textSize = 7.3f
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_CARD_BORDER
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    fun generatePdf(
        snapshot: FirestoreHealthSnapshot,
        timeRange: DashboardTimeRange,
        destinationFile: File,
        fallbackProfile: PatientProfile? = null,
        referenceNowMillis: Long = System.currentTimeMillis()
    ): HealthMetricsPdfReportResult {
        val filtered = extractFilteredMetrics(snapshot, timeRange, referenceNowMillis)
        val counts = HealthMetricsPdfPreviewCounts(
            bpCount = filtered.bpReadings.size,
            glucoseCount = filtered.glucoseReadings.size,
            weightCount = filtered.weightReadings.size,
            takenMedicineCount = filtered.intakeLogs.count {
                it.status.equals(MedicineIntakeLogEntity.STATUS_TAKEN, ignoreCase = true)
            },
            symptomCount = filtered.symptoms.size,
            activeMedicationCount = filtered.medications.size
        )

        val writer = pdfWriterFactory()
        var pageNumber = 1
        var canvas = writer.startPage(PAGE_WIDTH, PAGE_HEIGHT, pageNumber)

        val dateTimeFmt = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
        val shortDateFmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
        val chartDateFmt = SimpleDateFormat("d MMM", Locale.getDefault())
        val generatedStr = dateTimeFmt.format(Date(snapshot.fetchedAtMillis))
        val scopeLabel = when (timeRange) {
            DashboardTimeRange.DAYS_7 -> "Last 7 Days"
            DashboardTimeRange.DAYS_30 -> "Last 30 Days"
            DashboardTimeRange.DAYS_90 -> "Last 90 Days"
            DashboardTimeRange.ALL -> "All Recorded History"
        }

        var yCursor = drawHeader(canvas, generatedStr, scopeLabel, snapshot.isFromCache, isFirstPage = true)

        fun ensureSpace(neededHeight: Float) {
            if (yCursor + neededHeight > MARGIN_BOTTOM) {
                drawFooter(canvas, pageNumber, snapshot.isFromCache)
                writer.finishCurrentPage()
                pageNumber++
                canvas = writer.startPage(PAGE_WIDTH, PAGE_HEIGHT, pageNumber)
                yCursor = drawHeader(canvas, generatedStr, scopeLabel, snapshot.isFromCache, isFirstPage = false)
            }
        }

        // 1. Patient Profile & Contact Identity
        ensureSpace(98f)
        yCursor = drawPatientProfileCard(canvas, yCursor, snapshot.patientProfile, fallbackProfile, snapshot)

        // 2. Executive Clinical KPI Cards (BP, Glucose, Weight, Medicines Taken)
        ensureSpace(68f)
        yCursor = drawKpiRow(canvas, yCursor, filtered)

        // 3. Embedded Vector Line Graphs (BP & Blood Glucose over time)
        if (filtered.bpReadings.size >= 2) {
            ensureSpace(148f)
            yCursor = drawBpTrendChart(canvas, yCursor, filtered.bpReadings, chartDateFmt)
        }
        if (filtered.glucoseReadings.size >= 2) {
            ensureSpace(148f)
            yCursor = drawGlucoseTrendChart(canvas, yCursor, filtered.glucoseReadings, chartDateFmt)
        }

        // 4. Stored Medicine Intake History (taken/skipped doses synced with Firestore)
        ensureSpace(50f)
        yCursor = drawSectionTitle(
            canvas,
            yCursor,
            "MEDICINE INTAKE HISTORY (STORED PROGRESS LOGS)",
            "${filtered.intakeLogs.size} Logs"
        )
        if (filtered.intakeLogs.isEmpty()) {
            ensureSpace(32f)
            yCursor = drawEmptyNotice(canvas, yCursor, "No medicine doses logged in this period yet.")
        } else {
            ensureSpace(22f)
            yCursor = drawIntakeHistoryHeader(canvas, yCursor)
            filtered.intakeLogs.take(25).forEachIndexed { idx, log ->
                ensureSpace(22f)
                yCursor = drawIntakeHistoryRow(canvas, yCursor, log, dateTimeFmt, idx % 2 == 1)
            }
            yCursor += 10f
        }

        // 5. Active Prescriptions Schedule
        ensureSpace(48f)
        yCursor = drawSectionTitle(
            canvas,
            yCursor,
            "CURRENT PRESCRIPTION MEDICATIONS",
            "${filtered.medications.size} Active"
        )
        if (filtered.medications.isEmpty()) {
            ensureSpace(30f)
            yCursor = drawEmptyNotice(canvas, yCursor, "No active prescription medications synced to Firestore.")
        } else {
            ensureSpace(22f)
            yCursor = drawMedicationScheduleHeader(canvas, yCursor)
            filtered.medications.forEachIndexed { idx, med ->
                ensureSpace(22f)
                yCursor = drawMedicationScheduleRow(canvas, yCursor, med, idx % 2 == 1)
            }
            yCursor += 10f
        }

        // 6. Blood Pressure, Glucose & Weight Readings Log Table
        val combinedReadingsCount = filtered.bpReadings.size + filtered.glucoseReadings.size + filtered.weightReadings.size
        ensureSpace(48f)
        yCursor = drawSectionTitle(
            canvas,
            yCursor,
            "BLOOD PRESSURE, GLUCOSE & WEIGHT READINGS",
            "$combinedReadingsCount Readings"
        )
        if (combinedReadingsCount == 0) {
            ensureSpace(30f)
            yCursor = drawEmptyNotice(canvas, yCursor, "No Blood Pressure, Glucose, or Weight measurements recorded in this period.")
        } else {
            ensureSpace(22f)
            yCursor = drawVitalsHeader(canvas, yCursor)
            val rows = buildCombinedMetricRows(filtered)
            rows.take(24).forEachIndexed { idx, row ->
                ensureSpace(22f)
                yCursor = drawVitalRow(canvas, yCursor, row, dateTimeFmt, idx % 2 == 1)
            }
            yCursor += 10f
        }

        // 7. Symptom Logs (if any)
        if (filtered.symptoms.isNotEmpty()) {
            ensureSpace(48f)
            yCursor = drawSectionTitle(canvas, yCursor, "REPORTED SYMPTOM JOURNAL", "${filtered.symptoms.size} Logs")
            ensureSpace(22f)
            yCursor = drawSymptomsHeader(canvas, yCursor)
            filtered.symptoms.take(12).forEachIndexed { idx, sym ->
                ensureSpace(22f)
                yCursor = drawSymptomRow(canvas, yCursor, sym, shortDateFmt, idx % 2 == 1)
            }
            yCursor += 10f
        }

        // 8. Medical Records / Lab Panels Summary (if any)
        if (filtered.labRecordSummaries.isNotEmpty()) {
            ensureSpace(48f)
            yCursor = drawSectionTitle(
                canvas,
                yCursor,
                "ARCHIVED DIAGNOSTIC RECORDS SUMMARY",
                "${filtered.labRecordSummaries.size} Docs"
            )
            filtered.labRecordSummaries.forEach { rec ->
                ensureSpace(24f)
                yCursor = drawLabRecordLine(canvas, yCursor, rec)
            }
            yCursor += 8f
        }

        // 9. Clinician Review & Consultation Notes Block
        ensureSpace(98f)
        drawClinicianNotesArea(canvas, yCursor)

        drawFooter(canvas, pageNumber, snapshot.isFromCache)
        writer.finishCurrentPage()

        destinationFile.parentFile?.mkdirs()
        FileOutputStream(destinationFile).use { out ->
            writer.writeTo(out)
        }
        writer.close()

        return HealthMetricsPdfReportResult(
            file = destinationFile,
            counts = counts,
            isFromCache = snapshot.isFromCache
        )
    }

    private fun drawHeader(
        canvas: Canvas,
        generatedDateStr: String,
        scopeLabel: String,
        isFromCache: Boolean,
        isFirstPage: Boolean
    ): Float {
        val headerHeight = if (isFirstPage) 74f else 38f
        val rect = RectF(MARGIN_LEFT, MARGIN_TOP, MARGIN_RIGHT, MARGIN_TOP + headerHeight)
        fillPaint.color = COLOR_PRIMARY
        canvas.drawRoundRect(rect, 8f, 8f, fillPaint)

        if (isFirstPage) {
            val emblemCx = MARGIN_LEFT + 28f
            val emblemCy = MARGIN_TOP + 37f
            fillPaint.color = Color.WHITE
            canvas.drawCircle(emblemCx, emblemCy, 18f, fillPaint)
            fillPaint.color = COLOR_PRIMARY
            canvas.drawRect(emblemCx - 3.5f, emblemCy - 11f, emblemCx + 3.5f, emblemCy + 11f, fillPaint)
            canvas.drawRect(emblemCx - 11f, emblemCy - 3.5f, emblemCx + 11f, emblemCy + 3.5f, fillPaint)

            canvas.drawText("ABHAY HEALTH", MARGIN_LEFT + 56f, MARGIN_TOP + 27f, titlePaint.apply { textSize = 14.5f })
            canvas.drawText("Doctor Progress & Health Metrics Summary", MARGIN_LEFT + 56f, MARGIN_TOP + 43f, subtitlePaint)
            val syncLabel = if (isFromCache) "Cloud Firestore Offline Cache" else "Cloud Firestore Live Sync"
            canvas.drawText("Period: $scopeLabel • $syncLabel", MARGIN_LEFT + 56f, MARGIN_TOP + 57f, subtitlePaint)

            val badgeRect = RectF(MARGIN_RIGHT - 160f, MARGIN_TOP + 12f, MARGIN_RIGHT - 12f, MARGIN_TOP + 32f)
            fillPaint.color = "#1E293B".toColorInt()
            canvas.drawRoundRect(badgeRect, 4f, 4f, fillPaint)
            val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = "#FDE047".toColorInt()
                textSize = 7.3f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            canvas.drawText("DOCTOR VISIT PROGRESS PDF", MARGIN_RIGHT - 151f, MARGIN_TOP + 25f, badgePaint)

            val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = "#E2E8F0".toColorInt()
                textSize = 7.3f
            }
            canvas.drawText("Generated: $generatedDateStr", MARGIN_RIGHT - 160f, MARGIN_TOP + 54f, datePaint)

            return MARGIN_TOP + headerHeight + 12f
        } else {
            canvas.drawText(
                "ABHAY HEALTH — DOCTOR HEALTH METRICS SUMMARY (Continued)",
                MARGIN_LEFT + 14f,
                MARGIN_TOP + 24f,
                titlePaint.apply { textSize = 9.5f }
            )
            val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 7.5f
            }
            canvas.drawText(generatedDateStr, MARGIN_RIGHT - 115f, MARGIN_TOP + 24f, datePaint)
            return MARGIN_TOP + headerHeight + 10f
        }
    }

    private fun drawPatientProfileCard(
        canvas: Canvas,
        yStart: Float,
        cloudProfile: PatientProfileDoc?,
        fallbackProfile: PatientProfile?,
        snapshot: FirestoreHealthSnapshot
    ): Float {
        val cardHeight = 84f
        val rect = RectF(MARGIN_LEFT, yStart, MARGIN_RIGHT, yStart + cardHeight)
        fillPaint.color = COLOR_BG_LIGHT
        canvas.drawRoundRect(rect, 8f, 8f, fillPaint)
        borderPaint.color = COLOR_CARD_BORDER
        canvas.drawRoundRect(rect, 8f, 8f, borderPaint)

        fillPaint.color = COLOR_SECONDARY
        canvas.drawRoundRect(RectF(MARGIN_LEFT, yStart, MARGIN_LEFT + 4f, yStart + cardHeight), 4f, 4f, fillPaint)

        val fullName = cloudProfile?.fullName?.ifBlank { null }
            ?: fallbackProfile?.fullName?.ifBlank { null }
            ?: snapshot.userAccount?.displayName?.ifBlank { null }
            ?: "AbhayHealth Patient"
        val dob = cloudProfile?.dateOfBirth?.ifBlank { null } ?: fallbackProfile?.dateOfBirth?.ifBlank { null } ?: "Not specified"
        val gender = cloudProfile?.gender?.ifBlank { null } ?: fallbackProfile?.gender?.ifBlank { null } ?: "Not specified"
        val bloodGroup = cloudProfile?.bloodGroup?.ifBlank { null } ?: fallbackProfile?.bloodGroup?.ifBlank { null } ?: "Unknown"
        val allergies = cloudProfile?.allergies?.ifBlank { null } ?: fallbackProfile?.allergies?.ifBlank { null } ?: "None documented"
        val conditions = cloudProfile?.healthConditions?.ifBlank { null } ?: fallbackProfile?.healthConditions?.ifBlank { null } ?: "None reported"
        val contact = listOfNotNull(
            snapshot.userAccount?.phoneNumber?.ifBlank { null },
            snapshot.userAccount?.email?.ifBlank { null }
        ).joinToString(" • ").ifBlank { "Verified Account" }

        val col1 = MARGIN_LEFT + 14f
        val col2 = MARGIN_LEFT + 185f
        val col3 = MARGIN_LEFT + 355f

        canvas.drawText("PATIENT NAME", col1, yStart + 17f, bodySecondaryPaint)
        canvas.drawText(fullName.take(28), col1, yStart + 30f, bodyBoldPaint.apply { textSize = 10f })

        canvas.drawText("DOB / GENDER", col2, yStart + 17f, bodySecondaryPaint)
        canvas.drawText("$dob • $gender".take(28), col2, yStart + 30f, bodyBoldPaint.apply { textSize = 8.8f })

        canvas.drawText("BLOOD GROUP", col3, yStart + 17f, bodySecondaryPaint)
        val bgRect = RectF(col3, yStart + 20f, col3 + 58f, yStart + 34f)
        fillPaint.color = if (bloodGroup != "Unknown") "#FEE2E2".toColorInt() else "#E2E8F0".toColorInt()
        canvas.drawRoundRect(bgRect, 4f, 4f, fillPaint)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (bloodGroup != "Unknown") COLOR_ACCENT_RED else COLOR_TEXT_MUTED
            textSize = 8f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText(bloodGroup.take(8), col3 + 8f, yStart + 30f, bgPaint)

        canvas.drawLine(col1, yStart + 41f, MARGIN_RIGHT - 14f, yStart + 41f, borderPaint)

        canvas.drawText("ALLERGIES", col1, yStart + 55f, bodySecondaryPaint)
        canvas.drawText(allergies.take(28), col1, yStart + 68f, bodyRegularPaint)

        canvas.drawText("HEALTH CONDITIONS", col2, yStart + 55f, bodySecondaryPaint)
        canvas.drawText(conditions.take(28), col2, yStart + 68f, bodyRegularPaint)

        canvas.drawText("PATIENT CONTACT", col3, yStart + 55f, bodySecondaryPaint)
        canvas.drawText(contact.take(30), col3, yStart + 68f, bodyRegularPaint)

        return yStart + cardHeight + 12f
    }

    private fun drawKpiRow(
        canvas: Canvas,
        yStart: Float,
        filtered: FilteredMetrics
    ): Float {
        val boxH = 54f
        val gap = 8f
        val cardW = (CONTENT_WIDTH - gap * 3f) / 4f

        val latestBp = filtered.bpReadings.lastOrNull()
        val bpVal = latestBp?.let { "${it.systolic.roundToInt()}/${it.diastolic.roundToInt()}" } ?: "--/--"
        val bpSub = if (filtered.bpReadings.isNotEmpty()) {
            "Avg ${filtered.bpReadings.map { it.systolic }.average().roundToInt()}/${filtered.bpReadings.map { it.diastolic }.average().roundToInt()} mmHg"
        } else "No BP logs"

        val latestGlu = filtered.glucoseReadings.lastOrNull()
        val gluVal = latestGlu?.let { "${it.glucoseMgDl.roundToInt()} mg/dL" } ?: "--"
        val gluSub = if (filtered.glucoseReadings.isNotEmpty()) {
            "Avg ${filtered.glucoseReadings.map { it.glucoseMgDl }.average().roundToInt()} (${filtered.glucoseReadings.size} pts)"
        } else "No Glucose logs"

        val latestWt = filtered.weightReadings.lastOrNull()
        val wtVal = latestWt?.let { String.format(Locale.US, "%.1f kg", it.weightKg) } ?: "--"
        val wtSub = if (filtered.weightReadings.isNotEmpty()) {
            "Avg ${String.format(Locale.US, "%.1f kg", filtered.weightReadings.map { it.weightKg }.average())}"
        } else "No Weight logs"

        val takenCount = filtered.intakeLogs.count {
            it.status.equals(MedicineIntakeLogEntity.STATUS_TAKEN, ignoreCase = true)
        }
        val medSub = "${filtered.medications.size} active Rx"

        data class Kpi(val title: String, val value: String, val sub: String, val accent: Int)
        val cards = listOf(
            Kpi("BLOOD PRESSURE", bpVal, bpSub, COLOR_PRIMARY),
            Kpi("BLOOD GLUCOSE", gluVal, gluSub, COLOR_TERTIARY),
            Kpi("BODY WEIGHT", wtVal, wtSub, COLOR_SECONDARY),
            Kpi("MEDICINES TAKEN", "$takenCount Doses", medSub, COLOR_ACCENT_GREEN)
        )

        cards.forEachIndexed { idx, kpi ->
            val left = MARGIN_LEFT + idx * (cardW + gap)
            val rect = RectF(left, yStart, left + cardW, yStart + boxH)
            fillPaint.color = COLOR_BG_LIGHT
            canvas.drawRoundRect(rect, 6f, 6f, fillPaint)
            canvas.drawRoundRect(rect, 6f, 6f, borderPaint)

            fillPaint.color = kpi.accent
            canvas.drawRoundRect(RectF(left, yStart, left + 3.5f, yStart + boxH), 2f, 2f, fillPaint)

            canvas.drawText(kpi.title, left + 9f, yStart + 14f, footerPaint)
            val valPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = kpi.accent
                textSize = 11.5f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            canvas.drawText(kpi.value.take(14), left + 9f, yStart + 32f, valPaint)
            canvas.drawText(kpi.sub.take(20), left + 9f, yStart + 45f, footerPaint)
        }

        return yStart + boxH + 12f
    }

    private fun drawBpTrendChart(
        canvas: Canvas,
        yStart: Float,
        points: List<BpEntry>,
        dateFmt: SimpleDateFormat
    ): Float {
        val chartBoxH = 136f
        val rect = RectF(MARGIN_LEFT, yStart, MARGIN_RIGHT, yStart + chartBoxH)
        fillPaint.color = Color.WHITE
        canvas.drawRoundRect(rect, 6f, 6f, fillPaint)
        canvas.drawRoundRect(rect, 6f, 6f, borderPaint)

        canvas.drawText("BLOOD PRESSURE TREND OVER TIME (mmHg)", MARGIN_LEFT + 12f, yStart + 15f, bodyBoldPaint)
        canvas.drawText("— Systolic   --- Diastolic   • Target <120/80 mmHg", MARGIN_RIGHT - 215f, yStart + 15f, footerPaint)

        val padL = MARGIN_LEFT + 34f
        val padR = MARGIN_RIGHT - 16f
        val padT = yStart + 26f
        val padB = yStart + chartBoxH - 20f
        val plotW = padR - padL
        val plotH = padB - padT

        val allVals = points.flatMap { listOf(it.systolic, it.diastolic) }
        val minV = ((allVals.minOrNull() ?: 60.0) - 10.0).coerceAtLeast(40.0)
        val maxV = ((allVals.maxOrNull() ?: 140.0) + 12.0).coerceAtLeast(135.0)
        val span = (maxV - minV).coerceAtLeast(10.0)

        fun yFor(v: Double) = padT + plotH * (1f - ((v - minV) / span).toFloat())
        fun xFor(i: Int) = if (points.size <= 1) padL + plotW / 2f else padL + (i.toFloat() / (points.size - 1)) * plotW

        drawPdfChartGrid(canvas, padL, padR, padT, plotH, minV, maxV)

        val sysPath = Path()
        val diaPath = Path()
        val sysStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_PRIMARY
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        val diaStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TERTIARY
            style = Paint.Style.STROKE
            strokeWidth = 1.6f
            pathEffect = DashPathEffect(floatArrayOf(5f, 3f), 0f)
        }

        points.forEachIndexed { i, pt ->
            val x = xFor(i)
            val yS = yFor(pt.systolic)
            val yD = yFor(pt.diastolic)
            if (i == 0) {
                sysPath.moveTo(x, yS)
                diaPath.moveTo(x, yD)
            } else {
                sysPath.lineTo(x, yS)
                diaPath.lineTo(x, yD)
            }
        }
        canvas.drawPath(sysPath, sysStroke)
        canvas.drawPath(diaPath, diaStroke)

        points.forEachIndexed { i, pt ->
            val x = xFor(i)
            fillPaint.color = COLOR_PRIMARY
            canvas.drawCircle(x, yFor(pt.systolic), 2.5f, fillPaint)
            fillPaint.color = COLOR_TERTIARY
            canvas.drawCircle(x, yFor(pt.diastolic), 2.2f, fillPaint)
        }

        drawPdfXLabels(canvas, points.map { dateFmt.format(Date(it.timestamp)) }, ::xFor, yStart + chartBoxH - 7f)
        return yStart + chartBoxH + 10f
    }

    private fun drawGlucoseTrendChart(
        canvas: Canvas,
        yStart: Float,
        points: List<GlucoseEntry>,
        dateFmt: SimpleDateFormat
    ): Float {
        val chartBoxH = 136f
        val rect = RectF(MARGIN_LEFT, yStart, MARGIN_RIGHT, yStart + chartBoxH)
        fillPaint.color = Color.WHITE
        canvas.drawRoundRect(rect, 6f, 6f, fillPaint)
        canvas.drawRoundRect(rect, 6f, 6f, borderPaint)

        canvas.drawText("BLOOD GLUCOSE TREND OVER TIME (mg/dL)", MARGIN_LEFT + 12f, yStart + 15f, bodyBoldPaint)
        canvas.drawText("Shaded Band: Normal Fasting 70–99 mg/dL", MARGIN_RIGHT - 185f, yStart + 15f, footerPaint)

        val padL = MARGIN_LEFT + 34f
        val padR = MARGIN_RIGHT - 16f
        val padT = yStart + 26f
        val padB = yStart + chartBoxH - 20f
        val plotW = padR - padL
        val plotH = padB - padT

        val vals = points.map { it.glucoseMgDl }
        val minV = ((vals.minOrNull() ?: 70.0) - 12.0).coerceAtLeast(40.0)
        val maxV = ((vals.maxOrNull() ?: 140.0) + 16.0).coerceAtLeast(155.0)
        val span = (maxV - minV).coerceAtLeast(20.0)

        fun yFor(v: Double) = padT + plotH * (1f - ((v - minV) / span).toFloat())
        fun xFor(i: Int) = if (points.size <= 1) padL + plotW / 2f else padL + (i.toFloat() / (points.size - 1)) * plotW

        // Target fasting band (70-99)
        val topY = yFor(99.0.coerceIn(minV, maxV))
        val botY = yFor(70.0.coerceIn(minV, maxV))
        if (botY > topY) {
            fillPaint.color = "#CCFBF1".toColorInt()
            canvas.drawRect(padL, topY, padR, botY, fillPaint)
        }

        drawPdfChartGrid(canvas, padL, padR, padT, plotH, minV, maxV)

        val gluPath = Path()
        val gluStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_SECONDARY
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        points.forEachIndexed { i, pt ->
            val x = xFor(i)
            val y = yFor(pt.glucoseMgDl)
            if (i == 0) gluPath.moveTo(x, y) else gluPath.lineTo(x, y)
        }
        canvas.drawPath(gluPath, gluStroke)

        points.forEachIndexed { i, pt ->
            fillPaint.color = COLOR_SECONDARY
            canvas.drawCircle(xFor(i), yFor(pt.glucoseMgDl), 2.5f, fillPaint)
        }

        drawPdfXLabels(canvas, points.map { dateFmt.format(Date(it.timestamp)) }, ::xFor, yStart + chartBoxH - 7f)
        return yStart + chartBoxH + 10f
    }

    private fun drawPdfChartGrid(
        canvas: Canvas,
        padL: Float,
        padR: Float,
        padT: Float,
        plotH: Float,
        minV: Double,
        maxV: Double
    ) {
        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_CARD_BORDER
            style = Paint.Style.STROKE
            strokeWidth = 0.8f
            pathEffect = DashPathEffect(floatArrayOf(4f, 4f), 0f)
        }
        val yLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TEXT_MUTED
            textSize = 7f
            textAlign = Paint.Align.RIGHT
        }
        val span = maxV - minV
        for (i in 0..3) {
            val y = padT + (plotH / 3f) * i
            val v = maxV - (span / 3.0) * i
            canvas.drawLine(padL, y, padR, y, gridPaint)
            canvas.drawText("${v.roundToInt()}", padL - 5f, y + 2.5f, yLabelPaint)
        }
    }

    private fun drawPdfXLabels(
        canvas: Canvas,
        labels: List<String>,
        xFor: (Int) -> Float,
        yBaseline: Float
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TEXT_MUTED
            textSize = 7f
            textAlign = Paint.Align.CENTER
        }
        labels.forEachIndexed { idx, label ->
            val show = labels.size <= 5 || idx == 0 || idx == labels.lastIndex || idx == labels.size / 2
            if (show) {
                canvas.drawText(label, xFor(idx), yBaseline, paint)
            }
        }
    }

    private fun drawSectionTitle(canvas: Canvas, yStart: Float, title: String, rightBadge: String): Float {
        fillPaint.color = COLOR_PRIMARY
        canvas.drawRect(MARGIN_LEFT, yStart + 2f, MARGIN_LEFT + 3f, yStart + 14f, fillPaint)
        canvas.drawText(title, MARGIN_LEFT + 8f, yStart + 12f, sectionHeaderPaint)
        val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TEXT_MUTED
            textSize = 7.8f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText(rightBadge, MARGIN_RIGHT - badgePaint.measureText(rightBadge), yStart + 12f, badgePaint)
        canvas.drawLine(MARGIN_LEFT, yStart + 17f, MARGIN_RIGHT, yStart + 17f, borderPaint)
        return yStart + 23f
    }

    private fun drawEmptyNotice(canvas: Canvas, yStart: Float, msg: String): Float {
        val rect = RectF(MARGIN_LEFT, yStart, MARGIN_RIGHT, yStart + 22f)
        fillPaint.color = COLOR_BG_LIGHT
        canvas.drawRoundRect(rect, 4f, 4f, fillPaint)
        canvas.drawRoundRect(rect, 4f, 4f, borderPaint)
        canvas.drawText(msg, MARGIN_LEFT + 10f, yStart + 14f, bodySecondaryPaint)
        return yStart + 28f
    }

    private fun drawIntakeHistoryHeader(canvas: Canvas, yStart: Float): Float {
        val rect = RectF(MARGIN_LEFT, yStart, MARGIN_RIGHT, yStart + 17f)
        fillPaint.color = "#E2E8F0".toColorInt()
        canvas.drawRoundRect(rect, 4f, 4f, fillPaint)
        val hp = tableHeaderPaint()
        canvas.drawText("DATE & TIME", MARGIN_LEFT + 6f, yStart + 11.5f, hp)
        canvas.drawText("MEDICINE", MARGIN_LEFT + 122f, yStart + 11.5f, hp)
        canvas.drawText("DOSAGE", MARGIN_LEFT + 258f, yStart + 11.5f, hp)
        canvas.drawText("SLOT", MARGIN_LEFT + 345f, yStart + 11.5f, hp)
        canvas.drawText("STATUS", MARGIN_LEFT + 448f, yStart + 11.5f, hp)
        return yStart + 20f
    }

    private fun drawIntakeHistoryRow(
        canvas: Canvas,
        yStart: Float,
        log: MedicineIntakeLogDoc,
        dateFmt: SimpleDateFormat,
        isAlt: Boolean
    ): Float {
        val h = 20f
        fillPaint.color = if (isAlt) COLOR_ROW_ALT else Color.WHITE
        canvas.drawRect(MARGIN_LEFT, yStart, MARGIN_RIGHT, yStart + h, fillPaint)
        canvas.drawLine(MARGIN_LEFT, yStart + h, MARGIN_RIGHT, yStart + h, borderPaint)

        canvas.drawText(dateFmt.format(Date(log.takenAtTimestamp)).take(20), MARGIN_LEFT + 6f, yStart + 13f, bodySecondaryPaint)
        canvas.drawText(log.medicationName.take(22), MARGIN_LEFT + 122f, yStart + 13f, bodyBoldPaint)
        canvas.drawText(log.dosage.ifBlank { "-" }.take(15), MARGIN_LEFT + 258f, yStart + 13f, bodyRegularPaint)
        canvas.drawText(log.timeSlot.ifBlank { "-" }.take(17), MARGIN_LEFT + 345f, yStart + 13f, bodySecondaryPaint)

        val isTaken = log.status.equals(MedicineIntakeLogEntity.STATUS_TAKEN, ignoreCase = true)
        val statusBg = if (isTaken) "#DCFCE7".toColorInt() else "#FEF3C7".toColorInt()
        val statusFg = if (isTaken) COLOR_ACCENT_GREEN else COLOR_TERTIARY
        val pill = RectF(MARGIN_LEFT + 448f, yStart + 3f, MARGIN_RIGHT - 6f, yStart + 16f)
        fillPaint.color = statusBg
        canvas.drawRoundRect(pill, 3f, 3f, fillPaint)
        val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = statusFg
            textSize = 6.8f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText(log.status.uppercase().take(10), MARGIN_LEFT + 455f, yStart + 12f, sp)
        return yStart + h
    }

    private fun drawMedicationScheduleHeader(canvas: Canvas, yStart: Float): Float {
        val rect = RectF(MARGIN_LEFT, yStart, MARGIN_RIGHT, yStart + 17f)
        fillPaint.color = "#E2E8F0".toColorInt()
        canvas.drawRoundRect(rect, 4f, 4f, fillPaint)
        val hp = tableHeaderPaint()
        canvas.drawText("MEDICINE", MARGIN_LEFT + 6f, yStart + 11.5f, hp)
        canvas.drawText("DOSAGE", MARGIN_LEFT + 190f, yStart + 11.5f, hp)
        canvas.drawText("SCHEDULE SLOT", MARGIN_LEFT + 325f, yStart + 11.5f, hp)
        canvas.drawText("TODAY", MARGIN_LEFT + 455f, yStart + 11.5f, hp)
        return yStart + 20f
    }

    private fun drawMedicationScheduleRow(
        canvas: Canvas,
        yStart: Float,
        med: MedicationDoc,
        isAlt: Boolean
    ): Float {
        val h = 20f
        fillPaint.color = if (isAlt) COLOR_ROW_ALT else Color.WHITE
        canvas.drawRect(MARGIN_LEFT, yStart, MARGIN_RIGHT, yStart + h, fillPaint)
        canvas.drawLine(MARGIN_LEFT, yStart + h, MARGIN_RIGHT, yStart + h, borderPaint)

        canvas.drawText(med.name.take(30), MARGIN_LEFT + 6f, yStart + 13f, bodyBoldPaint)
        canvas.drawText(med.dosage.ifBlank { "As prescribed" }.take(22), MARGIN_LEFT + 190f, yStart + 13f, bodyRegularPaint)
        canvas.drawText(med.timeSlot.take(20), MARGIN_LEFT + 325f, yStart + 13f, bodySecondaryPaint)
        canvas.drawText(if (med.isTaken) "Taken" else "Scheduled", MARGIN_LEFT + 455f, yStart + 13f, bodyRegularPaint)
        return yStart + h
    }

    private data class MetricRow(
        val timestamp: Long,
        val metric: String,
        val valueText: String,
        val clinicalEval: String
    )

    private fun buildCombinedMetricRows(filtered: FilteredMetrics): List<MetricRow> {
        val rows = mutableListOf<MetricRow>()
        filtered.bpReadings.forEach { bp ->
            val eval = when {
                bp.systolic < 120.0 && bp.diastolic < 80.0 -> "Normal (<120/<80)"
                bp.systolic < 130.0 && bp.diastolic < 80.0 -> "Elevated (120–129)"
                else -> "High (≥130/≥80)"
            }
            rows.add(
                MetricRow(
                    timestamp = bp.timestamp,
                    metric = "Blood Pressure",
                    valueText = "${bp.systolic.roundToInt()}/${bp.diastolic.roundToInt()} mmHg",
                    clinicalEval = bp.notes.ifBlank { eval }
                )
            )
        }
        filtered.glucoseReadings.forEach { glu ->
            val eval = when {
                glu.glucoseMgDl < 100.0 -> "Optimal (<100)"
                glu.glucoseMgDl < 140.0 -> "Borderline (100–139)"
                else -> "High (≥140)"
            }
            rows.add(
                MetricRow(
                    timestamp = glu.timestamp,
                    metric = "Blood Glucose",
                    valueText = "${glu.glucoseMgDl.roundToInt()} mg/dL",
                    clinicalEval = glu.notes.ifBlank { eval }
                )
            )
        }
        filtered.weightReadings.forEach { wt ->
            rows.add(
                MetricRow(
                    timestamp = wt.timestamp,
                    metric = "Body Weight",
                    valueText = String.format(Locale.US, "%.1f kg", wt.weightKg),
                    clinicalEval = wt.notes.ifBlank { "Tracked weight" }
                )
            )
        }
        return rows.sortedByDescending { it.timestamp }
    }

    private fun drawVitalsHeader(canvas: Canvas, yStart: Float): Float {
        val rect = RectF(MARGIN_LEFT, yStart, MARGIN_RIGHT, yStart + 17f)
        fillPaint.color = "#E2E8F0".toColorInt()
        canvas.drawRoundRect(rect, 4f, 4f, fillPaint)
        val hp = tableHeaderPaint()
        canvas.drawText("RECORDED DATE", MARGIN_LEFT + 6f, yStart + 11.5f, hp)
        canvas.drawText("METRIC", MARGIN_LEFT + 130f, yStart + 11.5f, hp)
        canvas.drawText("VALUE", MARGIN_LEFT + 245f, yStart + 11.5f, hp)
        canvas.drawText("EVALUATION / NOTES", MARGIN_LEFT + 355f, yStart + 11.5f, hp)
        return yStart + 20f
    }

    private fun drawVitalRow(
        canvas: Canvas,
        yStart: Float,
        row: MetricRow,
        dateFmt: SimpleDateFormat,
        isAlt: Boolean
    ): Float {
        val h = 20f
        fillPaint.color = if (isAlt) COLOR_ROW_ALT else Color.WHITE
        canvas.drawRect(MARGIN_LEFT, yStart, MARGIN_RIGHT, yStart + h, fillPaint)
        canvas.drawLine(MARGIN_LEFT, yStart + h, MARGIN_RIGHT, yStart + h, borderPaint)

        canvas.drawText(dateFmt.format(Date(row.timestamp)).take(20), MARGIN_LEFT + 6f, yStart + 13f, bodySecondaryPaint)
        canvas.drawText(row.metric, MARGIN_LEFT + 130f, yStart + 13f, bodyBoldPaint)
        canvas.drawText(row.valueText, MARGIN_LEFT + 245f, yStart + 13f, bodyBoldPaint)
        canvas.drawText(row.clinicalEval.take(32), MARGIN_LEFT + 355f, yStart + 13f, bodySecondaryPaint)
        return yStart + h
    }

    private fun drawSymptomsHeader(canvas: Canvas, yStart: Float): Float {
        val rect = RectF(MARGIN_LEFT, yStart, MARGIN_RIGHT, yStart + 17f)
        fillPaint.color = "#E2E8F0".toColorInt()
        canvas.drawRoundRect(rect, 4f, 4f, fillPaint)
        val hp = tableHeaderPaint()
        canvas.drawText("DATE", MARGIN_LEFT + 6f, yStart + 11.5f, hp)
        canvas.drawText("SYMPTOM", MARGIN_LEFT + 95f, yStart + 11.5f, hp)
        canvas.drawText("SEVERITY", MARGIN_LEFT + 255f, yStart + 11.5f, hp)
        canvas.drawText("NOTES & TRIGGERS", MARGIN_LEFT + 345f, yStart + 11.5f, hp)
        return yStart + 20f
    }

    private fun drawSymptomRow(
        canvas: Canvas,
        yStart: Float,
        sym: SymptomLogDoc,
        shortDateFmt: SimpleDateFormat,
        isAlt: Boolean
    ): Float {
        val h = 20f
        fillPaint.color = if (isAlt) COLOR_ROW_ALT else Color.WHITE
        canvas.drawRect(MARGIN_LEFT, yStart, MARGIN_RIGHT, yStart + h, fillPaint)
        canvas.drawLine(MARGIN_LEFT, yStart + h, MARGIN_RIGHT, yStart + h, borderPaint)

        canvas.drawText(shortDateFmt.format(Date(sym.timestamp)), MARGIN_LEFT + 6f, yStart + 13f, bodySecondaryPaint)
        canvas.drawText(sym.symptomName.take(26), MARGIN_LEFT + 95f, yStart + 13f, bodyBoldPaint)
        canvas.drawText("${sym.severity} (${sym.severityScore}/5)", MARGIN_LEFT + 255f, yStart + 13f, bodyRegularPaint)
        val detail = listOfNotNull(
            sym.triggers.takeIf { it.isNotBlank() },
            sym.notes.takeIf { it.isNotBlank() }
        ).joinToString(" • ").ifBlank { "-" }
        canvas.drawText(detail.take(32), MARGIN_LEFT + 345f, yStart + 13f, bodySecondaryPaint)
        return yStart + h
    }

    private fun drawLabRecordLine(canvas: Canvas, yStart: Float, rec: LabSummaryEntry): Float {
        val rect = RectF(MARGIN_LEFT, yStart, MARGIN_RIGHT, yStart + 20f)
        fillPaint.color = COLOR_BG_LIGHT
        canvas.drawRoundRect(rect, 4f, 4f, fillPaint)
        canvas.drawRoundRect(rect, 4f, 4f, borderPaint)

        canvas.drawText("[${rec.category.take(10)}] ${rec.title.take(38)}", MARGIN_LEFT + 8f, yStart + 13f, bodyBoldPaint)
        val rightInfo = "${rec.dateText} • ${rec.markerCount} markers" +
            if (rec.abnormalCount > 0) " (${rec.abnormalCount} flagged)" else ""
        canvas.drawText(rightInfo, MARGIN_RIGHT - bodySecondaryPaint.measureText(rightInfo) - 8f, yStart + 13f, bodySecondaryPaint)
        return yStart + 24f
    }

    private fun drawClinicianNotesArea(canvas: Canvas, yStart: Float): Float {
        val h = 84f
        val rect = RectF(MARGIN_LEFT, yStart, MARGIN_RIGHT, yStart + h)
        fillPaint.color = COLOR_BG_LIGHT
        canvas.drawRoundRect(rect, 6f, 6f, fillPaint)
        canvas.drawRoundRect(rect, 6f, 6f, borderPaint)

        canvas.drawText("PHYSICIAN REVIEW & FOLLOW-UP NOTES", MARGIN_LEFT + 12f, yStart + 16f, sectionHeaderPaint.apply { textSize = 9f })
        canvas.drawLine(MARGIN_LEFT + 12f, yStart + 38f, MARGIN_RIGHT - 12f, yStart + 38f, borderPaint)
        canvas.drawLine(MARGIN_LEFT + 12f, yStart + 56f, MARGIN_RIGHT - 12f, yStart + 56f, borderPaint)
        canvas.drawText("Clinician Signature / Stamp:", MARGIN_LEFT + 12f, yStart + 74f, bodySecondaryPaint)
        canvas.drawText("Review Date: ____ / ____ / ________", MARGIN_RIGHT - 155f, yStart + 74f, bodySecondaryPaint)
        return yStart + h + 8f
    }

    private fun drawFooter(canvas: Canvas, pageNumber: Int, isFromCache: Boolean) {
        canvas.drawLine(MARGIN_LEFT, MARGIN_BOTTOM + 6f, MARGIN_RIGHT, MARGIN_BOTTOM + 6f, borderPaint)
        val sourceNote = if (isFromCache) "Offline Cached Firestore Snapshot" else "Cloud Firestore Synchronized Summary"
        canvas.drawText(
            "AbhayHealth Confidential Doctor Summary • $sourceNote",
            MARGIN_LEFT,
            MARGIN_BOTTOM + 20f,
            footerPaint
        )
        val pageLabel = "Page $pageNumber"
        canvas.drawText(pageLabel, MARGIN_RIGHT - footerPaint.measureText(pageLabel), MARGIN_BOTTOM + 20f, footerPaint)
    }

    private fun tableHeaderPaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_TEXT_PRIMARY
        textSize = 7.3f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
}
