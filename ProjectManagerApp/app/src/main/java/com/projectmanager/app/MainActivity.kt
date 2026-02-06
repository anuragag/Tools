package com.projectmanager.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.projectmanager.app.data.database.AppDatabase
import com.projectmanager.app.data.model.Project
import com.projectmanager.app.data.model.Task
import com.projectmanager.app.notification.NotificationScheduler
import com.projectmanager.app.repository.ProjectRepository
import com.projectmanager.app.ui.components.*
import com.projectmanager.app.ui.screens.DailySummaryScreen
import com.projectmanager.app.ui.screens.ProjectListScreen
import com.projectmanager.app.ui.theme.ProjectManagerTheme
import com.projectmanager.app.utils.VoiceCommandProcessor
import com.projectmanager.app.viewmodel.ProjectViewModel
import com.projectmanager.app.viewmodel.ProjectViewModelFactory

class MainActivity : ComponentActivity() {

    private val viewModel: ProjectViewModel by viewModels {
        val database = AppDatabase.getDatabase(applicationContext)
        val repository = ProjectRepository(database.projectDao(), database.taskDao())
        ProjectViewModelFactory(repository)
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            NotificationScheduler.scheduleDailySummary(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestNotificationPermission()

        val navigateToSummary = intent.getBooleanExtra("navigate_to_summary", false)

        setContent {
            ProjectManagerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ProjectManagerApp(
                        viewModel = viewModel,
                        initialTab = if (navigateToSummary) 1 else 0,
                        onVoiceCommand = { handleVoiceCommand(it) }
                    )
                }
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    NotificationScheduler.scheduleDailySummary(this)
                }
                else -> {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } else {
            NotificationScheduler.scheduleDailySummary(this)
        }
    }

    private fun handleVoiceCommand(voiceInput: String) {
        when (val command = VoiceCommandProcessor.processVoiceInput(voiceInput)) {
            is VoiceCommandProcessor.VoiceCommand.AddProject -> {
                val project = Project(name = command.name)
                viewModel.insertProject(project)
                showToast("Project '${command.name}' created")
            }
            is VoiceCommandProcessor.VoiceCommand.AddTask -> {
                showToast("Please select a project first to add task")
            }
            is VoiceCommandProcessor.VoiceCommand.ShowSummary -> {
                showToast("Navigating to summary...")
            }
            is VoiceCommandProcessor.VoiceCommand.NextProject -> {
                showToast("Swipe to next project")
            }
            is VoiceCommandProcessor.VoiceCommand.PreviousProject -> {
                showToast("Swipe to previous project")
            }
            else -> {
                showToast("Command not recognized")
            }
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectManagerApp(
    viewModel: ProjectViewModel,
    initialTab: Int = 0,
    onVoiceCommand: (String) -> Unit
) {
    var selectedTab by remember { mutableStateOf(initialTab) }
    var showAddProjectDialog by remember { mutableStateOf(false) }
    var showAddTaskDialog by remember { mutableStateOf(false) }
    var selectedProjectId by remember { mutableStateOf<Long?>(null) }

    val projectsWithTasks by viewModel.allProjectsWithTasks.collectAsStateWithLifecycle(
        initialValue = emptyList()
    )
    val tasksDueToday by viewModel.tasksDueToday.collectAsStateWithLifecycle(
        initialValue = emptyList()
    )
    val overdueTasks by viewModel.overdueTasks.collectAsStateWithLifecycle(
        initialValue = emptyList()
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (selectedTab == 0) "Projects" else "Daily Summary"
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.List, contentDescription = "Projects") },
                    label = { Text("Projects") },
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.CalendarToday, contentDescription = "Summary") },
                    label = { Text("Summary") },
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 }
                )
            }
        },
        floatingActionButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VoiceCommandButton(
                    onVoiceResult = onVoiceCommand
                )
                if (selectedTab == 0) {
                    FloatingActionButton(
                        onClick = { showAddProjectDialog = true }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add Project"
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {
            when (selectedTab) {
                0 -> ProjectListScreen(
                    projectsWithTasks = projectsWithTasks,
                    onAddProject = { showAddProjectDialog = true },
                    onAddTask = { projectId ->
                        selectedProjectId = projectId
                        showAddTaskDialog = true
                    },
                    onTaskClick = { },
                    onToggleComplete = { task ->
                        viewModel.toggleTaskCompletion(task.id, task.isCompleted)
                    }
                )
                1 -> DailySummaryScreen(
                    tasksDueToday = tasksDueToday,
                    overdueTasks = overdueTasks,
                    onTaskClick = { },
                    onToggleComplete = { task ->
                        viewModel.toggleTaskCompletion(task.id, task.isCompleted)
                    }
                )
            }
        }
    }

    if (showAddProjectDialog) {
        AddProjectDialog(
            onDismiss = { showAddProjectDialog = false },
            onSave = { project ->
                viewModel.insertProject(project)
            }
        )
    }

    if (showAddTaskDialog && selectedProjectId != null) {
        AddTaskDialog(
            projectId = selectedProjectId!!,
            onDismiss = {
                showAddTaskDialog = false
                selectedProjectId = null
            },
            onSave = { task ->
                viewModel.insertTask(task)
            }
        )
    }
}
