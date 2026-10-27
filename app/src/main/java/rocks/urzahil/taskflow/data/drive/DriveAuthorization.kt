package rocks.urzahil.taskflow.data.drive

import android.accounts.Account
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.tasks.await

/** The only Drive permission TaskFlow needs: its own hidden app-data folder. */
val DRIVE_APPDATA_SCOPE = Scope("https://www.googleapis.com/auth/drive.appdata")

/** Outcome of asking Google for Drive access. */
sealed interface DriveAuthorizationOutcome {
    /** Access is granted; [accountEmail] is null when the account has to be looked up from Drive. */
    data class Granted(val accessToken: String, val accountEmail: String?) : DriveAuthorizationOutcome

    /** The user has to pick an account or approve access; launch [pendingIntent] to ask them. */
    data class NeedsUserConsent(val pendingIntent: PendingIntent) : DriveAuthorizationOutcome
}

/** Google authorization for Drive, kept behind an interface so tests need no Play Services. */
interface DriveAuthorization {
    /** Requests [DRIVE_APPDATA_SCOPE], for [accountEmail] when given, without showing any UI. */
    suspend fun authorize(accountEmail: String?): DriveAuthorizationOutcome

    /** Reads the outcome of the consent screen launched from [DriveAuthorizationOutcome.NeedsUserConsent]. */
    fun outcomeFromConsentResult(data: Intent?): DriveAuthorizationOutcome

    /** Drops a cached token that Drive rejected, so the next [authorize] fetches a fresh one. */
    suspend fun clearToken(accessToken: String)

    /** Revokes TaskFlow's Drive access for [accountEmail]. */
    suspend fun revokeAccess(accountEmail: String)
}

/** [DriveAuthorization] backed by the Google Identity Services AuthorizationClient. */
class PlayServicesDriveAuthorization(context: Context) : DriveAuthorization {

    private val appContext = context.applicationContext
    private val client by lazy { Identity.getAuthorizationClient(appContext) }

    override suspend fun authorize(accountEmail: String?): DriveAuthorizationOutcome {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(DRIVE_APPDATA_SCOPE))
            .apply { accountEmail?.let { setAccount(googleAccount(it)) } }
            .build()
        return client.authorize(request).await().toOutcome()
    }

    override fun outcomeFromConsentResult(data: Intent?): DriveAuthorizationOutcome =
        client.getAuthorizationResultFromIntent(data).toOutcome()

    override suspend fun clearToken(accessToken: String) {
        client.clearToken(ClearTokenRequest.builder().setToken(accessToken).build()).await()
    }

    override suspend fun revokeAccess(accountEmail: String) {
        val request = RevokeAccessRequest.builder()
            .setAccount(googleAccount(accountEmail))
            .setScopes(listOf(DRIVE_APPDATA_SCOPE))
            .build()
        client.revokeAccess(request).await()
    }

    private fun AuthorizationResult.toOutcome(): DriveAuthorizationOutcome {
        val pendingIntent = pendingIntent
        if (hasResolution() && pendingIntent != null) {
            return DriveAuthorizationOutcome.NeedsUserConsent(pendingIntent)
        }
        val token = accessToken
            ?: throw IllegalStateException("Google did not return a Drive access token")
        // The result names the account only through the deprecated toGoogleSignInAccount();
        // the backup manager asks Drive for the account's email instead.
        return DriveAuthorizationOutcome.Granted(token, accountEmail = null)
    }

    private fun googleAccount(email: String) = Account(email, "com.google")
}
