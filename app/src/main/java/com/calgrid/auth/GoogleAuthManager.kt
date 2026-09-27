package com.calgrid.auth

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.Scopes
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class NotAuthorizedException : Exception("Google Tasks access is not granted")

/**
 * Authorization for the Google Tasks scope via Google Identity Services.
 * Once the user granted access in the UI, [accessToken] works silently from background workers.
 */
class GoogleAuthManager(private val context: Context) {

    private val client = Identity.getAuthorizationClient(context)

    private val request = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(TASKS_SCOPE), Scope(Scopes.EMAIL)))
        .build()

    /** May return a result that needs a UI resolution ([AuthorizationResult.hasResolution]). */
    suspend fun authorize(): AuthorizationResult = client.authorize(request).await()

    fun resultFromIntent(data: Intent?): AuthorizationResult =
        client.getAuthorizationResultFromIntent(data)

    /** Returns a valid access token or throws [NotAuthorizedException] if the user must act in the UI. */
    suspend fun accessToken(): String {
        val result = try {
            authorize()
        } catch (e: Exception) {
            throw NotAuthorizedException().initCause(e)
        }
        if (result.hasResolution()) throw NotAuthorizedException()
        return result.accessToken ?: throw NotAuthorizedException()
    }

    /** Drops a token the server rejected so the next [accessToken] fetches a fresh one. */
    suspend fun invalidate(token: String) = withContext(Dispatchers.IO) {
        runCatching { GoogleAuthUtil.clearToken(context, token) }
    }

    companion object {
        const val TASKS_SCOPE = "https://www.googleapis.com/auth/tasks"

        @Suppress("DEPRECATION")
        fun emailOf(result: AuthorizationResult): String? = result.toGoogleSignInAccount()?.email
    }
}
