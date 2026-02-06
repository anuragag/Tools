package com.projectmanager.app.data.dao

import androidx.room.*
import com.projectmanager.app.data.model.Task
import kotlinx.coroutines.flow.Flow
import java.util.Calendar

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks WHERE projectId = :projectId ORDER BY deadline ASC")
    fun getTasksForProject(projectId: Long): Flow<List<Task>>

    @Query("SELECT * FROM tasks WHERE id = :taskId")
    fun getTaskById(taskId: Long): Flow<Task?>

    @Query("""
        SELECT * FROM tasks
        WHERE isCompleted = 0
        AND deadline >= :startOfDay
        AND deadline < :endOfDay
        ORDER BY deadline ASC
    """)
    fun getTasksDueToday(startOfDay: Long, endOfDay: Long): Flow<List<Task>>

    @Query("""
        SELECT * FROM tasks
        WHERE isCompleted = 0
        AND deadline < :currentTime
        ORDER BY deadline ASC
    """)
    fun getOverdueTasks(currentTime: Long): Flow<List<Task>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: Task): Long

    @Update
    suspend fun updateTask(task: Task)

    @Delete
    suspend fun deleteTask(task: Task)

    @Query("UPDATE tasks SET isCompleted = :isCompleted WHERE id = :taskId")
    suspend fun updateTaskCompletion(taskId: Long, isCompleted: Boolean)
}
