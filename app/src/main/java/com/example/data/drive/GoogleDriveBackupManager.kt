package com.example.data.drive

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.data.model.AppDate
import com.example.data.model.CategoryEntity
import com.example.data.model.TaskCompletionEntity
import com.example.data.model.TaskEntity
import com.example.data.repository.TaskRepository
import com.example.ui.model.CategoryIcons
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ParsedBackup(
    val tasks: List<TaskEntity>,
    val completions: List<TaskCompletionEntity>,
    val categories: List<CategoryEntity> = emptyList()
)

data class RestoreResultData(
    val taskCount: Int,
    val categoryCount: Int
)

open class GoogleDriveBackupManager(
    private val context: Context,
    private val repository: TaskRepository
) {
    private val driveService = GoogleDriveService(context)
    private val prefs: SharedPreferences = context.getSharedPreferences("taskflow_drive_backup", Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "DriveBackupManager"
        private const val KEY_LAST_BACKUP_TIME = "last_backup_time"
        private const val KEY_LAST_BACKUP_COUNT = "last_backup_count"
        private const val KEY_LAST_BACKUP_CATEGORIES_COUNT = "last_backup_categories_count"
        private const val KEY_AUTO_BACKUP = "auto_backup_enabled"
        private const val KEY_HAS_CHECKED_INSTALL_RESTORE = "has_checked_install_restore"
    }

    open fun getGoogleSignInClient() = driveService.getGoogleSignInClient()

    open fun getSignedInAccount(): GoogleSignInAccount? = driveService.getSignedInAccount()

    open fun isAutoBackupEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_BACKUP, true)

    open fun setAutoBackupEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_BACKUP, enabled).apply()
    }

    open fun getLastBackupTime(): Long = prefs.getLong(KEY_LAST_BACKUP_TIME, 0L)

    open fun getLastBackupCount(): Int = prefs.getInt(KEY_LAST_BACKUP_COUNT, 0)

    open fun getLastBackupCategoriesCount(): Int = prefs.getInt(KEY_LAST_BACKUP_CATEGORIES_COUNT, 0)

    fun getLastBackupTimeFormatted(): String? {
        val time = getLastBackupTime()
        if (time == 0L) return null
        val date = Date(time)
        val day = SimpleDateFormat("d", Locale.getDefault()).format(date)
        val month = SimpleDateFormat("MMMM", Locale.getDefault()).format(date)
        val year = SimpleDateFormat("yyyy", Locale.getDefault()).format(date)
        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
        return "$day $month $year at $timeStr"
    }

    /**
     * Serializes tasks, completions, and categories to JSON string
     */
    fun exportBackupJson(
        tasks: List<TaskEntity>,
        completions: List<TaskCompletionEntity>,
        categories: List<CategoryEntity> = emptyList()
    ): String {
        val root = JSONObject()
        root.put("version", 2)
        root.put("app", "TaskFlow")
        root.put("timestamp", System.currentTimeMillis())

        val tasksArray = JSONArray()
        tasks.forEach { task ->
            val taskObj = JSONObject().apply {
                put("id", task.id)
                put("title", task.title)
                put("description", task.description)
                put("category", task.category)
                put("priority", task.priority)
                put("colorHex", task.colorHex)
                put("isRecurring", task.isRecurring)
                put("recurrenceDays", task.recurrenceDays)
                put("startDate", task.startDate)
                put("endDate", task.endDate ?: JSONObject.NULL)
                put("createdAt", task.createdAt)
            }
            tasksArray.put(taskObj)
        }
        root.put("tasks", tasksArray)

        val completionsArray = JSONArray()
        completions.forEach { comp ->
            val compObj = JSONObject().apply {
                put("taskId", comp.taskId)
                put("date", comp.date)
                put("completedAt", comp.completedAt)
            }
            completionsArray.put(compObj)
        }
        root.put("completions", completionsArray)

        val categoriesArray = JSONArray()
        categories.forEach { cat ->
            val catObj = JSONObject().apply {
                put("name", cat.name)
                put("colorHex", cat.colorHex)
                put("iconName", cat.iconName)
                put("isDefault", cat.isDefault)
            }
            categoriesArray.put(catObj)
        }
        root.put("categories", categoriesArray)

        return root.toString(2)
    }

    /**
     * Deserializes JSON string to tasks, completions, and custom categories
     */
    fun parseBackupJson(jsonString: String): ParsedBackup {
        val root = JSONObject(jsonString)
        val tasks = mutableListOf<TaskEntity>()
        val completions = mutableListOf<TaskCompletionEntity>()
        val categories = mutableListOf<CategoryEntity>()

        val tasksArray = root.optJSONArray("tasks")
        if (tasksArray != null) {
            for (i in 0 until tasksArray.length()) {
                val obj = tasksArray.getJSONObject(i)
                val endDate = if (obj.isNull("endDate")) null else obj.optString("endDate", null)
                val task = TaskEntity(
                    id = obj.optLong("id", System.currentTimeMillis() + i),
                    title = obj.getString("title"),
                    description = obj.optString("description", ""),
                    category = obj.optString("category", "General"),
                    priority = obj.optString("priority", "Medium"),
                    colorHex = obj.optLong("colorHex", 0xFF6750A4),
                    isRecurring = obj.optBoolean("isRecurring", false),
                    recurrenceDays = obj.optInt("recurrenceDays", 1),
                    startDate = obj.optString("startDate", AppDate.today().toIsoString()),
                    endDate = endDate,
                    createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                )
                tasks.add(task)
            }
        }

        val compsArray = root.optJSONArray("completions")
        if (compsArray != null) {
            for (i in 0 until compsArray.length()) {
                val obj = compsArray.getJSONObject(i)
                val comp = TaskCompletionEntity(
                    taskId = obj.getLong("taskId"),
                    date = obj.getString("date"),
                    completedAt = obj.optLong("completedAt", System.currentTimeMillis())
                )
                completions.add(comp)
            }
        }

        val categoriesArray = root.optJSONArray("categories")
        if (categoriesArray != null && categoriesArray.length() > 0) {
            for (i in 0 until categoriesArray.length()) {
                val obj = categoriesArray.getJSONObject(i)
                val name = obj.optString("name", "").trim()
                if (name.isNotEmpty()) {
                    val cat = CategoryEntity(
                        name = name,
                        colorHex = obj.optLong("colorHex", 0xFF3B82F6),
                        iconName = obj.optString("iconName", "general"),
                        isDefault = obj.optBoolean("isDefault", false)
                    )
                    categories.add(cat)
                }
            }
        }

        // Also check tasks for any categories (e.g. backward compatibility for backups without 'categories'
        // or tasks having custom categories that were not in the categories array)
        val existingNames = categories.map { it.name.lowercase().trim() }.toMutableSet()
        for (task in tasks) {
            val catName = task.category.trim()
            if (catName.isNotEmpty() && !existingNames.contains(catName.lowercase())) {
                existingNames.add(catName.lowercase())
                categories.add(
                    CategoryEntity(
                        name = catName,
                        colorHex = task.colorHex,
                        iconName = CategoryIcons.suggestIconForName(catName),
                        isDefault = (catName.equals("General", ignoreCase = true))
                    )
                )
            }
        }

        // Ensure at least one category exists and at least one is default
        if (categories.isEmpty()) {
            categories.add(CategoryEntity("General", 0xFF3B82F6, "general", isDefault = true))
        } else {
            val hasDefault = categories.any { it.isDefault }
            if (!hasDefault) {
                val generalIndex = categories.indexOfFirst { it.name.equals("General", ignoreCase = true) }
                val targetIndex = if (generalIndex >= 0) generalIndex else 0
                val target = categories[targetIndex]
                categories[targetIndex] = target.copy(isDefault = true)
            }
        }

        return ParsedBackup(tasks, completions, categories)
    }

    /**
     * Backs up tasks and categories to Google Drive
     */
    open suspend fun backupToDrive(
        tasks: List<TaskEntity>,
        completions: List<TaskCompletionEntity>,
        categories: List<CategoryEntity> = emptyList()
    ): Result<DriveBackupInfo> = withContext(Dispatchers.IO) {
        val json = exportBackupJson(tasks, completions, categories)
        val result = driveService.uploadBackup(json)
        result.onSuccess { info ->
            prefs.edit()
                .putLong(KEY_LAST_BACKUP_TIME, System.currentTimeMillis())
                .putInt(KEY_LAST_BACKUP_COUNT, tasks.size)
                .putInt(KEY_LAST_BACKUP_CATEGORIES_COUNT, categories.size)
                .apply()
        }
        result
    }

    /**
     * Restores tasks and custom categories from Google Drive into Room database.
     * Clears existing default categories and sample tasks so only backup data remains.
     */
    open suspend fun restoreFromDrive(): Result<RestoreResultData> = withContext(Dispatchers.IO) {
        val downloadResult = driveService.downloadBackup()
        if (downloadResult.isFailure) {
            return@withContext Result.failure(
                downloadResult.exceptionOrNull() ?: Exception("Failed downloading backup")
            )
        }

        try {
            val json = downloadResult.getOrThrow()
            val parsed = parseBackupJson(json)
            repository.clearAndRestoreAll(
                tasks = parsed.tasks,
                completions = parsed.completions,
                categories = parsed.categories
            )
            prefs.edit()
                .putLong(KEY_LAST_BACKUP_TIME, System.currentTimeMillis())
                .putInt(KEY_LAST_BACKUP_COUNT, parsed.tasks.size)
                .putInt(KEY_LAST_BACKUP_CATEGORIES_COUNT, parsed.categories.size)
                .putBoolean(KEY_HAS_CHECKED_INSTALL_RESTORE, true)
                .apply()

            Result.success(RestoreResultData(parsed.tasks.size, parsed.categories.size))
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring backup: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Restores tasks and categories from JSON string directly (for local backup import).
     * Clears existing default categories and sample tasks so only backup data remains.
     */
    suspend fun restoreFromJson(json: String): Result<RestoreResultData> = withContext(Dispatchers.IO) {
        try {
            val parsed = parseBackupJson(json)
            repository.clearAndRestoreAll(
                tasks = parsed.tasks,
                completions = parsed.completions,
                categories = parsed.categories
            )
            prefs.edit()
                .putLong(KEY_LAST_BACKUP_TIME, System.currentTimeMillis())
                .putInt(KEY_LAST_BACKUP_COUNT, parsed.tasks.size)
                .putInt(KEY_LAST_BACKUP_CATEGORIES_COUNT, parsed.categories.size)
                .apply()
            Result.success(RestoreResultData(parsed.tasks.size, parsed.categories.size))
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring from JSON: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Checks if this is a fresh install and auto-restores tasks if a Google Drive backup is found
     */
    suspend fun checkAndAutoRestoreOnInstall(): Result<RestoreResultData?> = withContext(Dispatchers.IO) {
        val alreadyChecked = prefs.getBoolean(KEY_HAS_CHECKED_INSTALL_RESTORE, false)
        val currentCount = repository.getTaskCount()

        // If local tasks already exist, do not auto-overwrite without user intent
        if (currentCount > 0) {
            prefs.edit().putBoolean(KEY_HAS_CHECKED_INSTALL_RESTORE, true).apply()
            return@withContext Result.success(null)
        }

        // If user already had restore checked on this install, don't repeat
        if (alreadyChecked) {
            return@withContext Result.success(null)
        }

        val account = driveService.getSignedInAccount() ?: return@withContext Result.success(null)
        Log.i(TAG, "Fresh install detected with account ${account.email}, checking Drive backup...")

        val backupInfo = driveService.findBackupFile() ?: run {
            prefs.edit().putBoolean(KEY_HAS_CHECKED_INSTALL_RESTORE, true).apply()
            return@withContext Result.success(null)
        }

        Log.i(TAG, "Found Drive backup (${backupInfo.fileId}), auto-restoring tasks...")
        val restoreResult = restoreFromDrive()
        if (restoreResult.isSuccess) {
            val res = restoreResult.getOrThrow()
            Result.success(res)
        } else {
            Result.failure(restoreResult.exceptionOrNull() ?: Exception("Auto-restore failed"))
        }
    }
}
