package com.example.data.drive

import android.content.Context
import android.util.Log
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class DriveBackupInfo(
    val fileId: String,
    val modifiedTime: String,
    val taskCount: Int,
    val timestamp: Long
)

class GoogleDriveService(private val context: Context) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "GoogleDriveService"
        const val BACKUP_FILENAME = "taskflow_backup.json"
        val SCOPE_FILE = Scope("https://www.googleapis.com/auth/drive.file")
        val SCOPE_APPDATA = Scope("https://www.googleapis.com/auth/drive.appdata")
        private const val OAUTH_SCOPE_STRING = "oauth2:https://www.googleapis.com/auth/drive.file https://www.googleapis.com/auth/drive.appdata"
    }

    fun getGoogleSignInClient(): GoogleSignInClient {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(SCOPE_FILE, SCOPE_APPDATA)
            .build()
        return GoogleSignIn.getClient(context, gso)
    }

    fun getSignedInAccount(): GoogleSignInAccount? {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        return if (account != null && GoogleSignIn.hasPermissions(account, SCOPE_FILE, SCOPE_APPDATA)) {
            account
        } else {
            null
        }
    }

    suspend fun getAccessToken(): String? = withContext(Dispatchers.IO) {
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return@withContext null
        try {
            val androidAccount = account.account ?: return@withContext null
            GoogleAuthUtil.getToken(context, androidAccount, OAUTH_SCOPE_STRING)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting OAuth access token: ${e.message}", e)
            null
        }
    }

    /**
     * Finds existing backup file metadata in Google Drive appDataFolder
     */
    suspend fun findBackupFile(): DriveBackupInfo? = withContext(Dispatchers.IO) {
        val token = getAccessToken() ?: return@withContext null
        try {
            val url = "https://www.googleapis.com/drive/v3/files" +
                    "?spaces=appDataFolder" +
                    "&q=name='$BACKUP_FILENAME' and trashed=false" +
                    "&fields=files(id,name,modifiedTime,size)"

            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Failed searching backup file: code ${response.code}")
                    return@withContext null
                }
                val body = response.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                val files = json.optJSONArray("files") ?: return@withContext null
                if (files.length() > 0) {
                    val fileObj = files.getJSONObject(0)
                    val id = fileObj.getString("id")
                    val modified = fileObj.optString("modifiedTime", "")
                    return@withContext DriveBackupInfo(
                        fileId = id,
                        modifiedTime = modified,
                        taskCount = 0,
                        timestamp = System.currentTimeMillis()
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding backup file: ${e.message}", e)
        }
        null
    }

    /**
     * Uploads tasks backup to Google Drive appDataFolder
     */
    suspend fun uploadBackup(tasksJson: String): Result<DriveBackupInfo> = withContext(Dispatchers.IO) {
        val token = getAccessToken()
            ?: return@withContext Result.failure(Exception("Not signed in or missing Drive permission"))

        val existingFile = findBackupFile()
        try {
            if (existingFile != null) {
                // Update existing file content
                val updateUrl = "https://www.googleapis.com/upload/drive/v3/files/${existingFile.fileId}?uploadType=media"
                val body = tasksJson.toRequestBody("application/json; charset=utf-8".toMediaType())

                val request = Request.Builder()
                    .url(updateUrl)
                    .addHeader("Authorization", "Bearer $token")
                    .patch(body)
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(Exception("Failed updating backup: ${response.code}"))
                    }
                    val respBody = response.body?.string() ?: ""
                    val json = JSONObject(respBody)
                    val id = json.optString("id", existingFile.fileId)
                    Result.success(
                        DriveBackupInfo(
                            fileId = id,
                            modifiedTime = json.optString("modifiedTime", ""),
                            taskCount = countTasks(tasksJson),
                            timestamp = System.currentTimeMillis()
                        )
                    )
                }
            } else {
                // Create new multipart file in appDataFolder
                val uploadUrl = "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart"

                val metadataJson = JSONObject().apply {
                    put("name", BACKUP_FILENAME)
                    put("parents", JSONArray().put("appDataFolder"))
                }.toString()

                val multipartBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "metadata",
                        null,
                        metadataJson.toRequestBody("application/json; charset=UTF-8".toMediaType())
                    )
                    .addFormDataPart(
                        "file",
                        BACKUP_FILENAME,
                        tasksJson.toRequestBody("application/json; charset=UTF-8".toMediaType())
                    )
                    .build()

                val request = Request.Builder()
                    .url(uploadUrl)
                    .addHeader("Authorization", "Bearer $token")
                    .post(multipartBody)
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(Exception("Failed creating backup: ${response.code}"))
                    }
                    val respBody = response.body?.string() ?: ""
                    val json = JSONObject(respBody)
                    val id = json.optString("id", "")
                    Result.success(
                        DriveBackupInfo(
                            fileId = id,
                            modifiedTime = json.optString("modifiedTime", ""),
                            taskCount = countTasks(tasksJson),
                            timestamp = System.currentTimeMillis()
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading backup: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Downloads tasks backup from Google Drive
     */
    suspend fun downloadBackup(): Result<String> = withContext(Dispatchers.IO) {
        val token = getAccessToken()
            ?: return@withContext Result.failure(Exception("Not signed in or missing Drive permission"))

        val existingFile = findBackupFile()
            ?: return@withContext Result.failure(Exception("No backup file found in Google Drive"))

        try {
            val downloadUrl = "https://www.googleapis.com/drive/v3/files/${existingFile.fileId}?alt=media"
            val request = Request.Builder()
                .url(downloadUrl)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("Failed downloading backup: ${response.code}"))
                }
                val content = response.body?.string()
                    ?: return@withContext Result.failure(Exception("Empty backup content"))
                Result.success(content)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading backup: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun countTasks(jsonStr: String): Int {
        return try {
            val root = JSONObject(jsonStr)
            root.optJSONArray("tasks")?.length() ?: 0
        } catch (_: Exception) {
            0
        }
    }
}
