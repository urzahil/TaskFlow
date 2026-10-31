package rocks.urzahil.taskflow.data.drive

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

object GoogleOAuthHelper {
    fun shareBackupFile(context: Context, jsonContent: String, fileName: String): Boolean {
        return try {
            // Matches the narrow cache-path in file_paths.xml; old shared backups are removed first.
            val backupDir = File(context.cacheDir, "backups").apply { mkdirs() }
            backupDir.listFiles()?.forEach { it.delete() }
            val cacheFile = File(backupDir, fileName)
            cacheFile.writeText(jsonContent)
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                cacheFile
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "TaskFlow Tasks Backup")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(shareIntent, "Save or Share Backup")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
            true
        } catch (e: Exception) {
            Log.e("GoogleOAuthHelper", "Failed to share backup file", e)
            false
        }
    }
}
