package com.example.abhayhealth.feature.records.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import com.example.abhayhealth.data.remote.FirestoreHealthSnapshot
import com.example.abhayhealth.data.remote.MedicalRecordDoc
import com.example.abhayhealth.data.remote.MedicationDoc
import com.example.abhayhealth.data.remote.MedicineIntakeLogDoc
import com.example.abhayhealth.data.remote.PatientProfileDoc
import com.example.abhayhealth.data.remote.SymptomLogDoc
import com.example.abhayhealth.data.remote.UserAccountDoc
import com.example.abhayhealth.data.remote.VitalReadingDoc
import com.example.abhayhealth.domain.model.ParsedMedicalResult
import com.example.abhayhealth.feature.medicines.MedicineIntakeLogEntity
import com.example.abhayhealth.feature.records.MedicalFieldParser
import com.example.abhayhealth.feature.trends.DashboardTimeRange
import com.example.abhayhealth.feature.vitals.VitalType
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.OutputStream
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class HealthMetricsPdfGeneratorTest {

    private val now = 1_750_000_000_000L

    private class CapturingPdfWriter : PdfWriterAdapter {
        val pagesStarted = mutableListOf<Int>()
        var pagesFinished = 0
        var closed = false
        val drawnTexts = mutableListOf<String>()
        private val bitmaps = mutableListOf<Bitmap>()

        override fun startPage(width: Int, height: Int, pageNumber: Int): Canvas {
            pagesStarted.add(pageNumber)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmaps.add(bitmap)
            return object : Canvas(bitmap) {
                override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
                    drawnTexts.add(text)
                    super.drawText(text, x, y, paint)
                }
            }
        }

        override fun finishCurrentPage() {
            pagesFinished++
        }

        override fun writeTo(outputStream: OutputStream) {
            val payload = buildString {
                append("%PDF-1.4\n")
                append("% Pages=${pagesFinished}\n")
                drawnTexts.forEach { append("% $it\n") }
                append("%%EOF\n")
            }
            outputStream.write(payload.toByteArray(Charsets.UTF_8))
        }

        override fun close() {
            closed = true
            bitmaps.forEach { it.recycle() }
            bitmaps.clear()
        }
    }

    @Test
    fun generatePdf_fromPopulatedFirestoreSnapshot_createsValidPdfWithExpectedCounts() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val day = TimeUnit.DAYS.toMillis(1)

        val labJson = MedicalFieldParser.serializeResults(
            listOf(
                ParsedMedicalResult(
                    id = "lab_1",
                    testName = "Fasting Blood Glucose",
                    value = "108",
                    unit = "mg/dL",
                    referenceRange = "70-99"
                )
            )
        )

        val snapshot = FirestoreHealthSnapshot(
            userAccount = UserAccountDoc(
                id = "u1",
                userId = "u1",
                phoneNumber = "+919876543210",
                email = "patient@gmail.com",
                displayName = "Ravi Kumar"
            ),
            patientProfile = PatientProfileDoc(
                id = "profile",
                userId = "u1",
                fullName = "Ravi Kumar",
                dateOfBirth = "15/06/1980",
                gender = "Male",
                bloodGroup = "B+",
                allergies = "Sulfa drugs",
                healthConditions = "Hypertension, Type 2 Diabetes"
            ),
            vitals = listOf(
                VitalReadingDoc(
                    id = "v1",
                    userId = "u1",
                    localVitalId = 1L,
                    type = VitalType.BLOOD_PRESSURE.name,
                    systolic = 132.0,
                    diastolic = 84.0,
                    timestamp = now - 20 * day,
                    notes = "Morning"
                ),
                VitalReadingDoc(
                    id = "v2",
                    userId = "u1",
                    localVitalId = 2L,
                    type = VitalType.BLOOD_PRESSURE.name,
                    systolic = 122.0,
                    diastolic = 78.0,
                    timestamp = now - 3 * day,
                    notes = "Resting"
                ),
                VitalReadingDoc(
                    id = "v3",
                    userId = "u1",
                    localVitalId = 3L,
                    type = VitalType.BLOOD_PRESSURE.name,
                    systolic = 118.0,
                    diastolic = 76.0,
                    timestamp = now - 1 * day,
                    notes = "After medication"
                ),
                VitalReadingDoc(
                    id = "v4",
                    userId = "u1",
                    localVitalId = 4L,
                    type = VitalType.BLOOD_GLUCOSE.name,
                    glucoseMgDl = 128.0,
                    timestamp = now - 4 * day,
                    notes = "Fasting"
                ),
                VitalReadingDoc(
                    id = "v5",
                    userId = "u1",
                    localVitalId = 5L,
                    type = VitalType.BLOOD_GLUCOSE.name,
                    glucoseMgDl = 96.0,
                    timestamp = now - 1 * day,
                    notes = "Fasting"
                ),
                VitalReadingDoc(
                    id = "v6",
                    userId = "u1",
                    localVitalId = 6L,
                    type = VitalType.WEIGHT.name,
                    weightKg = 74.2,
                    timestamp = now - 2 * day
                )
            ),
            medications = listOf(
                MedicationDoc(
                    id = "med_1",
                    userId = "u1",
                    name = "Metformin",
                    dosage = "500 mg",
                    timeSlot = "MORNING (08:30)",
                    isTaken = true
                ),
                MedicationDoc(
                    id = "med_2",
                    userId = "u1",
                    name = "Amlodipine",
                    dosage = "5 mg",
                    timeSlot = "NIGHT (21:30)",
                    isTaken = false
                )
            ),
            medicineIntakeLogs = listOf(
                MedicineIntakeLogDoc(
                    id = "log_1",
                    userId = "u1",
                    medicationId = "med_1",
                    medicationName = "Metformin",
                    dosage = "500 mg",
                    timeSlot = "MORNING (08:30)",
                    takenAtTimestamp = now - 2 * day,
                    status = MedicineIntakeLogEntity.STATUS_TAKEN,
                    notes = "Taken with breakfast"
                ),
                MedicineIntakeLogDoc(
                    id = "log_2",
                    userId = "u1",
                    medicationId = "med_2",
                    medicationName = "Amlodipine",
                    dosage = "5 mg",
                    timeSlot = "NIGHT (21:30)",
                    takenAtTimestamp = now - 15 * day,
                    status = MedicineIntakeLogEntity.STATUS_TAKEN
                )
            ),
            symptoms = listOf(
                SymptomLogDoc(
                    id = "sym_1",
                    userId = "u1",
                    localSymptomId = 1L,
                    symptomName = "Mild Fatigue",
                    severity = "MILD",
                    severityScore = 2,
                    timestamp = now - 2 * day,
                    triggers = "Late evening"
                )
            ),
            medicalRecords = listOf(
                MedicalRecordDoc(
                    id = "rec_1",
                    userId = "u1",
                    localRecordId = 1L,
                    title = "HbA1c & Metabolic Panel",
                    category = "LAB_REPORT",
                    recordDate = now - 5 * day,
                    structuredResultsJson = labJson,
                    updatedAtTimestamp = now - 5 * day
                )
            ),
            isFromCache = false,
            fetchedAtMillis = now
        )

        val outAll = File(context.cacheDir, "test_metrics_all.pdf")
        outAll.delete()

        val writer = CapturingPdfWriter()
        val resultAll = HealthMetricsPdfGenerator(context) { writer }.generatePdf(
            snapshot = snapshot,
            timeRange = DashboardTimeRange.ALL,
            destinationFile = outAll,
            referenceNowMillis = now
        )

        assertThat(writer.closed).isTrue()
        assertThat(writer.pagesStarted).isNotEmpty()
        assertThat(writer.pagesFinished).isEqualTo(writer.pagesStarted.size)

        val joinedText = writer.drawnTexts.joinToString("\n")
        assertThat(joinedText).contains("ABHAY HEALTH")
        assertThat(joinedText).contains("Ravi Kumar")
        assertThat(joinedText).contains("BLOOD PRESSURE TREND OVER TIME")
        assertThat(joinedText).contains("BLOOD GLUCOSE TREND OVER TIME")
        assertThat(joinedText).contains("MEDICINE INTAKE HISTORY")
        assertThat(joinedText).contains("Metformin")
        assertThat(joinedText).contains("Amlodipine")
        assertThat(joinedText).contains("118/76")
        assertThat(joinedText).contains("PHYSICIAN REVIEW & FOLLOW-UP NOTES")

        assertThat(outAll.exists()).isTrue()
        assertThat(outAll.length()).isGreaterThan(1500L)
        val header = outAll.inputStream().use { stream ->
            ByteArray(5).also { stream.read(it) }.decodeToString()
        }
        assertThat(header).isEqualTo("%PDF-")

        assertThat(resultAll.counts.bpCount).isEqualTo(3)
        assertThat(resultAll.counts.glucoseCount).isEqualTo(3) // 2 vitals + 1 lab-extracted glucose
        assertThat(resultAll.counts.weightCount).isEqualTo(1)
        assertThat(resultAll.counts.takenMedicineCount).isEqualTo(2)
        assertThat(resultAll.counts.symptomCount).isEqualTo(1)
        assertThat(resultAll.counts.activeMedicationCount).isEqualTo(2)

        // Verify 7-day range filters older readings cleanly
        val counts7D = HealthMetricsPdfGenerator.computePreviewCounts(
            snapshot = snapshot,
            timeRange = DashboardTimeRange.DAYS_7,
            referenceNowMillis = now
        )
        assertThat(counts7D.bpCount).isEqualTo(2)
        assertThat(counts7D.takenMedicineCount).isEqualTo(1)
        outAll.delete()
    }

    @Test
    fun generatePdf_emptySnapshot_generatesValidPdfGracefully() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val outEmpty = File(context.cacheDir, "test_metrics_empty.pdf")
        outEmpty.delete()

        val writer = CapturingPdfWriter()
        val result = HealthMetricsPdfGenerator(context) { writer }.generatePdf(
            snapshot = FirestoreHealthSnapshot(isFromCache = true, fetchedAtMillis = now),
            timeRange = DashboardTimeRange.DAYS_30,
            destinationFile = outEmpty,
            referenceNowMillis = now
        )

        assertThat(writer.closed).isTrue()
        assertThat(writer.pagesFinished).isEqualTo(writer.pagesStarted.size)
        assertThat(writer.drawnTexts.joinToString("\n")).contains("Offline Cached Firestore Snapshot")
        assertThat(outEmpty.exists()).isTrue()
        assertThat(outEmpty.length()).isGreaterThan(800L)
        assertThat(result.isFromCache).isTrue()
        assertThat(result.counts.bpCount).isEqualTo(0)
        assertThat(result.counts.takenMedicineCount).isEqualTo(0)
        outEmpty.delete()
    }
}
