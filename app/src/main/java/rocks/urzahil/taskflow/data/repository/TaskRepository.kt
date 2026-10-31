package rocks.urzahil.taskflow.data.repository

import android.database.sqlite.SQLiteConstraintException
import rocks.urzahil.taskflow.data.db.TaskDao
import rocks.urzahil.taskflow.data.model.AppDate
import rocks.urzahil.taskflow.data.model.CategoryEntity
import rocks.urzahil.taskflow.data.model.SubtaskCompletionEntity
import rocks.urzahil.taskflow.data.model.SubtaskEntity
import rocks.urzahil.taskflow.data.model.SubtaskExclusionEntity
import rocks.urzahil.taskflow.data.model.TaskCompletionEntity
import rocks.urzahil.taskflow.data.model.TaskEntity
import rocks.urzahil.taskflow.data.model.TaskOccurrenceExclusionEntity
import kotlinx.coroutines.flow.Flow

class TaskRepository(private val taskDao: TaskDao) {

    val allTasks: Flow<List<TaskEntity>> = taskDao.getAllTasks()
    val allCompletions: Flow<List<TaskCompletionEntity>> = taskDao.getAllCompletions()
    val allOccurrenceExclusions: Flow<List<TaskOccurrenceExclusionEntity>> =
        taskDao.getAllOccurrenceExclusions()
    val allCategories: Flow<List<CategoryEntity>> = taskDao.getAllCategories()
    val allSubtasks: Flow<List<SubtaskEntity>> = taskDao.getAllSubtasks()
    val allSubtaskCompletions: Flow<List<SubtaskCompletionEntity>> =
        taskDao.getAllSubtaskCompletions()
    val allSubtaskExclusions: Flow<List<SubtaskExclusionEntity>> =
        taskDao.getAllSubtaskExclusions()

    suspend fun insertTask(task: TaskEntity): Long = taskDao.insertTask(task)

    suspend fun insertTasks(tasks: List<TaskEntity>) = taskDao.insertTasks(tasks)

    suspend fun insertCompletions(completions: List<TaskCompletionEntity>) =
        taskDao.insertCompletions(completions)

    suspend fun getTaskCount(): Int = taskDao.getTaskCount()

    suspend fun updateTask(task: TaskEntity) = taskDao.updateTask(task)

    suspend fun deleteTask(task: TaskEntity) = taskDao.deleteTaskById(task.id)

    suspend fun deleteRecurringOccurrence(taskId: Long, dateIso: String) {
        taskDao.deleteOccurrence(taskId, dateIso)
    }

    suspend fun insertCategory(category: CategoryEntity) = taskDao.insertCategory(category)

    /** Adds a category, rejecting names that already exist (case-insensitively). */
    suspend fun addCategory(category: CategoryEntity) = taskDao.addCategoryChecked(category)

    suspend fun insertCategories(categories: List<CategoryEntity>) =
        taskDao.insertCategories(categories)

    suspend fun clearAndRestoreAll(
        tasks: List<TaskEntity>,
        completions: List<TaskCompletionEntity>,
        categories: List<CategoryEntity>,
        occurrenceExclusions: List<TaskOccurrenceExclusionEntity> = emptyList(),
        subtasks: List<SubtaskEntity> = emptyList(),
        subtaskCompletions: List<SubtaskCompletionEntity> = emptyList(),
        subtaskExclusions: List<SubtaskExclusionEntity> = emptyList()
    ) = taskDao.clearAndRestoreAll(
        tasks, completions, categories, occurrenceExclusions, subtasks, subtaskCompletions,
        subtaskExclusions
    )

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
            try {
                taskDao.insertCompletion(TaskCompletionEntity(taskId = taskId, date = dateIso))
            } catch (_: SQLiteConstraintException) {
                // The task was deleted (for example by rollover) while the toggle was in flight.
            }
        }
    }

    suspend fun replaceSharedSubtasks(taskId: Long, subtasks: List<SubtaskEntity>) =
        taskDao.replaceSharedSubtasks(taskId, subtasks, AppDate.today().toIsoString())

    /**
     * A one-time task's subtask is stored undated: rollover moves its start date, so a dated item
     * would be lost.
     */
    suspend fun addOccurrenceSubtask(
        taskId: Long,
        dateIso: String,
        title: String,
        position: Int,
        isRecurring: Boolean
    ) {
        try {
            taskDao.addOccurrenceSubtask(
                SubtaskEntity(
                    taskId = taskId,
                    date = if (isRecurring) dateIso else null,
                    title = title,
                    position = position
                ),
                dateIso
            )
        } catch (_: SQLiteConstraintException) {
            // The task was deleted while the add was in flight.
        }
    }

    suspend fun removeSubtaskOn(taskId: Long, subtaskId: Long, dateIso: String, shared: Boolean) {
        try {
            taskDao.removeSubtaskOn(taskId, subtaskId, dateIso, shared)
        } catch (_: SQLiteConstraintException) {
            // The task or subtask was deleted while the removal was in flight.
        }
    }

    suspend fun toggleSubtask(taskId: Long, subtaskId: Long, dateIso: String) {
        try {
            taskDao.toggleSubtask(taskId, subtaskId, dateIso)
        } catch (_: SQLiteConstraintException) {
            // The task or subtask was deleted while the toggle was in flight.
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
     * Gives a new install the starter categories, in every build type. Does nothing once any
     * category exists, and a restored backup replaces them. Returns true if it added them.
     */
    suspend fun seedInitialCategoriesIfEmpty(): Boolean =
        taskDao.insertCategoriesIfEmpty(STARTER_CATEGORIES)

    /**
     * Seed demo tasks only in debug builds; production installs should not get sample tasks.
     */
    suspend fun seedInitialTasksIfEmpty() {
        if (!rocks.urzahil.taskflow.BuildConfig.DEBUG) return

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

    companion object {
        /** The categories every new install starts with; General is the default. */
        val STARTER_CATEGORIES = listOf(
            CategoryEntity("General", 0xFF3B82F6, "general", isDefault = true),
            CategoryEntity("Work", 0xFF2563EB, "work", isDefault = false),
            CategoryEntity("Personal", 0xFF8B5CF6, "personal", isDefault = false),
            CategoryEntity("Health", 0xFF10B981, "health", isDefault = false),
            CategoryEntity("Fitness", 0xFFF59E0B, "fitness", isDefault = false),
            CategoryEntity("Home", 0xFF059669, "home", isDefault = false),
            CategoryEntity("Study", 0xFF6366F1, "study", isDefault = false)
        )
    }
}
