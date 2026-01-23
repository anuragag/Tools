package com.slackvoice.assistant.ui.main

import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.slackvoice.assistant.catchup.CatchUpSession
import com.slackvoice.assistant.data.auth.SlackAuthManager
import com.slackvoice.assistant.data.local.DraftReply
import com.slackvoice.assistant.data.model.ChannelSummary
import com.slackvoice.assistant.data.repository.DraftReplyRepository
import com.slackvoice.assistant.data.repository.SlackRepository
import com.slackvoice.assistant.voice.VoiceAssistant
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    application: Application,
    private val authManager: SlackAuthManager,
    private val slackRepository: SlackRepository,
    private val draftRepository: DraftReplyRepository,
    private val voiceAssistant: VoiceAssistant
) : AndroidViewModel(application) {

    // Auth state
    val authState = authManager.authState

    // Voice state
    val voiceState = voiceAssistant.state
    val ttsReady = voiceAssistant.ttsReady

    // Catch-up session
    private var catchUpSession: CatchUpSession? = null

    private val _sessionState = MutableStateFlow<CatchUpSession.SessionState>(
        CatchUpSession.SessionState.NotStarted
    )
    val sessionState: StateFlow<CatchUpSession.SessionState> = _sessionState.asStateFlow()

    private val _currentChannelSummary = MutableStateFlow<ChannelSummary?>(null)
    val currentChannelSummary: StateFlow<ChannelSummary?> = _currentChannelSummary.asStateFlow()

    private val _channelProgress = MutableStateFlow(Pair(0, 0)) // current, total
    val channelProgress: StateFlow<Pair<Int, Int>> = _channelProgress.asStateFlow()

    // Drafts
    val drafts: StateFlow<List<DraftReply>> = draftRepository.getAllDrafts()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val draftCount: StateFlow<Int> = draftRepository.getDraftCount()
        .stateIn(viewModelScope, SharingStarted.Lazily, 0)

    // Error state
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // Permission state
    private val _hasAudioPermission = MutableStateFlow(checkAudioPermission())
    val hasAudioPermission: StateFlow<Boolean> = _hasAudioPermission.asStateFlow()

    private fun checkAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            getApplication(),
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun updateAudioPermission(granted: Boolean) {
        _hasAudioPermission.value = granted
    }

    /**
     * Get OAuth URL for sign in
     */
    fun getOAuthUrl(): String = authManager.getOAuthUrl()

    /**
     * Handle OAuth callback
     */
    fun handleOAuthCallback(code: String) {
        viewModelScope.launch {
            authManager.handleOAuthCallback(code).fold(
                onSuccess = {
                    _error.value = null
                },
                onFailure = { e ->
                    _error.value = e.message
                }
            )
        }
    }

    /**
     * Sign out
     */
    fun signOut() {
        authManager.signOut()
        stopSession()
    }

    /**
     * Start catch-up session
     */
    fun startCatchUp(lookbackHours: Int = 24) {
        viewModelScope.launch {
            catchUpSession = CatchUpSession(slackRepository, draftRepository, voiceAssistant)

            // Observe session state
            catchUpSession?.sessionState?.onEach { state ->
                _sessionState.value = state
            }?.launchIn(viewModelScope)

            // Observe current channel
            catchUpSession?.currentChannelIndex?.onEach { index ->
                _currentChannelSummary.value = catchUpSession?.getCurrentChannelSummary()
                _channelProgress.value = Pair(
                    index + 1,
                    catchUpSession?.getTotalChannelCount() ?: 0
                )
            }?.launchIn(viewModelScope)

            val result = catchUpSession?.startSession(lookbackHours)
            result?.onFailure { e ->
                _error.value = e.message
            }
        }
    }

    /**
     * Stop current session
     */
    fun stopSession() {
        voiceAssistant.stopSpeaking()
        voiceAssistant.cancelListening()
        catchUpSession = null
        _sessionState.value = CatchUpSession.SessionState.NotStarted
        _currentChannelSummary.value = null
        _channelProgress.value = Pair(0, 0)
    }

    /**
     * Start listening for voice input
     */
    fun startListening() {
        if (!_hasAudioPermission.value) {
            _error.value = "Microphone permission required"
            return
        }

        voiceAssistant.startListening(
            onResult = { spokenText ->
                processVoiceInput(spokenText)
            },
            onError = { speechError ->
                _error.value = when (speechError) {
                    VoiceAssistant.SpeechError.NoMatch -> "Didn't catch that. Please try again."
                    VoiceAssistant.SpeechError.NetworkError -> "Network error. Check your connection."
                    VoiceAssistant.SpeechError.PermissionDenied -> "Microphone permission required."
                    else -> "Speech recognition error. Please try again."
                }
            }
        )
    }

    /**
     * Stop listening
     */
    fun stopListening() {
        voiceAssistant.stopListening()
    }

    /**
     * Process voice input
     */
    private fun processVoiceInput(spokenText: String) {
        viewModelScope.launch {
            catchUpSession?.processCommand(spokenText)
        }
    }

    /**
     * Speak text
     */
    fun speak(text: String) {
        voiceAssistant.speak(text)
    }

    /**
     * Stop speaking
     */
    fun stopSpeaking() {
        voiceAssistant.stopSpeaking()
    }

    /**
     * Delete a draft
     */
    fun deleteDraft(draft: DraftReply) {
        viewModelScope.launch {
            draftRepository.deleteDraft(draft.id)
        }
    }

    /**
     * Clear error
     */
    fun clearError() {
        _error.value = null
    }

    override fun onCleared() {
        super.onCleared()
        voiceAssistant.shutdown()
    }
}
