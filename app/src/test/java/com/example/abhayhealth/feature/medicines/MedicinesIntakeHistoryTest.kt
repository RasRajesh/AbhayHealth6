package com.example.abhayhealth.feature.medicines

import android.app.Activity
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.abhayhealth.core.common.TestDispatcherProvider
import com.example.abhayhealth.domain.model.AuthState
import com.example.abhayhealth.domain.model.AuthUser
import com.example.abhayhealth.domain.model.PhoneOtpUiState
import com.example.abhayhealth.domain.repository.AuthRepository
import com.example.abhayhealth.feature.records.MedicalRecordDatabase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class MedicinesIntakeHistoryTest {

    private lateinit var database: MedicalRecordDatabase
    private lateinit var medicationDao: MedicationDao
    private lateinit var intakeLogDao: MedicineIntakeLogDao
    private lateinit var fakeAuth: FakeAuthRepository

    private val testDispatchers = TestDispatcherProvider()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MedicalRecordDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        medicationDao = database.medicationDao()
        intakeLogDao = database.medicineIntakeLogDao()
        fakeAuth = FakeAuthRepository(
            AuthUser(
                uid = "uid_patient_1",
                phoneNumber = "+919876543210",
                displayName = "Test Patient"
            )
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun toggleDoseTaken_and_logMedicineDoseForFuture_persistHistoryAndSurviveReload() = runBlocking {
        val viewModel = MedicinesViewModel(
            medicationDao = medicationDao,
            authRepository = fakeAuth,
            context = null,
            dispatcherProvider = testDispatchers,
            firestoreHealthRepository = null,
            medicineIntakeLogDao = intakeLogDao
        )

        val medEntity = PrescriptionMedicationEntity(
            id = "med_amlodipine",
            accountUid = "uid_patient_1",
            name = "Amlodipine",
            dosage = "5 mg",
            timeSlot = "MORNING (08:30)",
            isTaken = false
        )
        medicationDao.insertOrUpdate(medEntity)

        val medsLoaded = withTimeout(3000L) {
            viewModel.medications.first { it.any { m -> m.id == "med_amlodipine" } }
        }
        assertThat(medsLoaded).hasSize(1)

        // Marking taken from schedule automatically logs a persistent TAKEN history entry
        viewModel.toggleDoseTaken("med_amlodipine")

        val afterToggle = withTimeout(3000L) {
            viewModel.intakeLogs.first { it.isNotEmpty() }
        }
        assertThat(afterToggle).hasSize(1)
        assertThat(afterToggle.first().medicationName).isEqualTo("Amlodipine")
        assertThat(afterToggle.first().status).isEqualTo(MedicineIntakeLogEntity.STATUS_TAKEN)

        // Explicitly logging another taken dose stores a second persistent record
        viewModel.logMedicineDoseForFuture(
            medication = medsLoaded.first(),
            status = MedicineIntakeLogEntity.STATUS_TAKEN,
            notes = "Evening follow-up dose"
        )

        val afterSecondLog = withTimeout(3000L) {
            viewModel.intakeLogs.first { it.size == 2 }
        }
        assertThat(afterSecondLog.count { it.medicationId == "med_amlodipine" }).isEqualTo(2)

        // Creating a fresh ViewModel still surfaces the persisted Room history
        val reloadedViewModel = MedicinesViewModel(
            medicationDao = medicationDao,
            authRepository = fakeAuth,
            context = null,
            dispatcherProvider = testDispatchers,
            firestoreHealthRepository = null,
            medicineIntakeLogDao = intakeLogDao
        )
        val reloadedLogs = withTimeout(3000L) {
            reloadedViewModel.intakeLogs.first { it.size == 2 }
        }
        assertThat(reloadedLogs).hasSize(2)

        // Delete one log removes only that entry
        val targetId = reloadedLogs.first().id
        reloadedViewModel.deleteIntakeLog(targetId)
        val afterDelete = withTimeout(3000L) {
            reloadedViewModel.intakeLogs.first { it.size == 1 }
        }
        assertThat(afterDelete.none { it.id == targetId }).isTrue()
    }

    private class FakeAuthRepository(private val user: AuthUser) : AuthRepository {
        private val stateFlow = MutableStateFlow<AuthState>(AuthState.Authenticated(user))
        private val otpFlow = MutableStateFlow(PhoneOtpUiState())
        override val authState: StateFlow<AuthState> = stateFlow
        override val otpUiState: StateFlow<PhoneOtpUiState> = otpFlow
        override val currentUser: AuthUser? get() = user
        override val isAuthenticated: Boolean get() = true

        override fun sendPhoneOtp(activity: Activity?, tenDigitPhone: String) {}
        override fun verifyPhoneOtp(otpCode: String) {}
        override fun resendPhoneOtp(activity: Activity?) {}
        override fun cancelPhoneOtp() {}
        override fun clearOtpError() {}
        override suspend fun attemptAutoSignIn(activityContext: Context) {}
        override suspend fun signInWithGoogle(activityContext: Context) {}
        override fun signInDirectly() {}
        override suspend fun linkGmailAccount(activityContext: Context): Result<String> =
            Result.failure(UnsupportedOperationException())
        override fun clearError() {}
        override fun signOut() {}
        override fun deleteAccount(onComplete: (Boolean, String?) -> Unit) = onComplete(true, null)
    }
}
