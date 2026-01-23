package com.slackvoice.assistant.catchup

import com.slackvoice.assistant.data.local.DraftReply
import com.slackvoice.assistant.data.model.ChannelSummary
import com.slackvoice.assistant.data.model.SlackMessage
import com.slackvoice.assistant.data.repository.DraftReplyRepository
import com.slackvoice.assistant.data.repository.SlackRepository
import com.slackvoice.assistant.voice.VoiceAssistant
import com.slackvoice.assistant.voice.VoiceCommand
import com.slackvoice.assistant.voice.VoiceCommandParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * Manages a catch-up session with voice interaction
 */
class CatchUpSession @Inject constructor(
    private val slackRepository: SlackRepository,
    private val draftRepository: DraftReplyRepository,
    private val voiceAssistant: VoiceAssistant
) {
    private val _sessionState = MutableStateFlow<SessionState>(SessionState.NotStarted)
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    private val _currentChannelIndex = MutableStateFlow(0)
    val currentChannelIndex: StateFlow<Int> = _currentChannelIndex.asStateFlow()

    private var channelSummaries: List<ChannelSummary> = emptyList()
    private var skippedChannels: MutableSet<String> = mutableSetOf()

    // Draft in progress
    private var currentDraftText: StringBuilder = StringBuilder()
    private var currentDraftChannelSummary: ChannelSummary? = null
    private var currentDraftMessage: SlackMessage? = null

    // Detail level for summaries
    private var detailLevel: DetailLevel = DetailLevel.Normal

    // Last spoken text (for repeat command)
    private var lastSpokenText: String = ""

    /**
     * Start a new catch-up session
     */
    suspend fun startSession(lookbackHours: Int = 24): Result<Unit> {
        _sessionState.value = SessionState.Loading

        return try {
            val result = slackRepository.getChannelSummaries(lookbackHours)

            result.fold(
                onSuccess = { summaries ->
                    channelSummaries = summaries.filter { it.messageCount > 0 }
                    skippedChannels.clear()
                    _currentChannelIndex.value = 0

                    if (channelSummaries.isEmpty()) {
                        _sessionState.value = SessionState.NoMessages
                        speak("No new messages to catch up on.")
                    } else {
                        _sessionState.value = SessionState.InProgress
                        speakIntro()
                    }
                    Result.success(Unit)
                },
                onFailure = { error ->
                    _sessionState.value = SessionState.Error(error.message ?: "Unknown error")
                    Result.failure(error)
                }
            )
        } catch (e: Exception) {
            _sessionState.value = SessionState.Error(e.message ?: "Unknown error")
            Result.failure(e)
        }
    }

    private fun speakIntro() {
        val totalChannels = channelSummaries.size
        val totalMessages = channelSummaries.sumOf { it.messageCount }
        val mentionChannels = channelSummaries.count { it.hasUnreadMentions }

        val intro = buildString {
            append("You have activity in $totalChannels channels with $totalMessages total messages. ")
            if (mentionChannels > 0) {
                append("You were mentioned in $mentionChannels channels. ")
            }
            append("Say 'next' to hear summaries, 'skip' to skip a channel, or 'reply' to compose a response.")
        }

        speak(intro) {
            speakCurrentChannel()
        }
    }

    /**
     * Process a voice command
     */
    suspend fun processCommand(spokenText: String) {
        val command = VoiceCommandParser.parse(spokenText)
        handleCommand(command)
    }

    private suspend fun handleCommand(command: VoiceCommand) {
        when (val state = _sessionState.value) {
            is SessionState.InProgress -> handleInProgressCommand(command)
            is SessionState.Replying -> handleReplyingCommand(command)
            is SessionState.Paused -> handlePausedCommand(command)
            else -> {
                // Ignore commands in other states
            }
        }
    }

    private suspend fun handleInProgressCommand(command: VoiceCommand) {
        when (command) {
            is VoiceCommand.Next -> moveToNextChannel()
            is VoiceCommand.Skip -> skipCurrentChannel()
            is VoiceCommand.Previous -> moveToPreviousChannel()
            is VoiceCommand.Stop -> stopSpeaking()
            is VoiceCommand.Repeat -> repeatLastSpoken()
            is VoiceCommand.Pause -> pauseSession()
            is VoiceCommand.MoreDetails -> increaseDetail()
            is VoiceCommand.LessDetails -> decreaseDetail()
            is VoiceCommand.ReadMessages -> readMessages()
            is VoiceCommand.StartReply -> startReply()
            is VoiceCommand.Reply -> startReplyWithContent(command.content)
            is VoiceCommand.Help -> speakHelp()
            is VoiceCommand.Status -> speakStatus()
            is VoiceCommand.SpeakSlower -> adjustSpeed(-0.2f)
            is VoiceCommand.SpeakFaster -> adjustSpeed(0.2f)
            is VoiceCommand.EndSession -> endSession()
            is VoiceCommand.Restart -> restartSession()
            else -> {} // Ignore other commands
        }
    }

    private suspend fun handleReplyingCommand(command: VoiceCommand) {
        when (command) {
            is VoiceCommand.DoneReply -> finishReply()
            is VoiceCommand.CancelReply -> cancelReply()
            is VoiceCommand.Stop -> stopSpeaking()
            is VoiceCommand.Dictation -> appendToReply(command.text)
            is VoiceCommand.Reply -> appendToReply(command.content)
            else -> {
                speak("You're composing a reply. Say your message, then say 'done' to save or 'cancel' to discard.")
            }
        }
    }

    private fun handlePausedCommand(command: VoiceCommand) {
        when (command) {
            is VoiceCommand.Resume -> resumeSession()
            is VoiceCommand.EndSession -> endSession()
            is VoiceCommand.Stop -> {} // Already stopped
            else -> speak("Session is paused. Say 'resume' to continue or 'done' to end.")
        }
    }

    private fun speakCurrentChannel() {
        if (_currentChannelIndex.value >= channelSummaries.size) {
            finishSession()
            return
        }

        val summary = channelSummaries[_currentChannelIndex.value]
        val text = generateSummaryText(summary)
        speak(text)
    }

    private fun generateSummaryText(summary: ChannelSummary): String {
        return when (detailLevel) {
            DetailLevel.Brief -> generateBriefSummary(summary)
            DetailLevel.Normal -> summary.summary
            DetailLevel.Detailed -> generateDetailedSummary(summary)
        }
    }

    private fun generateBriefSummary(summary: ChannelSummary): String {
        return buildString {
            append("${summary.channel.name}: ${summary.messageCount} messages")
            if (summary.hasUnreadMentions) {
                append(", you were mentioned")
            }
            append(".")
        }
    }

    private fun generateDetailedSummary(summary: ChannelSummary): String {
        return buildString {
            append(summary.summary)
            append(" ")

            // Add top message preview
            summary.topMessages.firstOrNull()?.let { topMsg ->
                val authorName = summary.participants.find { it.id == topMsg.userId }?.displayableName()
                    ?: "Someone"
                val preview = topMsg.text.take(100).replace(Regex("<[^>]+>"), "")
                append("Top message from $authorName: \"$preview\". ")
            }

            // Add reaction info
            val totalReactions = summary.topMessages.sumOf { msg ->
                msg.reactions.sumOf { it.count }
            }
            if (totalReactions > 0) {
                append("$totalReactions total reactions. ")
            }
        }
    }

    private fun moveToNextChannel() {
        if (_currentChannelIndex.value < channelSummaries.size - 1) {
            _currentChannelIndex.value++
            speakCurrentChannel()
        } else {
            finishSession()
        }
    }

    private fun skipCurrentChannel() {
        val currentSummary = channelSummaries.getOrNull(_currentChannelIndex.value)
        currentSummary?.let {
            skippedChannels.add(it.channel.id)
        }

        speak("Skipped.") {
            moveToNextChannel()
        }
    }

    private fun moveToPreviousChannel() {
        if (_currentChannelIndex.value > 0) {
            _currentChannelIndex.value--
            speakCurrentChannel()
        } else {
            speak("This is the first channel.")
        }
    }

    private fun stopSpeaking() {
        voiceAssistant.stopSpeaking()
    }

    private fun repeatLastSpoken() {
        if (lastSpokenText.isNotEmpty()) {
            voiceAssistant.speak(lastSpokenText)
        } else {
            speakCurrentChannel()
        }
    }

    private fun pauseSession() {
        stopSpeaking()
        _sessionState.value = SessionState.Paused
        speak("Session paused. Say 'resume' to continue.")
    }

    private fun resumeSession() {
        _sessionState.value = SessionState.InProgress
        speak("Resuming.") {
            speakCurrentChannel()
        }
    }

    private fun increaseDetail() {
        detailLevel = when (detailLevel) {
            DetailLevel.Brief -> DetailLevel.Normal
            DetailLevel.Normal -> DetailLevel.Detailed
            DetailLevel.Detailed -> DetailLevel.Detailed
        }
        speak("Showing more details.") {
            speakCurrentChannel()
        }
    }

    private fun decreaseDetail() {
        detailLevel = when (detailLevel) {
            DetailLevel.Detailed -> DetailLevel.Normal
            DetailLevel.Normal -> DetailLevel.Brief
            DetailLevel.Brief -> DetailLevel.Brief
        }
        speak("Showing less details.") {
            speakCurrentChannel()
        }
    }

    private fun readMessages() {
        val summary = channelSummaries.getOrNull(_currentChannelIndex.value) ?: return

        val text = buildString {
            append("Reading recent messages from ${summary.channel.name}. ")
            summary.topMessages.take(5).forEach { msg ->
                val author = summary.participants.find { it.id == msg.userId }?.displayableName()
                    ?: "Unknown"
                val cleanText = msg.text.replace(Regex("<[^>]+>"), "").take(200)
                append("$author said: $cleanText. ")
            }
        }

        speak(text)
    }

    private fun startReply() {
        val summary = channelSummaries.getOrNull(_currentChannelIndex.value) ?: return

        currentDraftText.clear()
        currentDraftChannelSummary = summary
        currentDraftMessage = summary.topMessages.firstOrNull()

        _sessionState.value = SessionState.Replying
        speak("Composing reply to ${summary.channel.name}. Say your message, then say 'done' when finished.")
    }

    private fun startReplyWithContent(content: String) {
        val summary = channelSummaries.getOrNull(_currentChannelIndex.value) ?: return

        currentDraftText.clear()
        currentDraftText.append(content)
        currentDraftChannelSummary = summary
        currentDraftMessage = summary.topMessages.firstOrNull()

        _sessionState.value = SessionState.Replying
        speak("Got it: \"$content\". Continue your reply or say 'done' to save.")
    }

    private fun appendToReply(text: String) {
        if (currentDraftText.isNotEmpty()) {
            currentDraftText.append(" ")
        }
        currentDraftText.append(text)
        speak("Added: \"$text\"")
    }

    private suspend fun finishReply() {
        val summary = currentDraftChannelSummary ?: return
        val draftText = currentDraftText.toString()

        if (draftText.isBlank()) {
            speak("Reply is empty. Cancelled.")
            cancelReplyInternal()
            return
        }

        // Get author name for the original message
        val authorName = currentDraftMessage?.userId?.let { userId ->
            summary.participants.find { it.id == userId }?.displayableName()
        }

        // Save draft to database
        draftRepository.createDraftForChannel(
            summary = summary,
            draftText = draftText,
            replyToMessage = currentDraftMessage,
            authorName = authorName
        )

        speak("Draft saved for ${summary.channel.name}.") {
            cancelReplyInternal()
            moveToNextChannel()
        }
    }

    private fun cancelReply() {
        speak("Reply cancelled.") {
            cancelReplyInternal()
        }
    }

    private fun cancelReplyInternal() {
        currentDraftText.clear()
        currentDraftChannelSummary = null
        currentDraftMessage = null
        _sessionState.value = SessionState.InProgress
    }

    private fun speakHelp() {
        val helpText = """
            Here are the commands you can use:
            Say 'next' to go to the next channel.
            Say 'skip' to skip the current channel.
            Say 'previous' or 'back' to go back.
            Say 'repeat' to hear the summary again.
            Say 'more details' or 'less details' to change detail level.
            Say 'read messages' to hear individual messages.
            Say 'reply' to compose a response.
            Say 'slower' or 'faster' to change speech speed.
            Say 'done' when you've finished catching up.
        """.trimIndent().replace("\n", " ")

        speak(helpText)
    }

    private fun speakStatus() {
        val remaining = channelSummaries.size - _currentChannelIndex.value - 1
        val skipped = skippedChannels.size
        val text = "You're on channel ${_currentChannelIndex.value + 1} of ${channelSummaries.size}. " +
                "$remaining channels remaining. $skipped channels skipped."
        speak(text)
    }

    private fun adjustSpeed(delta: Float) {
        val currentRate = 1.0f // Would need to track this
        val newRate = (currentRate + delta).coerceIn(0.5f, 2.0f)
        voiceAssistant.setSpeechRate(newRate)
        speak(if (delta > 0) "Speaking faster." else "Speaking slower.")
    }

    private fun finishSession() {
        _sessionState.value = SessionState.Completed

        val skippedCount = skippedChannels.size
        val text = buildString {
            append("All caught up! ")
            if (skippedCount > 0) {
                append("You skipped $skippedCount channels. ")
            }
        }

        speak(text)
    }

    private fun endSession() {
        voiceAssistant.stopSpeaking()
        _sessionState.value = SessionState.Completed
        speak("Session ended.")
    }

    private suspend fun restartSession() {
        speak("Starting over.") {
            // Restart will be handled by ViewModel
        }
        startSession()
    }

    private fun speak(text: String, onComplete: (() -> Unit)? = null) {
        lastSpokenText = text
        voiceAssistant.speak(text, onComplete)
    }

    /**
     * Get current channel summary
     */
    fun getCurrentChannelSummary(): ChannelSummary? {
        return channelSummaries.getOrNull(_currentChannelIndex.value)
    }

    /**
     * Get all channel summaries
     */
    fun getChannelSummaries(): List<ChannelSummary> = channelSummaries

    /**
     * Get total channel count
     */
    fun getTotalChannelCount(): Int = channelSummaries.size

    /**
     * Check if channel was skipped
     */
    fun isChannelSkipped(channelId: String): Boolean = channelId in skippedChannels

    sealed class SessionState {
        data object NotStarted : SessionState()
        data object Loading : SessionState()
        data object InProgress : SessionState()
        data object Replying : SessionState()
        data object Paused : SessionState()
        data object NoMessages : SessionState()
        data object Completed : SessionState()
        data class Error(val message: String) : SessionState()
    }

    enum class DetailLevel {
        Brief,
        Normal,
        Detailed
    }
}
