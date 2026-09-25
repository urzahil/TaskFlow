package com.example.data.repository

import com.example.data.db.TaskDao
import com.example.data.model.AppDate
import com.example.data.model.CategoryEntity
import com.example.data.model.TaskCompletionEntity
import com.example.data.model.TaskEntity
import kotlinx.coroutines.flow.Flow

class TaskRepository(private val taskDao: TaskDao) {

    val allTasks: Flow<List<TaskEntity>> = taskDao.getAllTasks()
    val allCompletions: Flow<List<TaskCompletionEntity>> = taskDao.getAllCompletions()
    val allCategories: Flow<List<CategoryEntity>> = taskDao.getAllCategories()

    fun getCompletionsForDate(dateIso: String): Flow<List<TaskCompletionEntity>> =
        taskDao.getCompletionsForDate(dateIso)

    suspend fun insertTask(task: TaskEntity): Long = taskDao.insertTask(task)

    suspend fun insertTasks(tasks: List<TaskEntity>) = taskDao.insertTasks(tasks)

    suspend fun insertCompletions(completions: List<TaskCompletionEntity>) = taskDao.insertCompletions(completions)

    suspend fun getTaskCount(): Int = taskDao.getTaskCount()

    suspend fun updateTask(task: TaskEntity) = taskDao.updateTask(task)

    suspend fun deleteTask(task: TaskEntity) {
        taskDao.deleteCompletionsForTask(task.id)
        taskDao.deleteTask(task)
    }

    suspend fun deleteTaskById(taskId: Long) {
        taskDao.deleteCompletionsForTask(taskId)
        taskDao.deleteTaskById(taskId)
    }

    suspend fun insertCategory(category: CategoryEntity) = taskDao.insertCategory(category)

    suspend fun insertCategories(categories: List<CategoryEntity>) = taskDao.insertCategories(categories)

    suspend fun clearAndRestoreAll(
        tasks: List<TaskEntity>,
        completions: List<TaskCompletionEntity>,
        categories: List<CategoryEntity>
    ) = taskDao.clearAndRestoreAll(tasks, completions, categories)

    suspend fun updateCategory(
        oldName: String,
        newName: String,
        newColorHex: Long,
        newIconName: String,
        isDefault: Boolean
    ) {
        if (oldName == newName) {
            taskDao.insertCategory(
                CategoryEntity(
                    name = newName,
                    colorHex = newColorHex,
                    iconName = newIconName,
                    isDefault = isDefault
                )
            )
            taskDao.updateTasksColorByCategory(newName, newColorHex)
        } else {
            taskDao.insertCategory(
                CategoryEntity(
                    name = newName,
                    colorHex = newColorHex,
                    iconName = newIconName,
                    isDefault = isDefault
                )
            )
            taskDao.updateTasksCategory(
                oldCategory = oldName,
                newCategory = newName,
                newColor = newColorHex
            )
            taskDao.deleteCategoryByName(oldName)
        }
    }

    suspend fun deleteCategory(categoryName: String) {
        taskDao.reassignCategoryTasks(categoryName)
        taskDao.deleteCategoryByName(categoryName)
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
     * Cleans completed tasks from past days, rolls uncompleted past tasks forward to [today],
     * and deletes old occurrences of recurring tasks before [today].
     */
    suspend fun cleanupAndRolloverTasks(today: AppDate = AppDate.today()): RolloverResult {
        val todayIso = today.toIsoString()
        val pastTasks = taskDao.getPastNonRecurringTasks(todayIso)
        val completedTaskIds = taskDao.getPastCompletedTaskIds(todayIso).toSet()

        var cleanedCount = 0
        var movedCount = 0
        var cleanedRecurringOccurrences = 0

        val tasksToDelete = mutableListOf<TaskEntity>()
        val tasksToUpdate = mutableListOf<TaskEntity>()
        val taskIdsForCompletionDeletion = mutableListOf<Long>()

        // 1. Clean completed non-recurring tasks and roll forward uncompleted tasks
        for (task in pastTasks) {
            if (task.id in completedTaskIds) {
                // Task was completed in the past: clean it up
                tasksToDelete.add(task)
                taskIdsForCompletionDeletion.add(task.id)
                cleanedCount++
            } else {
                // Task was uncompleted in the past: move it forward to today
                tasksToUpdate.add(task.copy(startDate = todayIso))
                movedCount++
            }
        }

        // 2. Delete old occurrences of recurring tasks by advancing their startDate to today or next occurrence
        val recurringTasks = taskDao.getAllRecurringTasks()
        for (task in recurringTasks) {
            val start = try {
                AppDate.parseIso(task.startDate)
            } catch (_: Exception) {
                null
            } ?: continue

            // If the recurring task has an end date that already passed, delete the expired task completely
            if (!task.endDate.isNullOrBlank()) {
                val end = try { AppDate.parseIso(task.endDate) } catch (_: Exception) { null }
                if (end != null && end < today) {
                    tasksToDelete.add(task)
                    taskIdsForCompletionDeletion.add(task.id)
                    cleanedCount++
                    continue
                }
            }

            // If start date is before today, delete old occurrences by moving start date to the first occurrence on or after today
            if (start < today) {
                val interval = if (task.recurrenceDays > 0) task.recurrenceDays else 1
                val diffDays = today.daysBetween(start)
                val remainder = diffDays % interval
                val daysToNext = if (remainder == 0L) 0L else (interval - remainder)
                val nextOccurrence = today.plusDays(daysToNext)

                // Count past occurrences being removed
                val pastDaysCount = today.minusDays(1).daysBetween(start)
                if (pastDaysCount >= 0) {
                    cleanedRecurringOccurrences += (pastDaysCount / interval + 1).toInt()
                }

                // If next occurrence is past optional end date, recurring task is completed
                if (!task.endDate.isNullOrBlank()) {
                    val end = try { AppDate.parseIso(task.endDate) } catch (_: Exception) { null }
                    if (end != null && nextOccurrence > end) {
                        tasksToDelete.add(task)
                        taskIdsForCompletionDeletion.add(task.id)
                        cleanedCount++
                        continue
                    }
                }

                tasksToUpdate.add(task.copy(startDate = nextOccurrence.toIsoString()))
            }
        }

        // 3. Apply all deletions, updates, past completions removal, and orphan cleanup in one atomic transaction
        taskDao.performCleanupAndRolloverBatch(
            tasksToDelete = tasksToDelete,
            tasksToUpdate = tasksToUpdate,
            taskIdsForCompletionDeletion = taskIdsForCompletionDeletion,
            todayIso = todayIso
        )

        return RolloverResult(
            cleanedCount = cleanedCount,
            movedCount = movedCount,
            cleanedRecurringOccurrences = cleanedRecurringOccurrences
        )
    }

    /**
     * Determines whether a given task is scheduled to occur on [targetDate].
     */
    fun isTaskScheduledOnDate(task: TaskEntity, targetDate: AppDate): Boolean {
        val start = try {
            AppDate.parseIso(task.startDate)
        } catch (_: Exception) {
            return false
        }

        if (!task.isRecurring) {
            return task.startDate == targetDate.toIsoString()
        }

        // Recurring task: must be on or after start date
        if (targetDate < start) {
            return false
        }

        // Check optional end date
        if (!task.endDate.isNullOrBlank()) {
            val end = try {
                AppDate.parseIso(task.endDate)
            } catch (_: Exception) {
                null
            }
            if (end != null && targetDate > end) {
                return false
            }
        }

        val interval = if (task.recurrenceDays > 0) task.recurrenceDays else 1
        val diffDays = targetDate.daysBetween(start)
        return (diffDays % interval) == 0L
    }

    /**
     * Seed initial categories if categories table is empty.
     */
    suspend fun seedInitialCategoriesIfEmpty() {
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
     * Seed initial tasks if database is empty.
     */
    suspend fun seedInitialTasksIfEmpty() {
        val today = AppDate.today()
        val todayIso = today.toIsoString()

        // 1. Daily recurring task
        insertTask(
            TaskEntity(
                title = "Morning hydration & vitamins",
                description = "Drink 500ml water and take daily supplements",
                category = "Health",
                priority = "Medium",
                colorHex = 0xFF10B981, // Emerald
                isRecurring = true,
                recurrenceDays = 1,
                startDate = today.minusDays(2).toIsoString()
            )
        )

        // 2. Simple task for today
        insertTask(
            TaskEntity(
                title = "Team planning & review",
                description = "Go over weekly roadmap and deliverables",
                category = "Work",
                priority = "High",
                colorHex = 0xFF3B82F6, // Blue
                isRecurring = false,
                recurrenceDays = 1,
                startDate = todayIso
            )
        )

        // 3. Recurring every 3 days
        insertTask(
            TaskEntity(
                title = "Water indoor plants",
                description = "Check soil moisture for monstera and orchids",
                category = "Home",
                priority = "Low",
                colorHex = 0xFF059669, // Green
                isRecurring = true,
                recurrenceDays = 3,
                startDate = today.toIsoString()
            )
        )

        // 4. Recurring every 2 days
        insertTask(
            TaskEntity(
                title = "Gym workout session",
                description = "Cardio + core strength training",
                category = "Fitness",
                priority = "High",
                colorHex = 0xFFF59E0B, // Amber
                isRecurring = true,
                recurrenceDays = 2,
                startDate = today.minusDays(1).toIsoString()
            )
        )

        // 5. Simple task tomorrow
        insertTask(
            TaskEntity(
                title = "Grocery store run",
                description = "Fresh vegetables, olive oil, and coffee beans",
                category = "Personal",
                priority = "Medium",
                colorHex = 0xFF8B5CF6, // Purple
                isRecurring = false,
                recurrenceDays = 1,
                startDate = today.plusDays(1).toIsoString()
            )
        )
    }
}
