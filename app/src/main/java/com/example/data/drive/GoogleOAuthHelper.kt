package com.example.data.drive

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest

object GoogleOAuthHelper {
    const val PROJECT_ID = "gen-lang-client-0510488616"
    const val PROJECT_NUMBER = "111332636166"
    const val OAUTH_CLIENT_ID = "111332636166-5ksmhkmc9goo5o5qhcffhfp3avh4fs8k.apps.googleusercontent.com"
    const val FALLBACK_SHA1 = "45:43:F6:D5:D3:1D:21:BA:C1:4E:CE:71:B6:9A:D3:EF:C7:FA:74:B7"
    const val FALLBACK_SHA256 = "4A:90:99:CD:31:04:0C:B2:D4:5E:32:9D:55:72:52:ED:CF:8B:53:96:BF:4E:88:DF:04:A3:FC:69:2C:DC:18:DD"

    fun getSigningCertificateFingerprint(context: Context, algorithm: String = "SHA-1"): String {
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
            val certBytes = signatures?.firstOrNull()?.toByteArray()
                ?: return if (algorithm.equals("SHA-256", ignoreCase = true)) FALLBACK_SHA256 else FALLBACK_SHA1
            val md = MessageDigest.getInstance(algorithm)
            val digest = md.digest(certBytes)
            digest.joinToString(":") { "%02X".format(it) }
        } catch (_: Exception) {
            if (algorithm.equals("SHA-256", ignoreCase = true)) FALLBACK_SHA256 else FALLBACK_SHA1
        }
    }

    fun shareBackupFile(context: Context, jsonContent: String, fileName: String): Boolean {
        return try {
            val cacheFile = File(context.cacheDir, fileName)
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
            false
        }
    }
}
