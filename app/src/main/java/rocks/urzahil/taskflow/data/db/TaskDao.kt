package rocks.urzahil.taskflow.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import rocks.urzahil.taskflow.data.model.CategoryEntity
import rocks.urzahil.taskflow.data.model.SubtaskCompletionEntity
import rocks.urzahil.taskflow.data.model.SubtaskEntity
import rocks.urzahil.taskflow.data.model.TaskCompletionEntity
import rocks.urzahil.taskflow.data.model.TaskEntity
import rocks.urzahil.taskflow.data.model.TaskOccurrenceExclusionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {

    @Query("SELECT * FROM tasks ORDER BY createdAt DESC")
    fun getAllTasks(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE id = :id LIMIT 1")
    suspend fun getTaskById(id: Long): TaskEntity?

    @Query("SELECT * FROM task_completions")
    fun getAllCompletions(): Flow<List<TaskCompletionEntity>>

    @Query("SELECT * FROM task_occurrence_exclusions")
    fun getAllOccurrenceExclusions(): Flow<List<TaskOccurrenceExclusionEntity>>

    @Query("SELECT * FROM task_occurrence_exclusions")
    suspend fun getAllOccurrenceExclusionsList(): List<TaskOccurrenceExclusionEntity>

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

    @Transaction
    suspend fun deleteOccurrence(taskId: Long, date: String) {
        insertOccurrenceExclusion(TaskOccurrenceExclusionEntity(taskId, date))
        deleteCompletion(taskId, date)
        deleteSubtasksOn(taskId, date)
    }

    /** Completions and occurrence exclusions go with the task (ON DELETE CASCADE). */
    @Query("DELETE FROM tasks WHERE id = :taskId")
    suspend fun deleteTaskById(taskId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCompletion(completion: TaskCompletionEntity)

    @Query("DELETE FROM task_completions WHERE taskId = :taskId AND date = :date")
    suspend fun deleteCompletion(taskId: Long, date: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOccurrenceExclusion(exclusion: TaskOccurrenceExclusionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOccurrenceExclusions(exclusions: List<TaskOccurrenceExclusionEntity>)

    @Query("DELETE FROM task_occurrence_exclusions WHERE date < :todayIso")
    suspend fun deleteOccurrenceExclusionsBefore(todayIso: String)

    // Subtask Queries
    @Query("SELECT * FROM subtasks ORDER BY position, id")
    fun getAllSubtasks(): Flow<List<SubtaskEntity>>

    @Query("SELECT * FROM subtask_completions")
    fun getAllSubtaskCompletions(): Flow<List<SubtaskCompletionEntity>>

    @Query("SELECT * FROM subtasks WHERE taskId = :taskId AND date IS NULL ORDER BY position, id")
    suspend fun getSharedSubtasks(taskId: Long): List<SubtaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSubtask(subtask: SubtaskEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSubtasks(subtasks: List<SubtaskEntity>)

    @Update
    suspend fun updateSubtask(subtask: SubtaskEntity)

    @Query("DELETE FROM subtasks WHERE id = :subtaskId")
    suspend fun deleteSubtask(subtaskId: Long)

    @Query("DELETE FROM subtasks WHERE taskId = :taskId AND date = :date")
    suspend fun deleteSubtasksOn(taskId: Long, date: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSubtaskCompletion(completion: SubtaskCompletionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSubtaskCompletions(completions: List<SubtaskCompletionEntity>)

    @Query("DELETE FROM subtask_completions WHERE subtaskId = :subtaskId AND date = :date")
    suspend fun deleteSubtaskCompletion(subtaskId: Long, date: String)

    @Query("SELECT COUNT(*) FROM subtask_completions WHERE subtaskId = :subtaskId AND date = :date")
    suspend fun countSubtaskCompletion(subtaskId: Long, date: String): Int

    /** The subtasks a task shows on [date]: its shared ones, or that date's own ones. */
    @Query(
        "SELECT s.id FROM subtasks s JOIN tasks t ON t.id = s.taskId " +
            "WHERE s.taskId = :taskId AND " +
            "((t.sharedSubtasks = 1 AND s.date IS NULL) OR (t.sharedSubtasks = 0 AND s.date = :date))"
    )
    suspend fun getShownSubtaskIds(taskId: Long, date: String): List<Long>

    @Query("SELECT COUNT(*) FROM subtask_completions WHERE date = :date AND subtaskId IN (:subtaskIds)")
    suspend fun countSubtaskCompletionsOn(date: String, subtaskIds: List<Long>): Int

    /** Saves [subtasks] as the task's shared list; items with id 0 are new, missing ones are removed. */
    @Transaction
    suspend fun replaceSharedSubtasks(taskId: Long, subtasks: List<SubtaskEntity>) {
        val keptIds = subtasks.map { it.id }.toSet()
        getSharedSubtasks(taskId).filter { it.id !in keptIds }.forEach { deleteSubtask(it.id) }
        subtasks.forEachIndexed { index, subtask ->
            val item = subtask.copy(taskId = taskId, date = null, position = index)
            if (item.id == 0L) insertSubtask(item) else updateSubtask(item)
        }
    }

    /** Flips a subtask's check for [date], then completes or reopens the task to match. */
    @Transaction
    suspend fun toggleSubtask(taskId: Long, subtaskId: Long, date: String) {
        val nowChecked = countSubtaskCompletion(subtaskId, date) == 0
        if (nowChecked) {
            insertSubtaskCompletion(SubtaskCompletionEntity(subtaskId = subtaskId, date = date))
        } else {
            deleteSubtaskCompletion(subtaskId, date)
        }
        val shownIds = getShownSubtaskIds(taskId, date)
        if (nowChecked && shownIds.isNotEmpty() &&
            countSubtaskCompletionsOn(date, shownIds) == shownIds.size
        ) {
            insertCompletion(TaskCompletionEntity(taskId = taskId, date = date))
        } else if (!nowChecked) {
            deleteCompletion(taskId, date)
        }
    }

    // Category Queries
    @Query("SELECT * FROM categories ORDER BY isDefault DESC, name ASC")
    fun getAllCategories(): Flow<List<CategoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategory(category: CategoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategories(categories: List<CategoryEntity>)

    @Query("DELETE FROM categories WHERE name = :name")
    suspend fun deleteCategoryByName(name: String)

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun getCategoryCount(): Int

    /** Inserts [categories] only when there are none yet. Returns true if it inserted them. */
    @Transaction
    suspend fun insertCategoriesIfEmpty(categories: List<CategoryEntity>): Boolean {
        if (getCategoryCount() > 0) return false
        insertCategories(categories)
        return true
    }

    @Query("SELECT * FROM categories WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun findCategoryIgnoreCase(name: String): CategoryEntity?

    @Query(
        "SELECT COUNT(*) FROM categories WHERE name = :name COLLATE NOCASE AND name != :excluding"
    )
    suspend fun countCategoriesNamedExcluding(name: String, excluding: String): Int

    @Query("SELECT * FROM categories WHERE isDefault = 1 AND name != :excluding ORDER BY name LIMIT 1")
    suspend fun getDefaultCategoryExcluding(excluding: String): CategoryEntity?

    @Transaction
    suspend fun addCategoryChecked(category: CategoryEntity) {
        if (findCategoryIgnoreCase(category.name) != null) {
            throw CategoryNameConflictException(category.name)
        }
        insertCategory(category)
    }

    @Transaction
    suspend fun updateCategoryAtomically(
        oldName: String,
        newCategory: CategoryEntity,
        newColor: Long
    ) {
        // Category names are the primary key, so a rename onto another existing name
        // (case-insensitively) would silently merge or replace that category.
        if (oldName != newCategory.name &&
            countCategoriesNamedExcluding(newCategory.name, oldName) > 0
        ) {
            throw CategoryNameConflictException(newCategory.name)
        }
        insertCategory(newCategory)
        if (oldName == newCategory.name) {
            updateTasksColorByCategory(newCategory.name, newColor)
        } else {
            updateTasksCategory(oldName, newCategory.name, newColor)
            deleteCategoryByName(oldName)
        }
    }

    /**
     * Deletes a category and moves its tasks to the default category (or to a freshly created
     * "General" when no other default exists), taking over that category's colour.
     */
    @Transaction
    suspend fun deleteCategoryAtomically(categoryName: String, fallbackColor: Long) {
        var target = getDefaultCategoryExcluding(categoryName)
        if (target == null) {
            if (categoryName.equals("General", ignoreCase = true)) {
                throw LastDefaultCategoryException()
            }
            target = findCategoryIgnoreCase("General")
                ?: CategoryEntity("General", fallbackColor, "general", isDefault = true)
                    .also { insertCategory(it) }
        }
        updateTasksCategory(categoryName, target.name, target.colorHex)
        deleteCategoryByName(categoryName)
    }

    @Query("UPDATE tasks SET category = :newCategory, colorHex = :newColor WHERE category = :oldCategory")
    suspend fun updateTasksCategory(oldCategory: String, newCategory: String, newColor: Long)

    @Query("UPDATE tasks SET colorHex = :newColor WHERE category = :categoryName")
    suspend fun updateTasksColorByCategory(categoryName: String, newColor: Long)

    // Cleanup and Rollover Queries
    @Query("SELECT * FROM tasks WHERE isRecurring = 0 AND startDate < :todayIso")
    suspend fun getPastNonRecurringTasks(todayIso: String): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE isRecurring = 0 AND id IN (SELECT DISTINCT taskId FROM task_completions)")
    suspend fun getCompletedNonRecurringTasks(): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE isRecurring = 1")
    suspend fun getAllRecurringTasks(): List<TaskEntity>

    @Query("SELECT * FROM task_completions")
    suspend fun getAllCompletionsList(): List<TaskCompletionEntity>

    @Query("DELETE FROM task_completions WHERE date < :todayIso")
    suspend fun deleteCompletionsBefore(todayIso: String)

    /** Completions and occurrence exclusions go with the tasks (ON DELETE CASCADE). */
    @Delete
    suspend fun deleteTasks(tasks: List<TaskEntity>)

    @Query("UPDATE tasks SET startDate = :newStartDate WHERE id = :taskId")
    suspend fun updateTaskStartDate(taskId: Long, newStartDate: String)

    @Transaction
    suspend fun performCleanupAndRolloverBatch(
        tasksToDelete: List<TaskEntity>,
        tasksToUpdate: List<TaskEntity>,
        todayIso: String
    ) {
        // Re-check completed one-off tasks inside the same transaction that applies the
        // cleanup. A completion may have been committed after the scheduler's initial read;
        // without this check, a stale rollover candidate could move that finished task.
        val completedNonRecurringTasks = getCompletedNonRecurringTasks()
        val effectiveTasksToDelete =
            (tasksToDelete + completedNonRecurringTasks).distinctBy { it.id }
        val deletedTaskIds = effectiveTasksToDelete.map { it.id }.toSet()

        if (effectiveTasksToDelete.isNotEmpty()) {
            deleteTasks(effectiveTasksToDelete)
        }
        // Never apply a stale date update to a task deleted by the final completion check.
        for (task in tasksToUpdate) {
            if (task.id !in deletedTaskIds) {
                updateTaskStartDate(task.id, task.startDate)
            }
        }
        deleteCompletionsBefore(todayIso)
        deleteOccurrenceExclusionsBefore(todayIso)
    }

    /** Completions and occurrence exclusions go with the tasks (ON DELETE CASCADE). */
    @Query("DELETE FROM tasks")
    suspend fun deleteAllTasks()

    @Query("DELETE FROM categories")
    suspend fun deleteAllCategories()

    @Transaction
    suspend fun clearAndRestoreAll(
        tasks: List<TaskEntity>,
        completions: List<TaskCompletionEntity>,
        categories: List<CategoryEntity>,
        occurrenceExclusions: List<TaskOccurrenceExclusionEntity> = emptyList(),
        subtasks: List<SubtaskEntity> = emptyList(),
        subtaskCompletions: List<SubtaskCompletionEntity> = emptyList()
    ) {
        deleteAllTasks()
        deleteAllCategories()
        if (categories.isNotEmpty()) {
            insertCategories(categories)
        }
        if (tasks.isNotEmpty()) {
            insertTasks(tasks)
        }
        if (completions.isNotEmpty()) {
            insertCompletions(completions)
        }
        if (occurrenceExclusions.isNotEmpty()) {
            insertOccurrenceExclusions(occurrenceExclusions)
        }
        if (subtasks.isNotEmpty()) {
            insertSubtasks(subtasks)
        }
        if (subtaskCompletions.isNotEmpty()) {
            insertSubtaskCompletions(subtaskCompletions)
        }
    }
}

class CategoryNameConflictException(val name: String) :
    IllegalArgumentException("A category named \"$name\" already exists")

class LastDefaultCategoryException :
    IllegalStateException("The default category cannot be deleted")
