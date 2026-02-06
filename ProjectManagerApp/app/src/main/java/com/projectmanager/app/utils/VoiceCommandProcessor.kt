package com.projectmanager.app.utils

import android.util.Log

object VoiceCommandProcessor {
    private const val TAG = "VoiceCommandProcessor"

    sealed class VoiceCommand {
        data class AddProject(val name: String) : VoiceCommand()
        data class AddTask(val taskName: String, val projectName: String? = null) : VoiceCommand()
        data class CompleteTask(val taskName: String) : VoiceCommand()
        data class ShowProject(val projectName: String) : VoiceCommand()
        object ShowSummary : VoiceCommand()
        object NextProject : VoiceCommand()
        object PreviousProject : VoiceCommand()
        object Unknown : VoiceCommand()
    }

    fun processVoiceInput(input: String): VoiceCommand {
        val lowercaseInput = input.lowercase().trim()
        Log.d(TAG, "Processing voice input: $lowercaseInput")

        return when {
            lowercaseInput.startsWith("add project") -> {
                val projectName = lowercaseInput.removePrefix("add project").trim()
                if (projectName.isNotEmpty()) {
                    VoiceCommand.AddProject(projectName)
                } else {
                    VoiceCommand.Unknown
                }
            }
            lowercaseInput.startsWith("create project") -> {
                val projectName = lowercaseInput.removePrefix("create project").trim()
                if (projectName.isNotEmpty()) {
                    VoiceCommand.AddProject(projectName)
                } else {
                    VoiceCommand.Unknown
                }
            }
            lowercaseInput.startsWith("add task") -> {
                val taskInfo = lowercaseInput.removePrefix("add task").trim()
                if (taskInfo.isNotEmpty()) {
                    VoiceCommand.AddTask(taskInfo)
                } else {
                    VoiceCommand.Unknown
                }
            }
            lowercaseInput.startsWith("complete task") || lowercaseInput.startsWith("mark complete") -> {
                val taskName = lowercaseInput
                    .removePrefix("complete task")
                    .removePrefix("mark complete")
                    .trim()
                if (taskName.isNotEmpty()) {
                    VoiceCommand.CompleteTask(taskName)
                } else {
                    VoiceCommand.Unknown
                }
            }
            lowercaseInput.startsWith("show project") || lowercaseInput.startsWith("open project") -> {
                val projectName = lowercaseInput
                    .removePrefix("show project")
                    .removePrefix("open project")
                    .trim()
                if (projectName.isNotEmpty()) {
                    VoiceCommand.ShowProject(projectName)
                } else {
                    VoiceCommand.Unknown
                }
            }
            lowercaseInput.contains("summary") || lowercaseInput.contains("daily") -> {
                VoiceCommand.ShowSummary
            }
            lowercaseInput.contains("next") -> {
                VoiceCommand.NextProject
            }
            lowercaseInput.contains("previous") || lowercaseInput.contains("back") -> {
                VoiceCommand.PreviousProject
            }
            else -> VoiceCommand.Unknown
        }
    }
}
