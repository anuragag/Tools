package com.projectmanager.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.accompanist.pager.ExperimentalPagerApi
import com.google.accompanist.pager.HorizontalPager
import com.google.accompanist.pager.HorizontalPagerIndicator
import com.google.accompanist.pager.rememberPagerState
import com.projectmanager.app.data.model.Project
import com.projectmanager.app.data.model.ProjectWithTasks
import com.projectmanager.app.data.model.Task
import com.projectmanager.app.ui.components.ProjectCard
import com.projectmanager.app.ui.components.TaskItem
import kotlinx.coroutines.launch

@OptIn(ExperimentalPagerApi::class)
@Composable
fun ProjectListScreen(
    projectsWithTasks: List<ProjectWithTasks>,
    onAddProject: () -> Unit,
    onAddTask: (Long) -> Unit,
    onTaskClick: (Task) -> Unit,
    onToggleComplete: (Task) -> Unit,
    modifier: Modifier = Modifier
) {
    val pagerState = rememberPagerState()
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier.fillMaxSize()
    ) {
        if (projectsWithTasks.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "No projects yet",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = onAddProject) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Create Project")
                    }
                }
            }
        } else {
            HorizontalPager(
                count = projectsWithTasks.size,
                state = pagerState,
                modifier = Modifier.weight(1f)
            ) { page ->
                val projectWithTasks = projectsWithTasks[page]

                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    ProjectCard(
                        projectWithTasks = projectWithTasks,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Tasks",
                            style = MaterialTheme.typography.titleLarge
                        )

                        FilledTonalButton(
                            onClick = { onAddTask(projectWithTasks.project.id) }
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = "Add Task",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Add Task")
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    if (projectWithTasks.tasks.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No tasks yet. Add your first task!",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(projectWithTasks.tasks) { task ->
                                TaskItem(
                                    task = task,
                                    onTaskClick = onTaskClick,
                                    onToggleComplete = onToggleComplete
                                )
                            }
                        }
                    }
                }
            }

            if (projectsWithTasks.size > 1) {
                HorizontalPagerIndicator(
                    pagerState = pagerState,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(16.dp),
                    activeColor = MaterialTheme.colorScheme.primary,
                    inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                )
            }
        }
    }
}
