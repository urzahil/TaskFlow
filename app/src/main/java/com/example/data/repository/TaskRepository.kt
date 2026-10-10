package com.example.data.repository

import com.example.data.db.TaskDao
import com.example.data.model.AppDate
import com.example.data.model.CategoryEntity
import com.example.data.model.TaskCompletionEntity
import com.example.data.model.TaskEntity
import com.example.data.model.TaskOccurrenceExclusionEntity
import kotlinx.coroutines.flow.Flow

class TaskRepository(private val taskDao: TaskDao) {

    val allTasks: Flow<List<TaskEntity>> = taskDao.getAllTasks()
    val allCompletions: Flow<List<TaskCompletionEntity>> = taskDao.getAllCompletions()
    val allOccurrenceExclusions: Flow<List<TaskOccurrenceExclusionEntity>> =
        taskDao.getAllOccurrenceExclusions()
    val allCategories: Flow<List<CategoryEntity>> = taskDao.getAllCategories()

    fun getCompletionsForDate(dateIso: String): Flow<List<TaskCompletionEntity>> =
        taskDao.getCompletionsForDate(dateIso)

    suspend fun insertTask(task: TaskEntity): Long = taskDao.insertTask(task)

    suspend fun insertTasks(tasks: List<TaskEntity>) = taskDao.insertTasks(tasks)

    suspend fun insertCompletions(completions: List<TaskCompletionEntity>) =
        taskDao.insertCompletions(completions)

    suspend fun getTaskCount(): Int = taskDao.getTaskCount()

    suspend fun updateTask(task: TaskEntity) = taskDao.updateTask(task)

    suspend fun deleteTask(task: TaskEntity) {
        taskDao.deleteTaskWithCompletions(task.id)
    }

    suspend fun deleteTaskById(taskId: Long) {
        taskDao.deleteTaskWithCompletions(taskId)
    }

    suspend fun deleteRecurringOccurrence(taskId: Long, dateIso: String) {
        taskDao.deleteOccurrence(taskId, dateIso)
    }

    suspend fun insertCategory(category: CategoryEntity) = taskDao.insertCategory(category)

    suspend fun insertCategories(categories: List<CategoryEntity>) =
        taskDao.insertCategories(categories)

    suspend fun clearAndRestoreAll(
        tasks: List<TaskEntity>,
        completions: List<TaskCompletionEntity>,
        categories: List<CategoryEntity>,
        occurrenceExclusions: List<TaskOccurrenceExclusionEntity> = emptyList()
    ) = taskDao.clearAndRestoreAll(tasks, completions, categories, occurrenceExclusions)

    suspend fun updateCategory(
        oldName: String,
        newName: String,
        newColorHex: Long,
        newIconName: String,
        isDefault: Boolean
    ) {
        taskDao.updateCategoryAtomically(
            oldName = oldName,
            newCategory = CategoryEntity(
                name = newName,
                colorHex = newColorHex,
                iconName = newIconName,
                isDefault = isDefault
            ),
            newColor = newColorHex
        )
    }

    suspend fun deleteCategory(categoryName: String) {
        taskDao.deleteCategoryAtomically(categoryName, 0xFF3B82F6)
    }

    suspend fun toggleCompletion(taskId: Long, dateIso: String, currentlyCompleted: Boolean) {
        if (currentlyCompleted) {
            taskDao.deleteCompletion(taskId, dateIso)
        } else {
            taskDao.insertCompletion(TaskCompletionEntity(taskId = taskId, date = dateIso))
        }
    }

    data class RolloverResult(
        val cleanedCount: Int,
        val movedCount: Int,
        val cleanedRecurringOccurrences: Int = 0
    )

    /**
     * Delegates scheduling and rollover rules to a dedicated scheduler component so the repository
     * stays focused on persistence while business rules remain testable and isolated.
     */
    suspend fun cleanupAndRolloverTasks(today: AppDate = AppDate.today()): RolloverResult =
        TaskScheduler.cleanupAndRolloverTasks(taskDao, today)

    fun isTaskScheduledOnDate(task: TaskEntity, targetDate: AppDate): Boolean =
        TaskScheduler.isTaskScheduledOnDate(task, targetDate)

    /**
     * Seed initial categories only in debug builds. Production installs should not silently create
     * demo data on first launch.
     */
    suspend fun seedInitialCategoriesIfEmpty() {
        if (!com.example.BuildConfig.DEBUG) return

        val defaults = listOf(
            CategoryEntity("General", 0xFF3B82F6, "general", isDefault = true),
            CategoryEntity("Work", 0xFF2563EB, "work", isDefault = false),
            CategoryEntity("Personal", 0xFF8B5CF6, "personal", isDefault = false),
            CategoryEntity("Health", 0xFF10B981, "health", isDefault = false),
            CategoryEntity("Fitness", 0xFFF59E0B, "fitness", isDefault = false),
            CategoryEntity("Home", 0xFF059669, "home", isDefault = false),
            CategoryEntity("Study", 0xFF6366F1, "study", isDefault = false)
        )
        defaults.forEach { insertCategory(it) }
    }

    /**
     * Seed initial tasks only in debug builds.
     */
    suspend fun seedInitialTasksIfEmpty() {
        if (!com.example.BuildConfig.DEBUG) return

        val today = AppDate.today()
        val todayIso = today.toIsoString()

        insertTask(
            TaskEntity(
                title = "Morning hydration & vitamins",
                description = "Drink 500ml water and take daily supplements",
                category = "Health",
                priority = "Medium",
                colorHex = 0xFF10B981,
                isRecurring = true,
                recurrenceDays = 1,
                startDate = today.minusDays(2).toIsoString()
            )
        )

        insertTask(
            TaskEntity(
                title = "Team planning & review",
                description = "Go over weekly roadmap and deliverables",
                category = "Work",
                priority = "High",
                colorHex = 0xFF3B82F6,
                isRecurring = false,
                recurrenceDays = 1,
                startDate = todayIso
            )
        )

        insertTask(
            TaskEntity(
                title = "Water indoor plants",
                description = "Check soil moisture for monstera and orchids",
                category = "Home",
                priority = "Low",
                colorHex = 0xFF059669,
                isRecurring = true,
                recurrenceDays = 3,
                startDate = today.toIsoString()
            )
        )

        insertTask(
            TaskEntity(
                title = "Gym workout session",
                description = "Cardio + core strength training",
                category = "Fitness",
                priority = "High",
                colorHex = 0xFFF59E0B,
                isRecurring = true,
                recurrenceDays = 2,
                startDate = today.minusDays(1).toIsoString()
            )
        )

        insertTask(
            TaskEntity(
                title = "Grocery store run",
                description = "Fresh vegetables, olive oil, and coffee beans",
                category = "Personal",
                priority = "Medium",
                colorHex = 0xFF8B5CF6,
                isRecurring = false,
                recurrenceDays = 1,
                startDate = today.plusDays(1).toIsoString()
            )
        )
    }
}
