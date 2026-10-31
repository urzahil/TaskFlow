package rocks.urzahil.taskflow.data.drive

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import rocks.urzahil.taskflow.data.model.AppDate
import rocks.urzahil.taskflow.data.model.CategoryEntity
import rocks.urzahil.taskflow.data.model.SubtaskCompletionEntity
import rocks.urzahil.taskflow.data.model.SubtaskEntity
import rocks.urzahil.taskflow.data.model.TaskCompletionEntity
import rocks.urzahil.taskflow.data.model.TaskEntity
import rocks.urzahil.taskflow.data.model.TaskOccurrenceExclusionEntity
import rocks.urzahil.taskflow.data.repository.TaskRepository
import rocks.urzahil.taskflow.ui.model.CategoryIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.Locale

data class ParsedBackup(
    val tasks: List<TaskEntity>,
    val completions: List<TaskCompletionEntity>,
    val categories: List<CategoryEntity> = emptyList(),
    val occurrenceExclusions: List<TaskOccurrenceExclusionEntity> = emptyList(),
    val subtasks: List<SubtaskEntity> = emptyList(),
    val subtaskCompletions: List<SubtaskCompletionEntity> = emptyList()
)

data class RestoreResultData(
    val taskCount: Int,
    val categoryCount: Int
)

/** Result of starting to connect Google Drive. */
sealed interface DriveConnectStep {
    data class Connected(val accountEmail: String) : DriveConnectStep

    /** Launch [pendingIntent] so the user can pick an account and approve Drive access. */
    data class NeedsUserConsent(val pendingIntent: PendingIntent) : DriveConnectStep
}

open class GoogleDriveBackupManager(
    private val context: Context,
    private val repository: TaskRepository,
    private val authorization: DriveAuthorization = PlayServicesDriveAuthorization(context)
) {
    private val driveService = GoogleDriveService(authorization)
    private val prefs: SharedPreferences =
        context.getSharedPreferences("taskflow_drive_backup", Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "DriveBackupManager"
        private const val KEY_LAST_BACKUP_TIME = "last_backup_time"
        private const val KEY_LAST_BACKUP_COUNT = "last_backup_count"
        private const val KEY_LAST_BACKUP_CATEGORIES_COUNT = "last_backup_categories_count"
        private const val KEY_AUTO_BACKUP = "auto_backup_enabled"
        private const val KEY_HAS_CHECKED_INSTALL_RESTORE = "has_checked_install_restore"
        private const val KEY_PENDING_BACKUP = "durable_pending_backup"
        private const val KEY_SYNC_CONFLICT = "sync_conflict_pending"
        private const val KEY_ACCOUNT_EMAIL = "connected_account_email"
        private const val KEY_LEGACY_SIGN_IN_MIGRATED = "legacy_sign_in_migrated"

        // Where the removed GoogleSignIn API stored the signed-in account.
        private const val LEGACY_SIGN_IN_PREFS = "com.google.android.gms.signin"
        private const val LEGACY_DEFAULT_ACCOUNT_KEY = "defaultGoogleSignInAccount"
        private const val LEGACY_ACCOUNT_KEY_PREFIX = "googleSignInAccount:"
    }

    /**
     * Builds before this one connected through the GoogleSignIn API (removed in play-services-auth
     * 22), which kept the signed-in account in its own preferences file and removed it on sign-out.
     * If that record is still there, carry the account over once so existing users stay connected;
     * their grant already covers the app-data scope, so no consent screen appears. Users who had
     * signed out have no record and stay disconnected. Safe to delete once no users are left on
     * those builds.
     */
    open suspend fun migrateLegacySignIn() {
        if (prefs.getBoolean(KEY_LEGACY_SIGN_IN_MIGRATED, false)) return
        val legacy = context.getSharedPreferences(LEGACY_SIGN_IN_PREFS, Context.MODE_PRIVATE)
        val legacyId = legacy.getString(LEGACY_DEFAULT_ACCOUNT_KEY, null)
        if (legacyId != null && !isDriveConnected()) {
            val email = try {
                legacyAccountEmail(legacy.getString("$LEGACY_ACCOUNT_KEY_PREFIX$legacyId", null))
                    ?: (authorization.authorize(accountEmail = null) as? DriveAuthorizationOutcome.Granted)
                        ?.let { storeGrant(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Offline or Play Services unavailable: try again on the next launch.
                Log.w(TAG, "Could not carry over the previous Google sign-in", e)
                return
            }
            if (email != null) prefs.edit().putString(KEY_ACCOUNT_EMAIL, email).apply()
        }
        legacy.edit().clear().apply()
        prefs.edit().putBoolean(KEY_LEGACY_SIGN_IN_MIGRATED, true).apply()
    }

    private fun legacyAccountEmail(accountJson: String?): String? = try {
        accountJson?.let { JSONObject(it).optString("email") }?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    /** Email of the Google account backups go to, or null when Drive is not connected. */
    open fun getConnectedAccountEmail(): String? = prefs.getString(KEY_ACCOUNT_EMAIL, null)

    open fun isDriveConnected(): Boolean = getConnectedAccountEmail() != null

    /**
     * Starts connecting Google Drive. Connects straight away when the user already granted access
     * on this device; otherwise returns the consent screen to launch, whose result goes to
     * [completeConnect].
     */
    open suspend fun connect(): DriveConnectStep =
        when (val outcome = authorization.authorize(accountEmail = null)) {
            is DriveAuthorizationOutcome.Granted -> DriveConnectStep.Connected(storeGrant(outcome))
            is DriveAuthorizationOutcome.NeedsUserConsent ->
                DriveConnectStep.NeedsUserConsent(outcome.pendingIntent)
        }

    /** Finishes connecting with the consent screen's result. Returns the connected account's email. */
    open suspend fun completeConnect(data: Intent?): String =
        when (val outcome = authorization.outcomeFromConsentResult(data)) {
            is DriveAuthorizationOutcome.Granted -> storeGrant(outcome)
            is DriveAuthorizationOutcome.NeedsUserConsent ->
                throw IllegalStateException("Google Drive access was not granted")
        }

    private suspend fun storeGrant(grant: DriveAuthorizationOutcome.Granted): String {
        val email = grant.accountEmail
            ?: driveService.fetchAccountEmail(grant.accessToken)
            ?: throw IllegalStateException("Could not tell which Google account was connected")
        prefs.edit().putString(KEY_ACCOUNT_EMAIL, email).apply()
        return email
    }

    /** Forgets the account and revokes TaskFlow's Drive access, so reconnecting asks again. */
    open suspend fun disconnect() {
        val email = getConnectedAccountEmail()
        forgetConnection()
        if (email != null) {
            try {
                authorization.revokeAccess(email)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Still disconnected locally; the grant just stays until the user reconnects.
                Log.w(TAG, "Could not revoke Drive access", e)
            }
        }
    }

    private fun forgetConnection() {
        prefs.edit().remove(KEY_ACCOUNT_EMAIL).putBoolean(KEY_SYNC_CONFLICT, false).apply()
    }

    /** Disconnects locally when Drive no longer accepts this app's access. */
    private fun <T> Result<T>.forgetConnectionIfAccessLost(): Result<T> = onFailure {
        if (it is DriveAccessLostException) forgetConnection()
    }

    private fun notConnected() = Exception("Google Drive is not connected")

    open fun isAutoBackupEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_BACKUP, true)

    open fun setAutoBackupEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_BACKUP, enabled).apply()
    }

    open fun isBackupPendingDurable(): Boolean = prefs.getBoolean(KEY_PENDING_BACKUP, false)

    open fun setBackupPendingDurable(pending: Boolean) {
        prefs.edit().putBoolean(KEY_PENDING_BACKUP, pending).apply()
    }

    /**
     * True after signing in on a device that already has local tasks while a Drive backup
     * exists. Auto-backup stays paused until the user picks which side wins, so a small local
     * data set never silently replaces a larger Drive backup.
     */
    open fun isSyncConflictPending(): Boolean = prefs.getBoolean(KEY_SYNC_CONFLICT, false)

    open fun setSyncConflictPending(pending: Boolean) {
        prefs.edit().putBoolean(KEY_SYNC_CONFLICT, pending).apply()
    }

    /** Flags a conflict when both this device and Drive hold data. Returns true if flagged. */
    open suspend fun detectSyncConflict(): Boolean = withContext(Dispatchers.IO) {
        val email = getConnectedAccountEmail() ?: return@withContext false
        val conflict = repository.getTaskCount() > 0 && driveService.findBackupFile(email) != null
        if (conflict) setSyncConflictPending(true)
        conflict
    }

    open fun getLastBackupTime(): Long = prefs.getLong(KEY_LAST_BACKUP_TIME, 0L)

    open fun getLastBackupCount(): Int = prefs.getInt(KEY_LAST_BACKUP_COUNT, 0)

    open fun getLastBackupCategoriesCount(): Int = prefs.getInt(KEY_LAST_BACKUP_CATEGORIES_COUNT, 0)

    fun getLastBackupTimeFormatted(): String? {
        val time = getLastBackupTime()
        if (time == 0L) return null
        // Locale-aware date and time (e.g. "October 16, 2026 at 14:05" / "16 octobre 2026 à 14:05").
        return DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT, Locale.getDefault())
            .format(Date(time))
    }

    /**
     * Serializes tasks, completions, and categories to JSON string
     */
    fun exportBackupJson(
        tasks: List<TaskEntity>,
        completions: List<TaskCompletionEntity>,
        categories: List<CategoryEntity> = emptyList(),
        exclusions: List<TaskOccurrenceExclusionEntity> = emptyList(),
        subtasks: List<SubtaskEntity> = emptyList(),
        subtaskCompletions: List<SubtaskCompletionEntity> = emptyList()
    ): String {
        val root = JSONObject()
        root.put("version", 4)
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
                put("recurrenceDaysOfWeek", task.recurrenceDaysOfWeek ?: JSONObject.NULL)
                put("startDate", task.startDate)
                put("endDate", task.endDate ?: JSONObject.NULL)
                put("sharedSubtasks", task.sharedSubtasks)
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

        val exclusionsArray = JSONArray()
        exclusions.forEach { exclusion ->
            exclusionsArray.put(JSONObject().apply {
                put("taskId", exclusion.taskId)
                put("date", exclusion.date)
            })
        }
        root.put("recurringTaskExclusions", exclusionsArray)

        val subtasksArray = JSONArray()
        subtasks.forEach { subtask ->
            subtasksArray.put(JSONObject().apply {
                put("id", subtask.id)
                put("taskId", subtask.taskId)
                put("date", subtask.date ?: JSONObject.NULL)
                put("title", subtask.title)
                put("position", subtask.position)
            })
        }
        root.put("subtasks", subtasksArray)

        val subtaskCompletionsArray = JSONArray()
        subtaskCompletions.forEach { comp ->
            subtaskCompletionsArray.put(JSONObject().apply {
                put("subtaskId", comp.subtaskId)
                put("date", comp.date)
                put("completedAt", comp.completedAt)
            })
        }
        root.put("subtaskCompletions", subtaskCompletionsArray)

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
        require(!root.has("app") || root.getString("app") == "TaskFlow") {
            "This file is not a TaskFlow backup"
        }
        require(!root.has("version") || root.getInt("version") in 1..4) {
            "Unsupported backup version"
        }
        // Validate the complete input before the restore transaction can delete local data.
        require(root.optJSONArray("tasks") != null) { "Backup must contain a tasks array" }
        for (key in listOf("completions", "categories", "subtasks", "subtaskCompletions")) {
            require(!root.has(key) || root.optJSONArray(key) != null) {
                "Backup $key must be an array"
            }
        }
        val tasks = mutableListOf<TaskEntity>()
        val taskIds = mutableSetOf<Long>()
        val completions = mutableListOf<TaskCompletionEntity>()
        val categories = mutableListOf<CategoryEntity>()

        val tasksArray = root.optJSONArray("tasks")
        if (tasksArray != null) {
            for (i in 0 until tasksArray.length()) {
                val obj = tasksArray.getJSONObject(i)
                val endDate = if (obj.isNull("endDate")) null else obj.optString("endDate")
                val rawDaysOfWeek =
                    if (obj.isNull("recurrenceDaysOfWeek")) null else obj.optString("recurrenceDaysOfWeek")
                val sanitizedDaysOfWeek = rawDaysOfWeek?.split(",")
                    ?.mapNotNull { it.trim().toIntOrNull() }
                    ?.filter { it in 1..7 }
                    ?.distinct()
                    ?.sorted()
                    ?.takeIf { it.isNotEmpty() }
                    ?.joinToString(",")

                val task = TaskEntity(
                    id = obj.optLong("id", System.currentTimeMillis() + i),
                    title = obj.getString("title"),
                    description = obj.optString("description", ""),
                    category = obj.optString("category", "General"),
                    priority = obj.optString("priority", "Medium"),
                    colorHex = obj.optLong("colorHex", 0xFF3B82F6),
                    isRecurring = obj.optBoolean("isRecurring", false),
                    recurrenceDays = obj.optInt("recurrenceDays", 1),
                    recurrenceDaysOfWeek = sanitizedDaysOfWeek,
                    startDate = obj.optString("startDate", AppDate.today().toIsoString()),
                    endDate = endDate,
                    sharedSubtasks = obj.optBoolean("sharedSubtasks", true),
                    createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                )
                require(task.id > 0 && taskIds.add(task.id)) { "Invalid or duplicate task ID" }
                require(task.title.isNotBlank()) { "Task title must not be empty" }
                require(
                    AppDate.parseIso(task.startDate).toIsoString() == task.startDate
                ) { "Invalid task date" }
                task.endDate?.let {
                    require(
                        AppDate.parseIso(it).toIsoString() == it && it >= task.startDate
                    ) { "Invalid end date" }
                }
                tasks.add(task)
            }
        }

        val recurringTaskIds = tasks.filter { it.isRecurring }.mapTo(HashSet()) { it.id }
        val exclusions = mutableListOf<TaskOccurrenceExclusionEntity>()
        val exclusionsArray = root.optJSONArray("recurringTaskExclusions")
        if (exclusionsArray != null) {
            for (i in 0 until exclusionsArray.length()) {
                val obj = exclusionsArray.getJSONObject(i)
                val exclusion = TaskOccurrenceExclusionEntity(
                    taskId = obj.getLong("taskId"),
                    date = obj.getString("date")
                )
                require(exclusion.taskId in taskIds) { "Occurrence exclusion refers to an unknown task" }
                require(
                    AppDate.parseIso(exclusion.date).toIsoString() == exclusion.date
                ) { "Invalid occurrence exclusion date" }
                require(exclusion.taskId in recurringTaskIds) { "Occurrence exclusion refers to a non-recurring task" }
                exclusions.add(exclusion)
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
                require(comp.taskId in taskIds) { "Completion refers to an unknown task" }
                require(
                    AppDate.parseIso(comp.date).toIsoString() == comp.date
                ) { "Invalid completion date" }
                completions.add(comp)
            }
        }

        val subtasks = mutableListOf<SubtaskEntity>()
        val subtasksArray = root.optJSONArray("subtasks")
        if (subtasksArray != null) {
            for (i in 0 until subtasksArray.length()) {
                val obj = subtasksArray.getJSONObject(i)
                val date = if (obj.isNull("date")) null else obj.getString("date")
                val subtask = SubtaskEntity(
                    id = obj.getLong("id"),
                    taskId = obj.getLong("taskId"),
                    date = date,
                    title = obj.getString("title"),
                    position = obj.optInt("position", 0)
                )
                if (subtask.taskId !in taskIds) continue
                require(date == null || AppDate.parseIso(date).toIsoString() == date) {
                    "Invalid subtask date"
                }
                subtasks.add(subtask)
            }
            require(subtasks.map { it.id }.toSet().size == subtasks.size) { "Duplicate subtask ID" }
        }

        val subtaskIds = subtasks.mapTo(HashSet()) { it.id }
        val subtaskCompletions = mutableListOf<SubtaskCompletionEntity>()
        val subtaskCompsArray = root.optJSONArray("subtaskCompletions")
        if (subtaskCompsArray != null) {
            for (i in 0 until subtaskCompsArray.length()) {
                val obj = subtaskCompsArray.getJSONObject(i)
                val comp = SubtaskCompletionEntity(
                    subtaskId = obj.getLong("subtaskId"),
                    date = obj.getString("date"),
                    completedAt = obj.optLong("completedAt", System.currentTimeMillis())
                )
                if (comp.subtaskId !in subtaskIds) continue
                require(
                    AppDate.parseIso(comp.date).toIsoString() == comp.date
                ) { "Invalid subtask completion date" }
                subtaskCompletions.add(comp)
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
                val generalIndex =
                    categories.indexOfFirst { it.name.equals("General", ignoreCase = true) }
                val targetIndex = if (generalIndex >= 0) generalIndex else 0
                val target = categories[targetIndex]
                categories[targetIndex] = target.copy(isDefault = true)
            }
        }

        return ParsedBackup(tasks, completions, categories, exclusions, subtasks, subtaskCompletions)
    }

    /**
     * Backs up tasks and categories to Google Drive
     */
    open suspend fun backupToDrive(
        tasks: List<TaskEntity>,
        completions: List<TaskCompletionEntity>,
        categories: List<CategoryEntity> = emptyList(),
        exclusions: List<TaskOccurrenceExclusionEntity> = emptyList(),
        subtasks: List<SubtaskEntity> = emptyList(),
        subtaskCompletions: List<SubtaskCompletionEntity> = emptyList()
    ): Result<DriveBackupInfo> = withContext(Dispatchers.IO) {
        val email = getConnectedAccountEmail() ?: return@withContext Result.failure(notConnected())
        val json = exportBackupJson(
            tasks, completions, categories, exclusions, subtasks, subtaskCompletions
        )
        val result = driveService.uploadBackup(email, json).forgetConnectionIfAccessLost()
        result.onSuccess {
            setSyncConflictPending(false)
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
        val email = getConnectedAccountEmail() ?: return@withContext Result.failure(notConnected())
        val downloadResult = driveService.downloadBackup(email).forgetConnectionIfAccessLost()
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
                categories = parsed.categories,
                occurrenceExclusions = parsed.occurrenceExclusions,
                subtasks = parsed.subtasks,
                subtaskCompletions = parsed.subtaskCompletions
            )
            prefs.edit()
                .putLong(KEY_LAST_BACKUP_TIME, System.currentTimeMillis())
                .putInt(KEY_LAST_BACKUP_COUNT, parsed.tasks.size)
                .putInt(KEY_LAST_BACKUP_CATEGORIES_COUNT, parsed.categories.size)
                .putBoolean(KEY_HAS_CHECKED_INSTALL_RESTORE, true)
                .putBoolean(KEY_SYNC_CONFLICT, false)
                .apply()

            Result.success(RestoreResultData(parsed.tasks.size, parsed.categories.size))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Error restoring backup: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Restores tasks and categories from JSON string directly (for local backup import).
     * Clears existing default categories and sample tasks so only backup data remains.
     */
    suspend fun restoreFromJson(json: String): Result<RestoreResultData> =
        withContext(Dispatchers.IO) {
            try {
                val parsed = parseBackupJson(json)
                repository.clearAndRestoreAll(
                    tasks = parsed.tasks,
                    completions = parsed.completions,
                    categories = parsed.categories,
                    occurrenceExclusions = parsed.occurrenceExclusions,
                    subtasks = parsed.subtasks,
                    subtaskCompletions = parsed.subtaskCompletions
                )
                prefs.edit()
                    .putLong(KEY_LAST_BACKUP_TIME, System.currentTimeMillis())
                    .putInt(KEY_LAST_BACKUP_COUNT, parsed.tasks.size)
                    .putInt(KEY_LAST_BACKUP_CATEGORIES_COUNT, parsed.categories.size)
                    .apply()
                Result.success(RestoreResultData(parsed.tasks.size, parsed.categories.size))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Error restoring from JSON: ${e.message}", e)
                Result.failure(e)
            }
        }

    /**
     * Checks if this is a fresh install and auto-restores tasks if a Google Drive backup is found
     */
    suspend fun checkAndAutoRestoreOnInstall(): Result<RestoreResultData?> =
        withContext(Dispatchers.IO) {
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

            val email = getConnectedAccountEmail() ?: return@withContext Result.success(null)
            Log.i(TAG, "Fresh install with Drive connected, checking for a backup...")

            val backupInfo = driveService.findBackupFile(email) ?: run {
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
