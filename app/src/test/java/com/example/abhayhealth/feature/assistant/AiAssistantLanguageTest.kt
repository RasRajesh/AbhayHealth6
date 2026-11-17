package com.example.abhayhealth.feature.assistant

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.abhayhealth.core.common.TestDispatcherProvider
import com.example.abhayhealth.domain.repository.UserPreferencesRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class AiAssistantLanguageTest {

    private class FakeUserPreferencesRepository(initialLanguage: String = "English") : UserPreferencesRepository {
        private val langFlow = MutableStateFlow(initialLanguage)
        override val isOnboardingCompleted: Flow<Boolean> = MutableStateFlow(true)
        override suspend fun setOnboardingCompleted(completed: Boolean) {}
        override val patientName: Flow<String?> = MutableStateFlow("Test Patient")
        override suspend fun setPatientName(name: String) {}
        override val isAiSummaryEnabled: Flow<Boolean> = MutableStateFlow(true)
        override suspend fun setAiSummaryEnabled(enabled: Boolean) {}
        override val appLanguage: Flow<String> = langFlow
        override suspend fun setAppLanguage(language: String) {
            langFlow.value = language
        }
        override val isMedRemindersEnabled: Flow<Boolean> = MutableStateFlow(true)
        override suspend fun setMedRemindersEnabled(enabled: Boolean) {}
        override val optionalBackupGmail: Flow<String?> = MutableStateFlow(null)
        override suspend fun setOptionalBackupGmail(email: String) {}
    }

    @Test
    fun setLanguage_switchesBetweenEnglishAndTeluguAndPersistsPreference() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = FakeUserPreferencesRepository("English")
        val viewModel = AiAssistantViewModel(
            context = context,
            userPreferencesRepository = prefs,
            dispatcherProvider = TestDispatcherProvider()
        )

        assertThat(viewModel.selectedLanguage.value).isEqualTo("English")
        assertThat(viewModel.chatMessages.value.first().language).isEqualTo("English")

        viewModel.setLanguage("Telugu")
        assertThat(viewModel.selectedLanguage.value).isEqualTo("Telugu")
        assertThat(viewModel.chatMessages.value.first().language).isEqualTo("Telugu")
        assertThat(viewModel.chatMessages.value.first().text).contains("నమస్కారం")

        viewModel.setLanguage("English")
        assertThat(viewModel.selectedLanguage.value).isEqualTo("English")
        assertThat(viewModel.chatMessages.value.first().language).isEqualTo("English")
        assertThat(viewModel.chatMessages.value.first().text).contains("Hello!")
    }
}
