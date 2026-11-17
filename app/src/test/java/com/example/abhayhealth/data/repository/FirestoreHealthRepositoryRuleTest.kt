package com.example.abhayhealth.data.repository

import com.example.abhayhealth.base.FirestoreEmulatorTestBase
import com.example.abhayhealth.data.remote.FirestoreHealthRepository
import com.example.abhayhealth.domain.model.PatientProfile
import com.example.abhayhealth.feature.medicines.MedicineIntakeLogEntity
import com.example.abhayhealth.feature.medicines.PrescriptionMedicationEntity
import com.example.abhayhealth.feature.records.ExtractedMedicalInfo
import com.example.abhayhealth.feature.records.MedicalRecord
import com.example.abhayhealth.feature.symptoms.SymptomLogEntity
import com.example.abhayhealth.feature.vitals.VitalReadingEntity
import com.example.abhayhealth.feature.vitals.VitalType
import com.google.common.truth.Truth.assertThat
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

class FirestoreHealthRepositoryRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun ensureUserAccount_and_upsertPatientProfile_ownerCanWriteAndObserveRealtime() = runBlocking {
        val freshEmail = "alice_phone_${UUID.randomUUID().toString().take(8)}@test.com"
        val aliceUid = signInTestUser(freshEmail)
        val repository = FirestoreHealthRepository(firestore)

        withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.ensureUserAccount(
                userId = aliceUid,
                email = null,
                displayName = "Alice Patient",
                phoneNumber = "+919876543210"
            )
            repository.upsertPatientProfile(
                PatientProfile(
                    accountUid = aliceUid,
                    fullName = "Alice Sharma",
                    dateOfBirth = "1992-05-14",
                    gender = "Female",
                    city = "Hyderabad",
                    state = "Telangana",
                    preferredLanguage = "English",
                    bloodGroup = "O+",
                    allergies = "Penicillin",
                    healthConditions = "Hypertension",
                    emergencyContactName = "Ravi Sharma",
                    emergencyContactPhone = "+919876543210",
                    additionalNotes = "Morning checkups preferred",
                    updatedAtTimestamp = 1700000000000L
                )
            )
        }

        val accountDoc = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeUserAccount(aliceUid).first { it != null }
        }
        val profileDoc = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observePatientProfile(aliceUid).first { it?.fullName == "Alice Sharma" }
        }

        assertThat(accountDoc?.userId).isEqualTo(aliceUid)
        assertThat(accountDoc?.phoneNumber).isEqualTo("+919876543210")
        assertThat(accountDoc?.email).isNull()
        assertThat(profileDoc?.bloodGroup).isEqualTo("O+")
        assertThat(profileDoc?.city).isEqualTo("Hyderabad")

        // Adding optional Gmail later preserves existing +91 phoneNumber
        withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.ensureUserAccount(
                userId = aliceUid,
                email = freshEmail,
                displayName = "Alice Patient",
                phoneNumber = null
            )
        }
        val linkedAccountDoc = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeUserAccount(aliceUid).first { it?.email == freshEmail }
        }
        assertThat(linkedAccountDoc?.phoneNumber).isEqualTo("+919876543210")
        assertThat(linkedAccountDoc?.email).isEqualTo(freshEmail)

        withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.upsertPatientProfile(
                PatientProfile(
                    accountUid = aliceUid,
                    fullName = "Alice Sharma Updated",
                    preferredLanguage = "Telugu",
                    updatedAtTimestamp = 1700000001000L
                )
            )
        }

        val updatedProfileDoc = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observePatientProfile(aliceUid).first { it?.fullName == "Alice Sharma Updated" }
        }
        assertThat(updatedProfileDoc?.preferredLanguage).isEqualTo("Telugu")
    }

    @Test
    fun medicalRecord_and_extractedInfo_ownerCanUpsertObserveAndDelete() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val repository = FirestoreHealthRepository(firestore)
        val recordId = 101L

        val record = MedicalRecord(
            id = recordId,
            accountUid = aliceUid,
            title = "Complete Blood Count",
            category = "LAB_REPORT",
            recordDate = 1700000000000L,
            mimeType = "application/pdf",
            createdAtTimestamp = 1700000000000L
        )
        val extracted = ExtractedMedicalInfo(
            recordId = recordId,
            accountUid = aliceUid,
            rawText = "Hemoglobin 13.8 g/dL",
            documentTitle = "CBC Lab Panel",
            reportDate = "2026-01-10",
            structuredResultsJson = "[{\"testName\":\"Hemoglobin\",\"value\":\"13.8\",\"unit\":\"g/dL\"}]",
            reviewStatus = ExtractedMedicalInfo.STATUS_REVIEWED_AND_SAVED,
            processedPages = 2,
            updatedAtTimestamp = 1700000005000L
        )

        withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.upsertMedicalRecord(record, extracted)
        }

        val records = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeMedicalRecords(aliceUid).first { list ->
                list.any { it.localRecordId == recordId }
            }
        }
        val synced = records.first { it.localRecordId == recordId }
        assertThat(synced.title).isEqualTo("Complete Blood Count")
        assertThat(synced.reviewStatus).isEqualTo(ExtractedMedicalInfo.STATUS_REVIEWED_AND_SAVED)
        assertThat(synced.processedPages).isEqualTo(2)

        withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.deleteMedicalRecord(aliceUid, recordId)
        }

        val afterDelete = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeMedicalRecords(aliceUid).first { list ->
                list.none { it.localRecordId == recordId }
            }
        }
        assertThat(afterDelete.none { it.localRecordId == recordId }).isTrue()
    }

    @Test
    fun medication_vital_and_symptom_ownerCanUpsertAndObserveRealtime() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val repository = FirestoreHealthRepository(firestore)
        val medId = "med_${UUID.randomUUID().toString().replace("-", "_")}"
        val vitalId = 202L
        val symptomId = 303L

        withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.upsertMedication(
                PrescriptionMedicationEntity(
                    id = medId,
                    accountUid = aliceUid,
                    name = "Metformin",
                    dosage = "500mg",
                    timeSlot = "MORNING (08:30)",
                    isTaken = true,
                    createdAtTimestamp = 1700000000000L
                )
            )
            repository.upsertVitalReading(
                VitalReadingEntity(
                    id = vitalId,
                    accountUid = aliceUid,
                    type = VitalType.BLOOD_PRESSURE.name,
                    systolic = 118.0,
                    diastolic = 76.0,
                    timestamp = 1700000000000L,
                    notes = "Resting morning reading"
                )
            )
            repository.upsertVitalReading(
                VitalReadingEntity(
                    id = 203L,
                    accountUid = aliceUid,
                    type = VitalType.BLOOD_GLUCOSE.name,
                    glucoseMgDl = 94.0,
                    timestamp = 1700000060000L,
                    notes = "Fasting glucose"
                )
            )
            repository.upsertSymptomLog(
                SymptomLogEntity(
                    id = symptomId,
                    accountUid = aliceUid,
                    symptomName = "Mild Headache",
                    severity = SymptomLogEntity.SEVERITY_MILD,
                    severityScore = 1,
                    timestamp = 1700000000000L,
                    notes = "Resolved after hydration",
                    triggers = "Screen glare",
                    durationMinutes = 30
                )
            )
        }

        val meds = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeMedications(aliceUid).first { list -> list.any { it.id == medId } }
        }
        val vitals = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeVitals(aliceUid).first { list ->
                list.any { it.localVitalId == vitalId } && list.any { it.localVitalId == 203L }
            }
        }
        val symptoms = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeSymptoms(aliceUid).first { list -> list.any { it.localSymptomId == symptomId } }
        }

        assertThat(meds.first { it.id == medId }.isTaken).isTrue()
        assertThat(vitals.first { it.localVitalId == vitalId }.systolic).isEqualTo(118.0)
        assertThat(vitals.first { it.localVitalId == 203L }.type).isEqualTo(VitalType.BLOOD_GLUCOSE.name)
        assertThat(vitals.first { it.localVitalId == 203L }.glucoseMgDl).isEqualTo(94.0)
        assertThat(symptoms.first { it.localSymptomId == symptomId }.symptomName).isEqualTo("Mild Headache")
    }

    @Test
    fun crossUserAccess_profileAndRecords_failsWithPermissionDenied() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val repository = FirestoreHealthRepository(firestore)

        withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.ensureUserAccount(aliceUid, ALICE_EMAIL, "Alice")
            repository.upsertPatientProfile(
                PatientProfile(
                    accountUid = aliceUid,
                    fullName = "Alice Private",
                    updatedAtTimestamp = 1700000000000L
                )
            )
        }

        signInTestUser(BOB_EMAIL)

        val readProfileException = assertThrowsException {
            withTimeout(FLOW_TIMEOUT_MS) {
                repository.observePatientProfile(aliceUid).first()
            }
        }
        val readFirestoreError = checkNotNull(readProfileException.unwrapFirestoreException()) {
            "Expected nested FirebaseFirestoreException on cross-user read, got $readProfileException"
        }
        assertThat(readFirestoreError.code).isEqualTo(FirebaseFirestoreException.Code.PERMISSION_DENIED)

        val writeRecordException = assertThrowsException {
            withTimeout(DEFAULT_TIMEOUT_MS) {
                repository.upsertMedicalRecord(
                    MedicalRecord(
                        id = 999L,
                        accountUid = aliceUid,
                        title = "Injected Record",
                        category = "OTHER",
                        recordDate = 1700000000000L
                    )
                )
            }
        }
        val writeFirestoreError = checkNotNull(writeRecordException.unwrapFirestoreException()) {
            "Expected nested FirebaseFirestoreException on cross-user write, got $writeRecordException"
        }
        assertThat(writeFirestoreError.code).isEqualTo(FirebaseFirestoreException.Code.PERMISSION_DENIED)
    }

    @Test
    fun unauthenticatedAccess_observeCollections_failsWithPermissionDenied() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        auth.signOut()
        val repository = FirestoreHealthRepository(firestore)

        val profileError = assertThrowsException {
            withTimeout(FLOW_TIMEOUT_MS) { repository.observePatientProfile(aliceUid).first() }
        }
        assertThat(profileError.unwrapFirestoreException()?.code)
            .isEqualTo(FirebaseFirestoreException.Code.PERMISSION_DENIED)

        val recordsError = assertThrowsException {
            withTimeout(FLOW_TIMEOUT_MS) { repository.observeMedicalRecords(aliceUid).first() }
        }
        assertThat(recordsError.unwrapFirestoreException()?.code)
            .isEqualTo(FirebaseFirestoreException.Code.PERMISSION_DENIED)

        val medsError = assertThrowsException {
            withTimeout(FLOW_TIMEOUT_MS) { repository.observeMedications(aliceUid).first() }
        }
        assertThat(medsError.unwrapFirestoreException()?.code)
            .isEqualTo(FirebaseFirestoreException.Code.PERMISSION_DENIED)

        val vitalsError = assertThrowsException {
            withTimeout(FLOW_TIMEOUT_MS) { repository.observeVitals(aliceUid).first() }
        }
        assertThat(vitalsError.unwrapFirestoreException()?.code)
            .isEqualTo(FirebaseFirestoreException.Code.PERMISSION_DENIED)

        val symptomsError = assertThrowsException {
            withTimeout(FLOW_TIMEOUT_MS) { repository.observeSymptoms(aliceUid).first() }
        }
        assertThat(symptomsError.unwrapFirestoreException()?.code)
            .isEqualTo(FirebaseFirestoreException.Code.PERMISSION_DENIED)
    }

    @Test
    fun deleteAllUserData_authenticatedOwner_clearsAllSubcollectionsAndUserDoc() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val repository = FirestoreHealthRepository(firestore)

        withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.ensureUserAccount(aliceUid, ALICE_EMAIL, "Alice Delete Test")
            repository.upsertPatientProfile(
                PatientProfile(accountUid = aliceUid, fullName = "Alice", updatedAtTimestamp = 1700000000000L)
            )
            repository.upsertMedicalRecord(
                MedicalRecord(id = 401L, accountUid = aliceUid, title = "Lab", category = "LAB_REPORT", recordDate = 1700000000000L)
            )
            repository.upsertMedication(
                PrescriptionMedicationEntity(
                    id = "med_delete_test",
                    accountUid = aliceUid,
                    name = "Atorvastatin",
                    dosage = "10mg",
                    timeSlot = "NIGHT (21:30)"
                )
            )
            repository.upsertVitalReading(
                VitalReadingEntity(id = 402L, accountUid = aliceUid, type = VitalType.WEIGHT.name, weightKg = 64.5)
            )
            repository.upsertSymptomLog(
                SymptomLogEntity(
                    id = 403L,
                    accountUid = aliceUid,
                    symptomName = "Fatigue",
                    severity = SymptomLogEntity.SEVERITY_MILD,
                    severityScore = 1
                )
            )
            repository.deleteAllUserData(aliceUid)
        }

        val profileAfter = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observePatientProfile(aliceUid).first { it == null }
        }
        val recordsAfter = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeMedicalRecords(aliceUid).first { it.isEmpty() }
        }
        val medsAfter = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeMedications(aliceUid).first { it.isEmpty() }
        }
        val vitalsAfter = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeVitals(aliceUid).first { it.isEmpty() }
        }
        val symptomsAfter = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeSymptoms(aliceUid).first { it.isEmpty() }
        }
        val intakeAfter = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeMedicineIntakeLogs(aliceUid).first { it.isEmpty() }
        }

        assertThat(profileAfter).isNull()
        assertThat(recordsAfter).isEmpty()
        assertThat(medsAfter).isEmpty()
        assertThat(vitalsAfter).isEmpty()
        assertThat(symptomsAfter).isEmpty()
        assertThat(intakeAfter).isEmpty()
    }

    @Test
    fun medicineIntakeLogs_ownerCanPersistObserveAndFetchHealthSnapshot_crossUserDenied() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val repository = FirestoreHealthRepository(firestore)
        val logId = "intake_${UUID.randomUUID().toString().replace("-", "_")}"

        val intakeLog = MedicineIntakeLogEntity(
            id = logId,
            accountUid = aliceUid,
            medicationId = "med_metformin_1",
            medicationName = "Metformin",
            dosage = "500mg",
            timeSlot = "MORNING (08:30)",
            takenAtTimestamp = 1750000000000L,
            status = MedicineIntakeLogEntity.STATUS_TAKEN,
            notes = "After breakfast"
        )

        withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.ensureUserAccount(aliceUid, ALICE_EMAIL, "Alice Snapshot", "+919876543210")
            repository.upsertVitalReading(
                VitalReadingEntity(
                    id = 801L,
                    accountUid = aliceUid,
                    type = VitalType.BLOOD_PRESSURE.name,
                    systolic = 118.0,
                    diastolic = 76.0,
                    timestamp = 1750000000000L
                )
            )
            repository.upsertMedicineIntakeLog(intakeLog)
        }

        val observedLogs = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeMedicineIntakeLogs(aliceUid).first { list -> list.any { it.id == logId } }
        }
        assertThat(observedLogs.first { it.id == logId }.medicationName).isEqualTo("Metformin")

        val snapshot = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.fetchFirestoreHealthSnapshot(aliceUid)
        }
        assertThat(snapshot.medicineIntakeLogs.any { it.id == logId }).isTrue()
        assertThat(snapshot.vitals.any { it.localVitalId == 801L }).isTrue()

        // Cross-user Bob denied
        signInTestUser(BOB_EMAIL)
        val crossReadError = assertThrowsException {
            withTimeout(FLOW_TIMEOUT_MS) { repository.observeMedicineIntakeLogs(aliceUid).first() }
        }
        assertThat(crossReadError.unwrapFirestoreException()?.code)
            .isEqualTo(FirebaseFirestoreException.Code.PERMISSION_DENIED)

        val crossWriteError = assertThrowsException {
            withTimeout(DEFAULT_TIMEOUT_MS) { repository.upsertMedicineIntakeLog(intakeLog) }
        }
        assertThat(crossWriteError.unwrapFirestoreException()?.code)
            .isEqualTo(FirebaseFirestoreException.Code.PERMISSION_DENIED)

        // Cleanup as owner Alice
        signInTestUser(ALICE_EMAIL)
        withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.deleteMedicineIntakeLog(aliceUid, logId)
        }
        val afterDelete = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeMedicineIntakeLogs(aliceUid).first { list -> list.none { it.id == logId } }
        }
        assertThat(afterDelete.none { it.id == logId }).isTrue()
    }

    private suspend fun assertThrowsException(block: suspend () -> Unit): Exception {
        try {
            block()
        } catch (e: Exception) {
            return e
        }
        fail("Expected an exception to be thrown, but operation succeeded")
        error("Unreachable")
    }

    private companion object {
        const val ALICE_EMAIL = "alice@test.com"
        const val BOB_EMAIL = "bob@test.com"
        const val DEFAULT_TIMEOUT_MS = 5000L
        const val FLOW_TIMEOUT_MS = 3000L
    }
}
