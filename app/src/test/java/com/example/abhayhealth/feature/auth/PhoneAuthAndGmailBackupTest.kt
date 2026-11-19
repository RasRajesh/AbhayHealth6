package com.example.abhayhealth.feature.auth

import com.example.abhayhealth.data.repository.FirebaseAuthRepository
import com.example.abhayhealth.domain.model.PhoneOtpUiState
import com.google.common.truth.Truth.assertThat
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuthException
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class PhoneAuthAndGmailBackupTest {

    @Test
    fun normalizeIndianPhoneDigits_stripsCountryCodeAndNonDigits() {
        assertThat(FirebaseAuthRepository.normalizeIndianPhoneDigits("9876543210"))
            .isEqualTo("9876543210")
        assertThat(FirebaseAuthRepository.normalizeIndianPhoneDigits("+91 98765 43210"))
            .isEqualTo("9876543210")
        assertThat(FirebaseAuthRepository.normalizeIndianPhoneDigits("919876543210"))
            .isEqualTo("9876543210")
        assertThat(FirebaseAuthRepository.normalizeIndianPhoneDigits("09876543210"))
            .isEqualTo("9876543210")
        assertThat(FirebaseAuthRepository.normalizeIndianPhoneDigits("98765-43210-99"))
            .isEqualTo("9876543210")
    }

    @Test
    fun formatE164India_returnsValidPlus91OnlyForTenDigits() {
        assertThat(FirebaseAuthRepository.formatE164India("98765 43210"))
            .isEqualTo("+919876543210")
        assertThat(FirebaseAuthRepository.formatE164India("+91 9123456789"))
            .isEqualTo("+919123456789")
        assertThat(FirebaseAuthRepository.formatE164India("12345")).isNull()
        assertThat(FirebaseAuthRepository.formatE164India("")).isNull()
    }

    @Test
    fun normalizeOtpCode_keepsMaxSixDigits() {
        assertThat(FirebaseAuthRepository.normalizeOtpCode("12-34 56"))
            .isEqualTo("123456")
        assertThat(FirebaseAuthRepository.normalizeOtpCode("123456789"))
            .isEqualTo("123456")
        assertThat(FirebaseAuthRepository.normalizeOtpCode("abc"))
            .isEmpty()
    }

    @Test
    fun isValidOptionalEmail_validatesGmailAndStandardAddresses() {
        assertThat(FirebaseAuthRepository.isValidOptionalEmail("patient@gmail.com")).isTrue()
        assertThat(FirebaseAuthRepository.isValidOptionalEmail("  user.name+health@gmail.com  ")).isTrue()
        assertThat(FirebaseAuthRepository.isValidOptionalEmail("")).isFalse()
        assertThat(FirebaseAuthRepository.isValidOptionalEmail("not-an-email")).isFalse()
        assertThat(FirebaseAuthRepository.isValidOptionalEmail("user@domain")).isFalse()
    }

    @Test
    fun phoneOtpUiState_computesSentAndFormattedDisplayPhoneCorrectly() {
        val initial = PhoneOtpUiState(phoneDigits = "9876543210")
        assertThat(initial.isOtpSent).isFalse()
        assertThat(initial.fullE164Phone).isEqualTo("+919876543210")
        assertThat(initial.formattedDisplayPhone).isEqualTo("+91 98765 43210")

        val sent = initial.copy(verificationId = "verif_abc_123")
        assertThat(sent.isOtpSent).isTrue()
    }

    @Test
    fun formatPhoneAuthError_translatesRecaptchaAndConsoleConfigErrorsClearly() {
        val recaptchaEx = FirebaseException("No Recaptcha Enterprise siteKey configured for tenant/project *")
        val recaptchaMsg = FirebaseAuthRepository.formatPhoneAuthError(recaptchaEx)
        assertThat(recaptchaMsg).contains("reCAPTCHA Enterprise")
        assertThat(recaptchaMsg).contains("Continue with Gmail (Optional)")

        val opNotAllowed = FirebaseAuthException("ERROR_OPERATION_NOT_ALLOWED", "Phone provider disabled")
        val opMsg = FirebaseAuthRepository.formatPhoneAuthError(opNotAllowed)
        assertThat(opMsg).contains("not yet enabled")
        assertThat(opMsg).contains("Continue with Gmail (Optional)")

        val missingClientId = FirebaseAuthException("ERROR_MISSING_CLIENT_IDENTIFIER", "Client validation failed")
        val clientMsg = FirebaseAuthRepository.formatPhoneAuthError(missingClientId)
        assertThat(clientMsg).contains("reCAPTCHA Enterprise")
    }

    @Test
    fun authViewModel_delegatesOtpAndStateActionsCorrectly() {
        val fakeRepo = FakeAuthRepository()
        val viewModel = AuthViewModel(fakeRepo)

        assertThat(viewModel.authState.value).isEqualTo(com.example.abhayhealth.domain.model.AuthState.Unauthenticated)

        viewModel.sendPhoneOtp(null, "9876543210")
        assertThat(fakeRepo.lastSentPhone).isEqualTo("9876543210")

        viewModel.verifyPhoneOtp("123456")
        assertThat(fakeRepo.lastVerifiedCode).isEqualTo("123456")

        viewModel.cancelPhoneOtp()
        assertThat(fakeRepo.cancelCalled).isTrue()
    }

    private class FakeAuthRepository : com.example.abhayhealth.domain.repository.AuthRepository {
        private val _authState = kotlinx.coroutines.flow.MutableStateFlow<com.example.abhayhealth.domain.model.AuthState>(
            com.example.abhayhealth.domain.model.AuthState.Unauthenticated
        )
        override val authState = _authState.asStateFlow()

        private val _otpUiState = kotlinx.coroutines.flow.MutableStateFlow(PhoneOtpUiState())
        override val otpUiState = _otpUiState.asStateFlow()

        override val currentUser = null
        override val isAuthenticated = false

        var lastSentPhone: String? = null
        var lastVerifiedCode: String? = null
        var cancelCalled = false

        override fun sendPhoneOtp(activity: android.app.Activity?, tenDigitPhone: String) {
            lastSentPhone = tenDigitPhone
        }

        override fun verifyPhoneOtp(otpCode: String) {
            lastVerifiedCode = otpCode
        }

        override fun resendPhoneOtp(activity: android.app.Activity?) {}
        override fun cancelPhoneOtp() { cancelCalled = true }
        override fun clearOtpError() {}
        override suspend fun attemptAutoSignIn(activityContext: android.content.Context) {}
        override suspend fun signInWithGoogle(activityContext: android.content.Context) {}
        override suspend fun linkGmailAccount(activityContext: android.content.Context): Result<String> = Result.success("test@gmail.com")
        override fun clearError() {}
        override fun signOut() {}
        override fun deleteAccount(onComplete: (Boolean, String?) -> Unit) { onComplete(true, null) }
    }
}
