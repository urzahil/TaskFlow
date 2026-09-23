package com.example.data.drive

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.data.model.AppDate
import com.example.data.model.TaskCompletionEntity
import com.example.data.model.TaskEntity
import com.example.data.repository.TaskRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GoogleDriveBackupManager(
    private val context: Context,
    private val repository: TaskRepository
) {
    private val driveService = GoogleDriveService(context)
    private val prefs: SharedPreferences = context.getSharedPreferences("taskflow_drive_backup", Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "DriveBackupManager"
        private const val KEY_LAST_BACKUP_TIME = "last_backup_time"
        private const val KEY_LAST_BACKUP_COUNT = "last_backup_count"
        private const val KEY_AUTO_BACKUP = "auto_backup_enabled"
        private const val KEY_HAS_CHECKED_INSTALL_RESTORE = "has_checked_install_restore"
    }

    fun getGoogleSignInClient() = driveService.getGoogleSignInClient()

    fun getSignedInAccount() = driveService.getSignedInAccount()

    fun isAutoBackupEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_BACKUP, true)

    fun setAutoBackupEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_BACKUP, enabled).apply()
    }

    fun getLastBackupTime(): Long = prefs.getLong(KEY_LAST_BACKUP_TIME, 0L)

    fun getLastBackupCount(): Int = prefs.getInt(KEY_LAST_BACKUP_COUNT, 0)

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
     * Serializes tasks and completions to JSON string
     */
    fun exportBackupJson(tasks: List<TaskEntity>, completions: List<TaskCompletionEntity>): String {
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

        return root.toString(2)
    }

    /**
     * Deserializes JSON string to tasks and completions
     */
    fun parseBackupJson(jsonString: String): Pair<List<TaskEntity>, List<TaskCompletionEntity>> {
        val root = JSONObject(jsonString)
        val tasks = mutableListOf<TaskEntity>()
        val completions = mutableListOf<TaskCompletionEntity>()

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

        return Pair(tasks, completions)
    }

    /**
     * Backs up tasks to Google Drive
     */
    suspend fun backupToDrive(
        tasks: List<TaskEntity>,
        completions: List<TaskCompletionEntity>
    ): Result<DriveBackupInfo> = withContext(Dispatchers.IO) {
        val json = exportBackupJson(tasks, completions)
        val result = driveService.uploadBackup(json)
        result.onSuccess { info ->
            prefs.edit()
                .putLong(KEY_LAST_BACKUP_TIME, System.currentTimeMillis())
                .putInt(KEY_LAST_BACKUP_COUNT, tasks.size)
                .apply()
        }
        result
    }

    /**
     * Restores tasks from Google Drive into Room database
     */
    suspend fun restoreFromDrive(): Result<Int> = withContext(Dispatchers.IO) {
        val downloadResult = driveService.downloadBackup()
        if (downloadResult.isFailure) {
            return@withContext Result.failure(
                downloadResult.exceptionOrNull() ?: Exception("Failed downloading backup")
            )
        }

        try {
            val json = downloadResult.getOrThrow()
            val (tasks, completions) = parseBackupJson(json)
            if (tasks.isNotEmpty()) {
                repository.insertTasks(tasks)
            }
            if (completions.isNotEmpty()) {
                repository.insertCompletions(completions)
            }
            prefs.edit()
                .putLong(KEY_LAST_BACKUP_TIME, System.currentTimeMillis())
                .putInt(KEY_LAST_BACKUP_COUNT, tasks.size)
                .putBoolean(KEY_HAS_CHECKED_INSTALL_RESTORE, true)
                .apply()

            Result.success(tasks.size)
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring backup: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Checks if this is a fresh install and auto-restores tasks if a Google Drive backup is found
     */
    suspend fun checkAndAutoRestoreOnInstall(): Result<Int?> = withContext(Dispatchers.IO) {
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
            val count = restoreResult.getOrThrow()
            Result.success(count)
        } else {
            Result.failure(restoreResult.exceptionOrNull() ?: Exception("Auto-restore failed"))
        }
    }
}
