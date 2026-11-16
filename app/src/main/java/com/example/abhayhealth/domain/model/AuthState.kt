package com.example.abhayhealth.domain.model

/**
 * Domain state for Firebase Authentication flow (+91 Phone OTP primary, Google optional).
 */
sealed interface AuthState {
    data object Initial : AuthState
    data object Loading : AuthState
    data class Authenticated(val user: AuthUser) : AuthState
    data object Unauthenticated : AuthState
    data class Error(
        val message: String,
        val isConfigurationError: Boolean = false
    ) : AuthState
}

/**
 * UI and verification state for +91 Indian Mobile Number + OTP first-time login.
 */
data class PhoneOtpUiState(
    val phoneDigits: String = "",
    val verificationId: String? = null,
    val isSendingOtp: Boolean = false,
    val isVerifyingOtp: Boolean = false,
    val resendCooldownSeconds: Int = 0,
    val errorMessage: String? = null
) {
    val isOtpSent: Boolean
        get() = !verificationId.isNullOrBlank()

    val fullE164Phone: String
        get() = if (phoneDigits.length == 10) "+91$phoneDigits" else ""

    val formattedDisplayPhone: String
        get() = if (phoneDigits.length == 10) {
            "+91 ${phoneDigits.substring(0, 5)} ${phoneDigits.substring(5)}"
        } else {
            "+91 $phoneDigits"
        }
}
