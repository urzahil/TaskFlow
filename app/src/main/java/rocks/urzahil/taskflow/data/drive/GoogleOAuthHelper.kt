package rocks.urzahil.taskflow.data.drive

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest

object GoogleOAuthHelper {
    /**
     * Colon-separated hex digest of the app's signing certificate, or null when it cannot be read.
     * Shown in the sign-in error 10 guide so self-builders can register their OAuth client.
     */
    fun getSigningCertificateFingerprint(context: Context, algorithm: String = "SHA-1"): String? {
        return try {
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val signingInfo = context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                ).signingInfo
                if (signingInfo?.hasMultipleSigners() == true) {
                    signingInfo.apkContentsSigners
                } else {
                    signingInfo?.signingCertificateHistory
                }
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNATURES
                ).signatures
            }
            val certBytes = signatures?.firstOrNull()?.toByteArray() ?: return null
            MessageDigest.getInstance(algorithm).digest(certBytes)
                .joinToString(":") { "%02X".format(it) }
        } catch (e: Exception) {
            Log.w("GoogleOAuthHelper", "Could not read signing certificate", e)
            null
        }
    }

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
