package com.slackvoice.assistant.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Voice assistant that handles text-to-speech and speech-to-text
 */
@Singleton
class VoiceAssistant @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var textToSpeech: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null

    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    private val _ttsReady = MutableStateFlow(false)
    val ttsReady: StateFlow<Boolean> = _ttsReady.asStateFlow()

    private var speechResultCallback: ((String) -> Unit)? = null
    private var speechErrorCallback: ((SpeechError) -> Unit)? = null

    private var speechRate: Float = 1.0f
    private var utteranceId = 0

    // Channel for speech completion events
    private val speechCompletionChannel = Channel<Boolean>(Channel.CONFLATED)

    init {
        initTextToSpeech()
    }

    private fun initTextToSpeech() {
        textToSpeech = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech?.let { tts ->
                    val result = tts.setLanguage(Locale.US)
                    if (result == TextToSpeech.LANG_MISSING_DATA ||
                        result == TextToSpeech.LANG_NOT_SUPPORTED
                    ) {
                        _ttsReady.value = false
                    } else {
                        tts.setSpeechRate(speechRate)
                        setupTtsListener(tts)
                        _ttsReady.value = true
                    }
                }
            } else {
                _ttsReady.value = false
            }
        }
    }

    private fun setupTtsListener(tts: TextToSpeech) {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _state.value = VoiceState.Speaking
            }

            override fun onDone(utteranceId: String?) {
                _state.value = VoiceState.Idle
                speechCompletionChannel.trySend(true)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                _state.value = VoiceState.Idle
                speechCompletionChannel.trySend(false)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                _state.value = VoiceState.Idle
                speechCompletionChannel.trySend(false)
            }
        })
    }

    /**
     * Speak text using TTS
     */
    fun speak(text: String, onComplete: (() -> Unit)? = null) {
        if (!_ttsReady.value) {
            onComplete?.invoke()
            return
        }

        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "utterance_${++utteranceId}")
        }

        textToSpeech?.let { tts ->
            if (onComplete != null) {
                val currentId = utteranceId
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        _state.value = VoiceState.Speaking
                    }

                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == "utterance_$currentId") {
                            _state.value = VoiceState.Idle
                            onComplete()
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        if (utteranceId == "utterance_$currentId") {
                            _state.value = VoiceState.Idle
                            onComplete()
                        }
                    }
                })
            }
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "utterance_$utteranceId")
        }
    }

    /**
     * Add text to speech queue
     */
    fun speakQueued(text: String) {
        if (!_ttsReady.value) return

        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "utterance_${++utteranceId}")
        }
        textToSpeech?.speak(text, TextToSpeech.QUEUE_ADD, params, "utterance_$utteranceId")
    }

    /**
     * Stop speaking
     */
    fun stopSpeaking() {
        textToSpeech?.stop()
        _state.value = VoiceState.Idle
    }

    /**
     * Check if currently speaking
     */
    fun isSpeaking(): Boolean = textToSpeech?.isSpeaking == true

    /**
     * Set speech rate (0.5 to 2.0)
     */
    fun setSpeechRate(rate: Float) {
        speechRate = rate.coerceIn(0.5f, 2.0f)
        textToSpeech?.setSpeechRate(speechRate)
    }

    /**
     * Start listening for speech input
     */
    fun startListening(
        onResult: (String) -> Unit,
        onError: (SpeechError) -> Unit
    ) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError(SpeechError.NotAvailable)
            return
        }

        speechResultCallback = onResult
        speechErrorCallback = onError

        // Stop TTS if speaking
        stopSpeaking()

        // Initialize speech recognizer
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(createRecognitionListener())
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

        _state.value = VoiceState.Listening
        speechRecognizer?.startListening(intent)
    }

    /**
     * Stop listening
     */
    fun stopListening() {
        speechRecognizer?.stopListening()
        _state.value = VoiceState.Idle
    }

    /**
     * Cancel speech recognition
     */
    fun cancelListening() {
        speechRecognizer?.cancel()
        _state.value = VoiceState.Idle
    }

    private fun createRecognitionListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _state.value = VoiceState.Listening
        }

        override fun onBeginningOfSpeech() {
            _state.value = VoiceState.Listening
        }

        override fun onRmsChanged(rmsdB: Float) {
            // Could use this for audio level visualization
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            _state.value = VoiceState.Processing
        }

        override fun onError(error: Int) {
            _state.value = VoiceState.Idle
            val speechError = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> SpeechError.AudioError
                SpeechRecognizer.ERROR_CLIENT -> SpeechError.ClientError
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechError.PermissionDenied
                SpeechRecognizer.ERROR_NETWORK -> SpeechError.NetworkError
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> SpeechError.NetworkTimeout
                SpeechRecognizer.ERROR_NO_MATCH -> SpeechError.NoMatch
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> SpeechError.RecognizerBusy
                SpeechRecognizer.ERROR_SERVER -> SpeechError.ServerError
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SpeechError.SpeechTimeout
                else -> SpeechError.Unknown
            }
            speechErrorCallback?.invoke(speechError)
        }

        override fun onResults(results: Bundle?) {
            _state.value = VoiceState.Idle
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                speechResultCallback?.invoke(matches[0])
            } else {
                speechErrorCallback?.invoke(SpeechError.NoMatch)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            // Could display partial results in real-time
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    /**
     * Clean up resources
     */
    fun shutdown() {
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null

        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    sealed class VoiceState {
        data object Idle : VoiceState()
        data object Listening : VoiceState()
        data object Processing : VoiceState()
        data object Speaking : VoiceState()
    }

    sealed class SpeechError {
        data object NotAvailable : SpeechError()
        data object PermissionDenied : SpeechError()
        data object AudioError : SpeechError()
        data object ClientError : SpeechError()
        data object NetworkError : SpeechError()
        data object NetworkTimeout : SpeechError()
        data object NoMatch : SpeechError()
        data object RecognizerBusy : SpeechError()
        data object ServerError : SpeechError()
        data object SpeechTimeout : SpeechError()
        data object Unknown : SpeechError()
    }
}
