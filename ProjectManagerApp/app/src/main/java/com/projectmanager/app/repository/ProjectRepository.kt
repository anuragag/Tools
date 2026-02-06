package com.projectmanager.app.repository

import com.projectmanager.app.data.dao.ProjectDao
import com.projectmanager.app.data.dao.TaskDao
import com.projectmanager.app.data.model.Project
import com.projectmanager.app.data.model.ProjectWithTasks
import com.projectmanager.app.data.model.Task
import kotlinx.coroutines.flow.Flow
import java.util.Calendar

class ProjectRepository(
    private val projectDao: ProjectDao,
    private val taskDao: TaskDao
) {
    // Project operations
    fun getAllProjects(): Flow<List<Project>> = projectDao.getAllProjects()

    fun getProjectById(projectId: Long): Flow<Project?> = projectDao.getProjectById(projectId)

    fun getAllProjectsWithTasks(): Flow<List<ProjectWithTasks>> =
        projectDao.getAllProjectsWithTasks()

    fun getProjectWithTasks(projectId: Long): Flow<ProjectWithTasks?> =
        projectDao.getProjectWithTasks(projectId)

    suspend fun insertProject(project: Project): Long = projectDao.insertProject(project)

    suspend fun updateProject(project: Project) = projectDao.updateProject(project)

    suspend fun deleteProject(project: Project) = projectDao.deleteProject(project)

    // Task operations
    fun getTasksForProject(projectId: Long): Flow<List<Task>> =
        taskDao.getTasksForProject(projectId)

    fun getTaskById(taskId: Long): Flow<Task?> = taskDao.getTaskById(taskId)

    fun getTasksDueToday(): Flow<List<Task>> {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val startOfDay = calendar.timeInMillis

        calendar.add(Calendar.DAY_OF_MONTH, 1)
        val endOfDay = calendar.timeInMillis

        return taskDao.getTasksDueToday(startOfDay, endOfDay)
    }

    fun getOverdueTasks(): Flow<List<Task>> =
        taskDao.getOverdueTasks(System.currentTimeMillis())

    suspend fun insertTask(task: Task): Long = taskDao.insertTask(task)

    suspend fun updateTask(task: Task) = taskDao.updateTask(task)

    suspend fun deleteTask(task: Task) = taskDao.deleteTask(task)

    suspend fun updateTaskCompletion(taskId: Long, isCompleted: Boolean) =
        taskDao.updateTaskCompletion(taskId, isCompleted)
}
