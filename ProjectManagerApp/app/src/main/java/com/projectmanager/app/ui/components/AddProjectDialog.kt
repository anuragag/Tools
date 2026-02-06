package com.projectmanager.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.projectmanager.app.data.model.Project

@Composable
fun AddProjectDialog(
    onDismiss: () -> Unit,
    onSave: (Project) -> Unit,
    project: Project? = null
) {
    var name by remember { mutableStateOf(project?.name ?: "") }
    var description by remember { mutableStateOf(project?.description ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = if (project == null) "Add Project" else "Edit Project")
        },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Project Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description (Optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 5
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isNotBlank()) {
                        val newProject = Project(
                            id = project?.id ?: 0,
                            name = name.trim(),
                            description = description.trim(),
                            createdAt = project?.createdAt ?: System.currentTimeMillis(),
                            color = project?.color ?: getRandomColor()
                        )
                        onSave(newProject)
                        onDismiss()
                    }
                },
                enabled = name.isNotBlank()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private fun getRandomColor(): String {
    val colors = listOf(
        "#6200EE", "#03DAC5", "#FF6F00", "#C51162",
        "#00C853", "#2962FF", "#AA00FF", "#DD2C00"
    )
    return colors.random()
}
