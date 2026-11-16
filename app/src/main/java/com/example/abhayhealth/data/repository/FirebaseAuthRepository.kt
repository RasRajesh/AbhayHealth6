package com.example.abhayhealth.data.repository

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.example.abhayhealth.core.security.SecurityAudit
import com.example.abhayhealth.domain.model.AuthState
import com.example.abhayhealth.domain.model.AuthUser
import com.example.abhayhealth.domain.model.PhoneOtpUiState
import com.example.abhayhealth.domain.repository.AuthRepository
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import com.google.firebase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

private const val TAG = "FirebaseAuthRepo"

/**
 * Firebase Auth repository supporting:
 * 1) Simple +91 Indian Mobile Number + 6-digit OTP first-time login (persists across restarts)
 * 2) Optional Google/Gmail Sign-In & account linking via Jetpack Credential Manager
 *
 * No anonymous or email/password fallbacks are permitted in application code.
 */
class FirebaseAuthRepository(
    context: Context
) : AuthRepository {

    private val appContext = context.applicationContext
    private val credentialManager = CredentialManager.create(appContext)
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val auth: FirebaseAuth by lazy {
        if (FirebaseApp.getApps(appContext).isEmpty()) {
            FirebaseApp.initializeApp(appContext)
        }
        Firebase.auth
    }

    private val _authState = MutableStateFlow<AuthState>(AuthState.Initial)
    override val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _otpUiState = MutableStateFlow(PhoneOtpUiState())
    override val otpUiState: StateFlow<PhoneOtpUiState> = _otpUiState.asStateFlow()

    private var forceResendingToken: PhoneAuthProvider.ForceResendingToken? = null
    private var resendCountdownJob: Job? = null

    private val authStateListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        val user = firebaseAuth.currentUser
        _authState.value = if (user != null) {
            AuthState.Authenticated(user.toAuthUser())
        } else if (_authState.value !is AuthState.Error) {
            AuthState.Unauthenticated
        } else {
            _authState.value
        }
    }

    init {
        val existingUser = runCatching { auth.currentUser }.getOrNull()
        _authState.value = if (existingUser != null) {
            AuthState.Authenticated(existingUser.toAuthUser())
        } else {
            AuthState.Unauthenticated
        }
        runCatching { auth.addAuthStateListener(authStateListener) }
            .onFailure { e ->
                SecurityAudit.logError(TAG, "Failed to register FirebaseAuth listener", e)
            }
    }

    override val currentUser: AuthUser?
        get() = runCatching { auth.currentUser?.toAuthUser() }.getOrNull()

    override val isAuthenticated: Boolean
        get() = currentUser != null

    // --- +91 Mobile Number + OTP Authentication ---

    override fun sendPhoneOtp(activity: Activity?, tenDigitPhone: String) {
        val digits = normalizeIndianPhoneDigits(tenDigitPhone)
        if (digits.length != 10) {
            _otpUiState.update {
                it.copy(
                    phoneDigits = digits,
                    isSendingOtp = false,
                    errorMessage = "Please enter a valid 10-digit +91 mobile number."
                )
            }
            return
        }
        if (activity == null) {
            _otpUiState.update {
                it.copy(
                    phoneDigits = digits,
                    isSendingOtp = false,
                    errorMessage = "Active screen is required to verify your phone number."
                )
            }
            return
        }

        clearError()
        _otpUiState.update {
            it.copy(
                phoneDigits = digits,
                isSendingOtp = true,
                errorMessage = null
            )
        }

        val e164Phone = "+91$digits"
        startPhoneVerification(activity = activity, e164Phone = e164Phone, resendToken = null)
    }

    override fun resendPhoneOtp(activity: Activity?) {
        val state = _otpUiState.value
        if (state.resendCooldownSeconds > 0 || state.isSendingOtp) return
        val digits = normalizeIndianPhoneDigits(state.phoneDigits)
        if (digits.length != 10) {
            _otpUiState.update { it.copy(errorMessage = "Invalid +91 mobile number.") }
            return
        }
        if (activity == null) {
            _otpUiState.update { it.copy(errorMessage = "Active screen is required to resend OTP.") }
            return
        }

        _otpUiState.update { it.copy(isSendingOtp = true, errorMessage = null) }
        startPhoneVerification(
            activity = activity,
            e164Phone = "+91$digits",
            resendToken = forceResendingToken
        )
    }

    private fun startPhoneVerification(
        activity: Activity,
        e164Phone: String,
        resendToken: PhoneAuthProvider.ForceResendingToken?
    ) {
        val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
            override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                repoScope.launch {
                    signInWithPhoneCredential(credential)
                }
            }

            override fun onVerificationFailed(e: FirebaseException) {
                Log.w(TAG, "Phone OTP verification failed: ${e.message}", e)
                val friendly = formatPhoneAuthError(e)
                _otpUiState.update {
                    it.copy(
                        isSendingOtp = false,
                        isVerifyingOtp = false,
                        errorMessage = friendly
                    )
                }
            }

            override fun onCodeSent(
                verificationId: String,
                token: PhoneAuthProvider.ForceResendingToken
            ) {
                forceResendingToken = token
                _otpUiState.update {
                    it.copy(
                        verificationId = verificationId,
                        isSendingOtp = false,
                        errorMessage = null
                    )
                }
                startResendCooldown()
            }
        }

        try {
            val optionsBuilder = PhoneAuthOptions.newBuilder(auth)
                .setPhoneNumber(e164Phone)
                .setTimeout(60L, TimeUnit.SECONDS)
                .setActivity(activity)
                .setCallbacks(callbacks)

            if (resendToken != null) {
                optionsBuilder.setForceResendingToken(resendToken)
            }
            PhoneAuthProvider.verifyPhoneNumber(optionsBuilder.build())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initiate PhoneAuthProvider", e)
            _otpUiState.update {
                it.copy(
                    isSendingOtp = false,
                    errorMessage = e.localizedMessage
                        ?: "Could not start SMS OTP verification. You can also try Continue with Gmail below."
                )
            }
        }
    }

    override fun verifyPhoneOtp(otpCode: String) {
        val cleanCode = normalizeOtpCode(otpCode)
        if (cleanCode.length != 6) {
            _otpUiState.update {
                it.copy(errorMessage = "Please enter the 6-digit OTP code sent to your +91 mobile.")
            }
            return
        }
        val verificationId = _otpUiState.value.verificationId
        if (verificationId.isNullOrBlank()) {
            _otpUiState.update {
                it.copy(errorMessage = "OTP session expired. Please request a new code.")
            }
            return
        }

        _otpUiState.update { it.copy(isVerifyingOtp = true, errorMessage = null) }
        val credential = PhoneAuthProvider.getCredential(verificationId, cleanCode)
        repoScope.launch {
            signInWithPhoneCredential(credential)
        }
    }

    private suspend fun signInWithPhoneCredential(credential: PhoneAuthCredential) {
        try {
            val result = auth.signInWithCredential(credential).await()
            val signedInUser = result.user ?: auth.currentUser
            if (signedInUser != null) {
                resetOtpState()
                _authState.value = AuthState.Authenticated(signedInUser.toAuthUser())
            } else {
                _otpUiState.update {
                    it.copy(
                        isSendingOtp = false,
                        isVerifyingOtp = false,
                        errorMessage = "Could not complete phone verification. Please try again."
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Phone OTP sign-in failed", e)
            val message = when (e) {
                is FirebaseAuthInvalidCredentialsException -> "Incorrect OTP code. Please check and try again."
                else -> e.localizedMessage ?: "OTP verification failed. Please try again."
            }
            _otpUiState.update {
                it.copy(
                    isSendingOtp = false,
                    isVerifyingOtp = false,
                    errorMessage = message
                )
            }
        }
    }

    override fun cancelPhoneOtp() {
        resetOtpState()
    }

    override fun clearOtpError() {
        _otpUiState.update { it.copy(errorMessage = null) }
    }

    private fun startResendCooldown() {
        resendCountdownJob?.cancel()
        resendCountdownJob = repoScope.launch {
            for (remaining in 30 downTo 0) {
                _otpUiState.update { it.copy(resendCooldownSeconds = remaining) }
                if (remaining > 0) {
                    delay(1000L)
                }
            }
        }
    }

    private fun resetOtpState() {
        resendCountdownJob?.cancel()
        resendCountdownJob = null
        forceResendingToken = null
        _otpUiState.value = PhoneOtpUiState()
    }

    // --- Optional Google / Gmail Sign-In & Account Linking ---

    override suspend fun attemptAutoSignIn(activityContext: Context) {
        if (auth.currentUser != null) return
        val clientId = resolveWebClientId() ?: return

        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(true)
            .setServerClientId(clientId)
            .setAutoSelectEnabled(true)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        try {
            val result = credentialManager.getCredential(activityContext, request)
            handleGoogleCredential(result.credential)
        } catch (e: CancellationException) {
            throw e
        } catch (_: NoCredentialException) {
            // Silent check found no authorized account yet.
        } catch (e: GetCredentialCancellationException) {
            Log.w(TAG, "Auto Google Sign-In cancelled: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "Silent Google Sign-In skipped: ${e.message}")
        }
    }

    override suspend fun signInWithGoogle(activityContext: Context) {
        val clientId = resolveWebClientId()
        if (clientId == null) {
            _authState.value = AuthState.Error(
                message = "Gmail sign-in configuration missing (default_web_client_id).",
                isConfigurationError = true
            )
            return
        }

        if (auth.currentUser == null) {
            _authState.value = AuthState.Loading
        }

        val signInOption = GetSignInWithGoogleOption.Builder(serverClientId = clientId).build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(signInOption)
            .build()

        try {
            val result = credentialManager.getCredential(activityContext, request)
            handleGoogleCredential(result.credential)
        } catch (e: CancellationException) {
            throw e
        } catch (e: GetCredentialCancellationException) {
            Log.w(TAG, "Google Sign-In flow cancelled or dismissed: ${e.message}", e)
            _authState.value = if (auth.currentUser != null) {
                AuthState.Authenticated(auth.currentUser!!.toAuthUser())
            } else {
                AuthState.Unauthenticated
            }
        } catch (e: NoCredentialException) {
            Log.w(TAG, "No Google credentials found on device: ${e.message}", e)
            _authState.value = AuthState.Error(
                "No Gmail / Google account is configured on this device. Use your +91 mobile number above or add a Google account in device Settings."
            )
        } catch (e: Exception) {
            Log.e(TAG, "Google Sign-In failed", e)
            _authState.value = AuthState.Error(
                e.localizedMessage ?: "Gmail Sign-In failed. Please try again."
            )
        }
    }

    override suspend fun linkGmailAccount(activityContext: Context): Result<String> {
        val currentFirebaseUser = auth.currentUser
            ?: return Result.failure(IllegalStateException("Please sign in first before linking a Gmail account."))

        val existingEmail = currentFirebaseUser.email?.takeIf { it.isNotBlank() }
        if (existingEmail != null) {
            return Result.success(existingEmail)
        }

        val clientId = resolveWebClientId()
            ?: return Result.failure(IllegalStateException("Google configuration missing (default_web_client_id)."))

        val signInOption = GetSignInWithGoogleOption.Builder(serverClientId = clientId).build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(signInOption)
            .build()

        return try {
            val result = credentialManager.getCredential(activityContext, request)
            val credential = result.credential
            if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val idCred = GoogleIdTokenCredential.createFrom(credential.data)
                val firebaseCredential = GoogleAuthProvider.getCredential(idCred.idToken, null)
                val linkResult = currentFirebaseUser.linkWithCredential(firebaseCredential).await()
                val linkedUser = linkResult.user ?: auth.currentUser
                if (linkedUser != null) {
                    _authState.value = AuthState.Authenticated(linkedUser.toAuthUser())
                }
                val resolvedEmail = linkedUser?.email ?: idCred.id
                Result.success(resolvedEmail)
            } else {
                Result.failure(IllegalStateException("Unexpected credential returned from Google."))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: GetCredentialCancellationException) {
            Log.w(TAG, "Gmail link cancelled by user: ${e.message}")
            Result.failure(e)
        } catch (e: FirebaseAuthUserCollisionException) {
            Log.w(TAG, "Gmail already linked to another Firebase account", e)
            Result.failure(
                IllegalStateException(
                    "This Gmail is already registered with another account. You can still type it below as your Backup Gmail to upload PDF reports."
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to link Gmail account", e)
            Result.failure(e)
        }
    }

    private suspend fun handleGoogleCredential(credential: androidx.credentials.Credential) {
        if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
            val firebaseCredential = GoogleAuthProvider.getCredential(googleIdToken, null)
            val authResult = auth.signInWithCredential(firebaseCredential).await()
            val signedInUser = authResult.user ?: auth.currentUser
            if (signedInUser != null) {
                resetOtpState()
                _authState.value = AuthState.Authenticated(signedInUser.toAuthUser())
            } else {
                _authState.value = AuthState.Error("Unable to retrieve Firebase user after Gmail Sign-In.")
            }
        } else {
            _authState.value = AuthState.Error("Unexpected credential type returned from Gmail Sign-In.")
        }
    }

    override fun clearError() {
        if (_authState.value is AuthState.Error) {
            _authState.value = if (auth.currentUser != null) {
                AuthState.Authenticated(auth.currentUser!!.toAuthUser())
            } else {
                AuthState.Unauthenticated
            }
        }
        clearOtpError()
    }

    override fun signOut() {
        resetOtpState()
        repoScope.launch {
            withContext(NonCancellable) {
                try {
                    credentialManager.clearCredentialState(ClearCredentialStateRequest())
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to clear credential state: ${e.message}")
                } finally {
                    try {
                        auth.signOut()
                    } catch (e: Exception) {
                        SecurityAudit.logError(TAG, "Firebase sign-out error", e)
                    }
                    _authState.value = AuthState.Unauthenticated
                }
            }
        }
    }

    override fun deleteAccount(onComplete: (Boolean, String?) -> Unit) {
        val user = auth.currentUser
        if (user == null) {
            resetOtpState()
            _authState.value = AuthState.Unauthenticated
            onComplete(true, null)
            return
        }

        user.delete().addOnCompleteListener { task ->
            if (task.isSuccessful) {
                resetOtpState()
                repoScope.launch {
                    withContext(NonCancellable) {
                        runCatching {
                            credentialManager.clearCredentialState(ClearCredentialStateRequest())
                        }
                    }
                }
                _authState.value = AuthState.Unauthenticated
                onComplete(true, null)
            } else {
                val exception = task.exception
                val errorMsg = if (exception is FirebaseAuthRecentLoginRequiredException) {
                    "Security requirement: Please sign out and sign in again before deleting your account."
                } else {
                    exception?.localizedMessage ?: "Failed to delete Firebase account."
                }
                onComplete(false, errorMsg)
            }
        }
    }

    private fun resolveWebClientId(): String? {
        return try {
            val id = appContext.resources.getIdentifier(
                "default_web_client_id",
                "string",
                appContext.packageName
            )
            if (id != 0) appContext.getString(id).takeIf { it.isNotBlank() } else null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve default_web_client_id", e)
            null
        }
    }

    private fun FirebaseUser.toAuthUser(): AuthUser = AuthUser(
        uid = uid,
        phoneNumber = phoneNumber,
        email = email,
        displayName = displayName
    )

    companion object {
        fun normalizeIndianPhoneDigits(rawInput: String): String {
            val digits = rawInput.filter { it.isDigit() }
            return when {
                digits.length > 10 && digits.startsWith("91") -> digits.drop(2).take(10)
                digits.length > 10 && digits.startsWith("0") -> digits.drop(1).take(10)
                else -> digits.take(10)
            }
        }

        fun formatE164India(rawInput: String): String? {
            val digits = normalizeIndianPhoneDigits(rawInput)
            return if (digits.length == 10) "+91$digits" else null
        }

        fun normalizeOtpCode(rawInput: String): String =
            rawInput.filter { it.isDigit() }.take(6)

        fun isValidOptionalEmail(rawEmail: String): Boolean {
            val trimmed = rawEmail.trim()
            if (trimmed.isEmpty()) return false
            return Regex("""^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$""").matches(trimmed)
        }

        private fun formatPhoneAuthError(e: FirebaseException): String {
            return when (e) {
                is FirebaseTooManyRequestsException ->
                    "Too many OTP attempts right now. Please wait a minute or try Continue with Gmail (Optional)."
                is FirebaseAuthInvalidCredentialsException ->
                    "Invalid mobile number format. Please check your 10-digit +91 number."
                is FirebaseAuthException -> when (e.errorCode) {
                    "ERROR_OPERATION_NOT_ALLOWED", "OPERATION_NOT_ALLOWED" ->
                        "Phone OTP sign-in is not yet enabled in Firebase Auth Console for this project. Enable Phone provider in Firebase Console or use Continue with Gmail (Optional) below."
                    else -> e.localizedMessage
                        ?: "Unable to send OTP right now. You can also try Continue with Gmail (Optional)."
                }
                else -> e.localizedMessage
                    ?: "Could not send OTP right now. Check your internet connection or try Continue with Gmail (Optional)."
            }
        }
    }
}
