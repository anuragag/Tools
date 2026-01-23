package com.slackvoice.assistant.voice

/**
 * Parses voice commands from speech recognition results
 */
object VoiceCommandParser {

    /**
     * Parse a speech result into a voice command
     */
    fun parse(input: String): VoiceCommand {
        val normalized = input.lowercase().trim()

        return when {
            // Navigation commands
            normalized.matchesAny("next", "next channel", "continue", "go on", "move on") ->
                VoiceCommand.Next

            normalized.matchesAny("skip", "skip this", "skip channel", "pass", "skip it") ->
                VoiceCommand.Skip

            normalized.matchesAny("previous", "back", "go back", "last one") ->
                VoiceCommand.Previous

            // Playback commands
            normalized.matchesAny("stop", "stop it", "be quiet", "quiet", "silence", "shut up") ->
                VoiceCommand.Stop

            normalized.matchesAny("repeat", "say again", "what", "repeat that", "again", "say that again") ->
                VoiceCommand.Repeat

            normalized.matchesAny("pause", "wait", "hold on", "one moment") ->
                VoiceCommand.Pause

            normalized.matchesAny("resume", "continue speaking", "keep going", "go ahead") ->
                VoiceCommand.Resume

            // Detail commands
            normalized.matchesAny("more", "more details", "tell me more", "details", "elaborate") ->
                VoiceCommand.MoreDetails

            normalized.matchesAny("less", "summarize", "shorter", "brief", "summary only") ->
                VoiceCommand.LessDetails

            normalized.matchesAny("read messages", "read them", "read all", "read the messages") ->
                VoiceCommand.ReadMessages

            // Reply commands
            normalized.startsWith("reply") || normalized.startsWith("respond") -> {
                val message = normalized
                    .removePrefix("reply")
                    .removePrefix("respond")
                    .removePrefix("with")
                    .removePrefix("saying")
                    .trim()
                if (message.isNotEmpty()) {
                    VoiceCommand.Reply(message)
                } else {
                    VoiceCommand.StartReply
                }
            }

            normalized.matchesAny("start reply", "compose reply", "i want to reply", "let me reply", "dictate reply") ->
                VoiceCommand.StartReply

            normalized.matchesAny("done", "finished", "that's it", "send it", "done replying", "finish reply", "save reply", "save it") ->
                VoiceCommand.DoneReply

            normalized.matchesAny("cancel", "cancel reply", "never mind", "forget it", "discard") ->
                VoiceCommand.CancelReply

            // Session commands
            normalized.matchesAny("all done", "i'm done", "finish", "exit", "quit", "stop catching up") ->
                VoiceCommand.EndSession

            normalized.matchesAny("start over", "restart", "begin again", "from the beginning") ->
                VoiceCommand.Restart

            // Help
            normalized.matchesAny("help", "what can i say", "commands", "what can you do") ->
                VoiceCommand.Help

            // Speed commands
            normalized.matchesAny("slower", "speak slower", "slow down") ->
                VoiceCommand.SpeakSlower

            normalized.matchesAny("faster", "speak faster", "speed up") ->
                VoiceCommand.SpeakFaster

            // Status
            normalized.matchesAny("how many left", "how many more", "remaining", "what's left") ->
                VoiceCommand.Status

            // Default - treat as dictation content if in reply mode
            else -> VoiceCommand.Dictation(input)
        }
    }

    private fun String.matchesAny(vararg patterns: String): Boolean {
        return patterns.any { pattern ->
            this == pattern ||
            this.startsWith("$pattern ") ||
            this.endsWith(" $pattern") ||
            this.contains(" $pattern ")
        }
    }
}

/**
 * Voice commands recognized by the assistant
 */
sealed class VoiceCommand {
    // Navigation
    data object Next : VoiceCommand()
    data object Skip : VoiceCommand()
    data object Previous : VoiceCommand()

    // Playback control
    data object Stop : VoiceCommand()
    data object Repeat : VoiceCommand()
    data object Pause : VoiceCommand()
    data object Resume : VoiceCommand()

    // Detail level
    data object MoreDetails : VoiceCommand()
    data object LessDetails : VoiceCommand()
    data object ReadMessages : VoiceCommand()

    // Reply handling
    data object StartReply : VoiceCommand()
    data class Reply(val content: String) : VoiceCommand()
    data object DoneReply : VoiceCommand()
    data object CancelReply : VoiceCommand()

    // Session control
    data object EndSession : VoiceCommand()
    data object Restart : VoiceCommand()

    // Help and status
    data object Help : VoiceCommand()
    data object Status : VoiceCommand()

    // Speed
    data object SpeakSlower : VoiceCommand()
    data object SpeakFaster : VoiceCommand()

    // Raw dictation (for replies)
    data class Dictation(val text: String) : VoiceCommand()
}
