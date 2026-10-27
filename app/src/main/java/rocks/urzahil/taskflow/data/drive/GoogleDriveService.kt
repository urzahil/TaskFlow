package rocks.urzahil.taskflow.data.drive

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class DriveBackupInfo(
    val fileId: String,
    val modifiedTime: String
)

/** Drive no longer accepts TaskFlow's access for the connected account; the user must reconnect. */
class DriveAccessLostException :
    IllegalStateException("Google Drive access was removed. Reconnect Google Drive in Settings.")

/**
 * Talks to the Drive REST API inside the app-data folder. Every call is made for an explicit
 * account and gets its access token from [authorization].
 */
class GoogleDriveService(private val authorization: DriveAuthorization) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "GoogleDriveService"
        const val BACKUP_FILENAME = "taskflow_backup.json"
        private const val FILES_URL = "https://www.googleapis.com/drive/v3/files"
        private const val UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }

    /** Holds one operation's access token, so a multi-request operation asks for it once. */
    private inner class Session(private val accountEmail: String) {
        private var token: String? = null

        suspend fun token(): String = token ?: requestToken().also { token = it }

        /** Forgets a token Drive rejected; the next [token] call fetches a new one. */
        suspend fun discardToken() {
            token?.let { runCatching { authorization.clearToken(it) } }
            token = null
        }

        private suspend fun requestToken(): String =
            when (val outcome = authorization.authorize(accountEmail)) {
                is DriveAuthorizationOutcome.Granted -> outcome.accessToken
                is DriveAuthorizationOutcome.NeedsUserConsent -> throw DriveAccessLostException()
            }

        /** Runs a request, retrying once with a fresh token if Drive answers 401. */
        suspend fun <T> execute(build: Request.Builder.() -> Unit, handle: (Response) -> T): T {
            for (attempt in 0..1) {
                val request = Request.Builder()
                    .header("Authorization", "Bearer ${token()}")
                    .apply(build)
                    .build()
                val response = httpClient.newCall(request).execute()
                if (response.code == 401 && attempt == 0) {
                    response.close()
                    discardToken()
                    continue
                }
                return response.use(handle)
            }
            throw IllegalStateException("Unreachable")
        }
    }

    /**
     * Email address of the account [accessToken] belongs to. Used when Play Services does not
     * report it alongside the grant.
     */
    suspend fun fetchAccountEmail(accessToken: String): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/about?fields=user(emailAddress)")
            .header("Authorization", "Bearer $accessToken")
            .build()
        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                JSONObject(response.body.string()).optJSONObject("user")
                    ?.optString("emailAddress")?.takeIf { it.isNotBlank() }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Could not read the Drive account email", e)
            null
        }
    }

    /** Finds the backup's metadata, or null when there is none or Drive could not be reached. */
    suspend fun findBackupFile(accountEmail: String): DriveBackupInfo? = withContext(Dispatchers.IO) {
        try {
            Session(accountEmail).lookUpBackupFile()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error finding backup file: ${e.message}", e)
            null
        }
    }

    /** Throws when the lookup fails, so callers never mistake an error for "no backup yet". */
    private suspend fun Session.lookUpBackupFile(): DriveBackupInfo? = execute(
        build = {
            url(
                "$FILES_URL?spaces=appDataFolder" +
                    "&q=name='$BACKUP_FILENAME' and trashed=false" +
                    "&orderBy=modifiedTime desc" +
                    "&fields=files(id,modifiedTime)"
            )
        }
    ) { response ->
        if (!response.isSuccessful) throw IOException("Failed searching backup file: ${response.code}")
        val files = JSONObject(response.body.string()).optJSONArray("files")
        if (files == null || files.length() == 0) return@execute null
        val file = files.getJSONObject(0)
        DriveBackupInfo(fileId = file.getString("id"), modifiedTime = file.optString("modifiedTime", ""))
    }

    /** Uploads the backup to the app-data folder, replacing the existing backup file if there is one. */
    suspend fun uploadBackup(accountEmail: String, tasksJson: String): Result<DriveBackupInfo> =
        withContext(Dispatchers.IO) {
            try {
                val session = Session(accountEmail)
                val existingFile = session.lookUpBackupFile()
                val info = if (existingFile != null) {
                    session.execute(
                        build = {
                            url("$UPLOAD_URL/${existingFile.fileId}?uploadType=media")
                            patch(tasksJson.toRequestBody(JSON))
                        }
                    ) { response ->
                        if (!response.isSuccessful) throw IOException("Failed updating backup: ${response.code}")
                        val json = JSONObject(response.body.string())
                        DriveBackupInfo(
                            fileId = json.optString("id", existingFile.fileId),
                            modifiedTime = json.optString("modifiedTime", "")
                        )
                    }
                } else {
                    val metadataJson = JSONObject().apply {
                        put("name", BACKUP_FILENAME)
                        put("parents", JSONArray().put("appDataFolder"))
                    }.toString()
                    val multipartBody = MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("metadata", null, metadataJson.toRequestBody(JSON))
                        .addFormDataPart("file", BACKUP_FILENAME, tasksJson.toRequestBody(JSON))
                        .build()
                    session.execute(
                        build = {
                            url("$UPLOAD_URL?uploadType=multipart")
                            post(multipartBody)
                        }
                    ) { response ->
                        if (!response.isSuccessful) throw IOException("Failed creating backup: ${response.code}")
                        val json = JSONObject(response.body.string())
                        DriveBackupInfo(
                            fileId = json.optString("id", ""),
                            modifiedTime = json.optString("modifiedTime", "")
                        )
                    }
                }
                Result.success(info)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error uploading backup: ${e.message}", e)
                Result.failure(e)
            }
        }

    /** Downloads the backup JSON from the app-data folder. */
    suspend fun downloadBackup(accountEmail: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val session = Session(accountEmail)
            val existingFile = session.lookUpBackupFile()
                ?: return@withContext Result.failure(Exception("No backup file found in Google Drive"))
            val content = session.execute(
                build = { url("$FILES_URL/${existingFile.fileId}?alt=media") }
            ) { response ->
                if (!response.isSuccessful) throw IOException("Failed downloading backup: ${response.code}")
                // Bounded read: a corrupt or hostile Drive file must not exhaust memory.
                BackupFileReader.readJson(response.body.byteStream())
            }
            Result.success(content)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading backup: ${e.message}", e)
            Result.failure(e)
        }
    }
}
