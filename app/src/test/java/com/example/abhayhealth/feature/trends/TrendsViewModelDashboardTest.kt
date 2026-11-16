package com.example.abhayhealth.feature.trends

import com.example.abhayhealth.data.remote.FirestoreSyncState
import com.example.abhayhealth.data.remote.MedicalRecordDoc
import com.example.abhayhealth.data.remote.VitalReadingDoc
import com.example.abhayhealth.domain.model.ParsedMedicalResult
import com.example.abhayhealth.feature.records.ExtractedMedicalInfo
import com.example.abhayhealth.feature.records.MedicalFieldParser
import com.example.abhayhealth.feature.vitals.VitalReadingEntity
import com.example.abhayhealth.feature.vitals.VitalType
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.concurrent.TimeUnit

class TrendsViewModelDashboardTest {

    private val now = 1_750_000_000_000L

    @Test
    fun mergeVitals_prefersCloudDocsAndRetainsUnsyncedLocalEntries() {
        val cloud = listOf(
            VitalReadingDoc(
                id = "vital_1",
                userId = "u1",
                localVitalId = 1L,
                type = VitalType.BLOOD_PRESSURE.name,
                systolic = 118.0,
                diastolic = 76.0,
                timestamp = now - TimeUnit.DAYS.toMillis(2),
                notes = "Cloud BP"
            ),
            VitalReadingDoc(
                id = "vital_2",
                userId = "u1",
                localVitalId = 2L,
                type = VitalType.BLOOD_GLUCOSE.name,
                glucoseMgDl = 94.0,
                timestamp = now - TimeUnit.DAYS.toMillis(1),
                notes = "Cloud Glucose"
            )
        )
        val local = listOf(
            VitalReadingEntity(
                id = 1L,
                accountUid = "u1",
                type = VitalType.BLOOD_PRESSURE.name,
                systolic = 122.0,
                diastolic = 80.0,
                timestamp = now - TimeUnit.DAYS.toMillis(2),
                notes = "Stale Local BP"
            ),
            VitalReadingEntity(
                id = 3L,
                accountUid = "u1",
                type = VitalType.BLOOD_GLUCOSE.name,
                glucoseMgDl = 108.0,
                timestamp = now,
                notes = "Offline queued glucose"
            )
        )

        val merged = TrendsViewModel.mergeVitals(local, cloud, "u1")

        assertThat(merged).hasSize(3)
        val vital1 = merged.first { it.entity.id == 1L }
        assertThat(vital1.fromFirestore).isTrue()
        assertThat(vital1.entity.systolic).isEqualTo(118.0)

        val vital3 = merged.first { it.entity.id == 3L }
        assertThat(vital3.fromFirestore).isFalse()
        assertThat(vital3.entity.glucoseMgDl).isEqualTo(108.0)
    }

    @Test
    fun buildDashboardState_filtersByTimeRangeAndComputesBpAndGlucoseStats() {
        val day = TimeUnit.DAYS.toMillis(1)
        val vitals = listOf(
            VitalReadingEntity(
                id = 1L,
                accountUid = "u1",
                type = VitalType.BLOOD_PRESSURE.name,
                systolic = 134.0,
                diastolic = 86.0,
                timestamp = now - 45 * day
            ),
            VitalReadingEntity(
                id = 2L,
                accountUid = "u1",
                type = VitalType.BLOOD_PRESSURE.name,
                systolic = 122.0,
                diastolic = 78.0,
                timestamp = now - 5 * day
            ),
            VitalReadingEntity(
                id = 3L,
                accountUid = "u1",
                type = VitalType.BLOOD_PRESSURE.name,
                systolic = 116.0,
                diastolic = 74.0,
                timestamp = now - 1 * day
            ),
            VitalReadingEntity(
                id = 4L,
                accountUid = "u1",
                type = VitalType.BLOOD_GLUCOSE.name,
                glucoseMgDl = 148.0,
                timestamp = now - 20 * day
            ),
            VitalReadingEntity(
                id = 5L,
                accountUid = "u1",
                type = VitalType.BLOOD_GLUCOSE.name,
                glucoseMgDl = 92.0,
                timestamp = now - 2 * day
            )
        )

        val firestoreKeys = setOf("id_1", "id_2", "id_3", "id_4", "id_5")

        val sevenDayState = TrendsViewModel.buildDashboardState(
            vitals = vitals,
            extractedInfos = emptyList(),
            firestoreVitalKeys = firestoreKeys,
            selectedRange = DashboardTimeRange.DAYS_7,
            syncState = FirestoreSyncState(isFromCache = false, hasPendingWrites = false),
            referenceNowMillis = now
        )

        assertThat(sevenDayState.bloodPressureSeries).hasSize(2)
        assertThat(sevenDayState.glucoseSeries).hasSize(1)
        assertThat(sevenDayState.latestBpText).isEqualTo("116/74")
        assertThat(sevenDayState.bpStatus).isEqualTo(MetricClinicalStatus.OPTIMAL)
        assertThat(sevenDayState.bpAvgText).isEqualTo("119/76 mmHg")
        assertThat(sevenDayState.latestGlucoseText).isEqualTo("92")
        assertThat(sevenDayState.glucoseStatus).isEqualTo(MetricClinicalStatus.OPTIMAL)
        assertThat(sevenDayState.firestorePointsCount).isEqualTo(3)

        val allState = TrendsViewModel.buildDashboardState(
            vitals = vitals,
            extractedInfos = emptyList(),
            firestoreVitalKeys = firestoreKeys,
            selectedRange = DashboardTimeRange.ALL,
            syncState = FirestoreSyncState(isFromCache = false, hasPendingWrites = false),
            referenceNowMillis = now
        )
        assertThat(allState.bloodPressureSeries).hasSize(3)
        assertThat(allState.glucoseSeries).hasSize(2)
        assertThat(allState.firestorePointsCount).isEqualTo(5)
    }

    @Test
    fun buildDashboardState_mergesFirestoreLabGlucoseRecordsWithoutDuplicatingNearbyVitals() {
        val json = MedicalFieldParser.serializeResults(
            listOf(
                ParsedMedicalResult(
                    id = "r1",
                    testName = "Fasting Blood Glucose (FBS)",
                    value = "112",
                    unit = "mg/dL",
                    referenceRange = "70-99"
                )
            )
        )
        val cloudRecord = MedicalRecordDoc(
            id = "rec_10",
            userId = "u1",
            localRecordId = 10L,
            title = "Metabolic Panel",
            category = "LAB_REPORT",
            structuredResultsJson = json,
            updatedAtTimestamp = now - TimeUnit.DAYS.toMillis(3)
        )
        val mergedInfos = TrendsViewModel.mergeExtractedInfo(
            localInfos = emptyList(),
            cloudRecords = listOf(cloudRecord),
            uid = "u1"
        )

        val state = TrendsViewModel.buildDashboardState(
            vitals = emptyList(),
            extractedInfos = mergedInfos,
            firestoreVitalKeys = emptySet(),
            selectedRange = DashboardTimeRange.DAYS_30,
            syncState = FirestoreSyncState(),
            referenceNowMillis = now
        )

        assertThat(state.glucoseSeries).hasSize(1)
        assertThat(state.glucoseSeries.first().glucoseMgDl).isEqualTo(112.0)
        assertThat(state.glucoseStatus).isEqualTo(MetricClinicalStatus.ELEVATED)
    }
}
