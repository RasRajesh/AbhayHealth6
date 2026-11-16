package com.example.abhayhealth.feature.auth

import android.app.Activity
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.abhayhealth.domain.model.AuthState
import com.example.abhayhealth.domain.model.PhoneOtpUiState
import com.example.abhayhealth.domain.repository.AuthRepository
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class AuthViewModel(
    private val authRepository: AuthRepository
) : ViewModel() {

    val authState: StateFlow<AuthState> = authRepository.authState
    val otpUiState: StateFlow<PhoneOtpUiState> = authRepository.otpUiState

    fun sendPhoneOtp(activity: Activity?, tenDigitPhone: String) {
        authRepository.sendPhoneOtp(activity, tenDigitPhone)
    }

    fun verifyPhoneOtp(otpCode: String) {
        authRepository.verifyPhoneOtp(otpCode)
    }

    fun resendPhoneOtp(activity: Activity?) {
        authRepository.resendPhoneOtp(activity)
    }

    fun cancelPhoneOtp() {
        authRepository.cancelPhoneOtp()
    }

    fun clearOtpError() {
        authRepository.clearOtpError()
    }

    fun attemptAutoSignIn(activityContext: Context) {
        viewModelScope.launch {
            authRepository.attemptAutoSignIn(activityContext)
        }
    }

    fun signInWithGoogle(activityContext: Context) {
        viewModelScope.launch {
            authRepository.signInWithGoogle(activityContext)
        }
    }

    fun clearError() {
        authRepository.clearError()
    }

    fun signOut() {
        authRepository.signOut()
    }
}
