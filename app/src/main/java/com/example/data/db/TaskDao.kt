package com.example.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.CategoryEntity
import com.example.data.model.TaskCompletionEntity
import com.example.data.model.TaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {

    @Query("SELECT * FROM tasks ORDER BY createdAt DESC")
    fun getAllTasks(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE id = :id LIMIT 1")
    suspend fun getTaskById(id: Long): TaskEntity?

    @Query("SELECT * FROM task_completions")
    fun getAllCompletions(): Flow<List<TaskCompletionEntity>>

    @Query("SELECT * FROM task_completions WHERE date = :date")
    fun getCompletionsForDate(date: String): Flow<List<TaskCompletionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: TaskEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTasks(tasks: List<TaskEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCompletions(completions: List<TaskCompletionEntity>)

    @Query("SELECT COUNT(*) FROM tasks")
    suspend fun getTaskCount(): Int

    @Update
    suspend fun updateTask(task: TaskEntity)

    @Delete
    suspend fun deleteTask(task: TaskEntity)

    @Query("DELETE FROM tasks WHERE id = :taskId")
    suspend fun deleteTaskById(taskId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCompletion(completion: TaskCompletionEntity)

    @Query("DELETE FROM task_completions WHERE taskId = :taskId AND date = :date")
    suspend fun deleteCompletion(taskId: Long, date: String)

    @Query("DELETE FROM task_completions WHERE taskId = :taskId")
    suspend fun deleteCompletionsForTask(taskId: Long)

    // Category Queries
    @Query("SELECT * FROM categories ORDER BY isDefault DESC, name ASC")
    fun getAllCategories(): Flow<List<CategoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategory(category: CategoryEntity)

    @Query("DELETE FROM categories WHERE name = :name")
    suspend fun deleteCategoryByName(name: String)

    @Query("UPDATE tasks SET category = 'General', colorHex = 4282098422 WHERE category = :categoryName")
    suspend fun reassignCategoryTasks(categoryName: String)

    @Query("UPDATE tasks SET category = :newCategory, colorHex = :newColor WHERE category = :oldCategory")
    suspend fun updateTasksCategory(oldCategory: String, newCategory: String, newColor: Long)

    @Query("UPDATE tasks SET colorHex = :newColor WHERE category = :categoryName")
    suspend fun updateTasksColorByCategory(categoryName: String, newColor: Long)

    // Cleanup and Rollover Queries
    @Query("SELECT * FROM tasks WHERE isRecurring = 0 AND startDate < :todayIso")
    suspend fun getPastNonRecurringTasks(todayIso: String): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE isRecurring = 1")
    suspend fun getAllRecurringTasks(): List<TaskEntity>

    @Query("SELECT DISTINCT taskId FROM task_completions WHERE date < :todayIso")
    suspend fun getPastCompletedTaskIds(todayIso: String): List<Long>

    @Query("DELETE FROM task_completions WHERE date < :todayIso")
    suspend fun deleteCompletionsBefore(todayIso: String)

    @Query("DELETE FROM task_completions WHERE taskId NOT IN (SELECT id FROM tasks)")
    suspend fun cleanOrphanCompletions()
}
