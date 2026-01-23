package com.slackvoice.assistant.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.slackvoice.assistant.data.local.DraftReply
import com.slackvoice.assistant.ui.components.DraftsList

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DraftsScreen(
    drafts: List<DraftReply>,
    onDeleteDraft: (DraftReply) -> Unit,
    onDeleteAllDrafts: () -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showDeleteAllDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Draft Replies") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    if (drafts.isNotEmpty()) {
                        IconButton(onClick = { showDeleteAllDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.DeleteSweep,
                                contentDescription = "Delete all drafts"
                            )
                        }
                    }
                }
            )
        },
        modifier = modifier
    ) { paddingValues ->
        DraftsList(
            drafts = drafts,
            onDeleteDraft = onDeleteDraft,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        )
    }

    // Delete all confirmation dialog
    if (showDeleteAllDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAllDialog = false },
            title = { Text("Delete All Drafts?") },
            text = { Text("This will permanently delete all ${drafts.size} draft replies.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteAllDrafts()
                        showDeleteAllDialog = false
                    }
                ) {
                    Text("Delete All", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAllDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
