package rocks.urzahil.taskflow.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import rocks.urzahil.taskflow.data.model.CategoryEntity
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

    // Category Queries
    @Query("SELECT * FROM categories ORDER BY isDefault DESC, name ASC")
    fun getAllCategories(): Flow<List<CategoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategory(category: CategoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategories(categories: List<CategoryEntity>)

    @Query("DELETE FROM categories WHERE name = :name")
    suspend fun deleteCategoryByName(name: String)

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
        occurrenceExclusions: List<TaskOccurrenceExclusionEntity> = emptyList()
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
    }
}

class CategoryNameConflictException(val name: String) :
    IllegalArgumentException("A category named \"$name\" already exists")

class LastDefaultCategoryException :
    IllegalStateException("The default category cannot be deleted")
