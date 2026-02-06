package com.projectmanager.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.projectmanager.app.data.model.Project
import com.projectmanager.app.data.model.ProjectWithTasks
import com.projectmanager.app.data.model.Task
import com.projectmanager.app.repository.ProjectRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ProjectViewModel(private val repository: ProjectRepository) : ViewModel() {

    val allProjectsWithTasks = repository.getAllProjectsWithTasks()
    val tasksDueToday = repository.getTasksDueToday()
    val overdueTasks = repository.getOverdueTasks()

    private val _currentProjectId = MutableStateFlow<Long?>(null)
    val currentProjectId: StateFlow<Long?> = _currentProjectId.asStateFlow()

    fun setCurrentProject(projectId: Long?) {
        _currentProjectId.value = projectId
    }

    fun insertProject(project: Project) = viewModelScope.launch {
        repository.insertProject(project)
    }

    fun updateProject(project: Project) = viewModelScope.launch {
        repository.updateProject(project)
    }

    fun deleteProject(project: Project) = viewModelScope.launch {
        repository.deleteProject(project)
    }

    fun insertTask(task: Task) = viewModelScope.launch {
        repository.insertTask(task)
    }

    fun updateTask(task: Task) = viewModelScope.launch {
        repository.updateTask(task)
    }

    fun deleteTask(task: Task) = viewModelScope.launch {
        repository.deleteTask(task)
    }

    fun toggleTaskCompletion(taskId: Long, isCompleted: Boolean) = viewModelScope.launch {
        repository.updateTaskCompletion(taskId, !isCompleted)
    }
}
