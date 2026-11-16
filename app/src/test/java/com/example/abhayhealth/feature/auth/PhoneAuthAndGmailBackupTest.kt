package com.example.abhayhealth.feature.auth

import com.example.abhayhealth.data.repository.FirebaseAuthRepository
import com.example.abhayhealth.domain.model.PhoneOtpUiState
import com.google.common.truth.Truth.assertThat
import org.junit.Test

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
}
