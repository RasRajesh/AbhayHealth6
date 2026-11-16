package com.example.abhayhealth.domain.repository

import android.app.Activity
import android.content.Context
import com.example.abhayhealth.domain.model.AuthState
import com.example.abhayhealth.domain.model.AuthUser
import com.example.abhayhealth.domain.model.PhoneOtpUiState
import kotlinx.coroutines.flow.StateFlow

/**
 * Authentication Repository supporting +91 Phone Number + OTP login (primary)
 * and optional Google/Gmail Sign-In & account linking backed by Firebase Auth.
 */
interface AuthRepository {
    val authState: StateFlow<AuthState>
    val otpUiState: StateFlow<PhoneOtpUiState>
    val currentUser: AuthUser?
    val isAuthenticated: Boolean

    fun sendPhoneOtp(activity: Activity?, tenDigitPhone: String)
    fun verifyPhoneOtp(otpCode: String)
    fun resendPhoneOtp(activity: Activity?)
    fun cancelPhoneOtp()
    fun clearOtpError()

    suspend fun attemptAutoSignIn(activityContext: Context)
    suspend fun signInWithGoogle(activityContext: Context)
    suspend fun linkGmailAccount(activityContext: Context): Result<String>
    fun clearError()
    fun signOut()
    fun deleteAccount(onComplete: (Boolean, String?) -> Unit)
}
