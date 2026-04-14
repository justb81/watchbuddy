package com.justb81.watchbuddy.phone.auth

import android.util.Log
import com.justb81.watchbuddy.core.network.TokenProxyServiceFactory
import com.justb81.watchbuddy.core.trakt.ProxyRefreshRequest
import com.justb81.watchbuddy.core.trakt.RefreshTokenRequest
import com.justb81.watchbuddy.core.trakt.TokenProxyService
import com.justb81.watchbuddy.core.trakt.TraktApiService
import com.justb81.watchbuddy.phone.settings.SettingsRepository
import com.justb81.watchbuddy.phone.ui.settings.AuthMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

private const val TAG = "TokenRefreshManager"

/**
 * Ensures the app always has a valid Trakt access token.
 *
 * Call [getValidAccessToken] before any authenticated API request. If the stored token is
 * still valid it is returned immediately. If it has expired (or will expire within
 * [REFRESH_BUFFER_MS]) a refresh is attempted via the appropriate backend depending on
 * the user's configured [AuthMode]:
 *
 * - **MANAGED** — calls `POST /trakt/token/refresh` on the WatchBuddy token proxy.
 * - **SELF_HOSTED** — calls the same endpoint on the user-supplied proxy URL.
 * - **DIRECT** — calls `POST /oauth/token` directly on the Trakt API.
 *
 * A [Mutex] serialises concurrent refresh attempts so that only one network call is
 * made even when multiple requests arrive simultaneously with an expired token.
 */
@Singleton
class TokenRefreshManager @Inject constructor(
    private val tokenRepository: TokenRepository,
    private val settingsRepository: SettingsRepository,
    private val traktApi: TraktApiService,
    private val tokenProxy: TokenProxyService?,
    private val tokenProxyServiceFactory: TokenProxyServiceFactory,
    @Named("traktClientId") private val clientId: String
) {
    private val mutex = Mutex()

    /**
     * Returns a valid access token, refreshing it first if it has expired or is about to.
     *
     * Returns `null` when:
     * - no token is stored (user never authenticated), or
     * - no refresh token is available, or
     * - the refresh call fails (network error, revoked token, etc.).
     */
    suspend fun getValidAccessToken(): String? {
        // Fast path — token is valid and not expiring soon; no lock needed.
        if (!tokenRepository.isTokenExpiredOrExpiringSoon(REFRESH_BUFFER_MS)) {
            return tokenRepository.getAccessToken()
        }

        // Slow path — refresh under lock to avoid duplicate refresh calls.
        mutex.withLock {
            // Re-check after acquiring the lock; another coroutine may have already
            // refreshed the token while we were waiting.
            if (!tokenRepository.isTokenExpiredOrExpiringSoon(REFRESH_BUFFER_MS)) {
                return tokenRepository.getAccessToken()
            }

            return performRefresh()
        }
    }

    private suspend fun performRefresh(): String? {
        val refreshToken = tokenRepository.getRefreshToken()
        if (refreshToken.isNullOrBlank()) {
            Log.w(TAG, "No refresh token available — user must re-authenticate")
            return null
        }

        return try {
            val settings = settingsRepository.settings.first()
            val (accessToken, newRefreshToken, expiresIn) = when (settings.authMode) {
                AuthMode.MANAGED -> refreshViaManagedProxy(refreshToken)
                AuthMode.SELF_HOSTED -> refreshViaSelfHostedProxy(refreshToken, settings.backendUrl)
                AuthMode.DIRECT -> refreshViaTraktDirect(refreshToken, settings.directClientId)
            } ?: return null

            tokenRepository.saveTokens(accessToken, newRefreshToken, expiresIn)
            Log.d(TAG, "Token refreshed successfully (mode=${settings.authMode})")
            accessToken
        } catch (e: Exception) {
            Log.e(TAG, "Token refresh failed", e)
            null
        }
    }

    private suspend fun refreshViaManagedProxy(refreshToken: String): Triple<String, String, Int>? {
        val proxy = tokenProxy ?: run {
            Log.w(TAG, "Managed proxy not available — cannot refresh")
            return null
        }
        val response = proxy.refreshToken(ProxyRefreshRequest(refresh_token = refreshToken))
        return Triple(response.access_token, response.refresh_token, response.expires_in)
    }

    private suspend fun refreshViaSelfHostedProxy(
        refreshToken: String,
        backendUrl: String
    ): Triple<String, String, Int>? {
        if (backendUrl.isBlank()) {
            Log.w(TAG, "Self-hosted backend URL not configured — cannot refresh")
            return null
        }
        val proxy = tokenProxyServiceFactory.create(backendUrl)
        val response = proxy.refreshToken(ProxyRefreshRequest(refresh_token = refreshToken))
        return Triple(response.access_token, response.refresh_token, response.expires_in)
    }

    private suspend fun refreshViaTraktDirect(
        refreshToken: String,
        directClientId: String
    ): Triple<String, String, Int>? {
        val effectiveClientId = directClientId.ifBlank { clientId }
        val clientSecret = settingsRepository.getClientSecret()
        if (effectiveClientId.isBlank() || clientSecret.isBlank()) {
            Log.w(TAG, "DIRECT mode: client ID or secret missing — cannot refresh")
            return null
        }
        val response = traktApi.refreshToken(
            RefreshTokenRequest(
                refresh_token = refreshToken,
                client_id = effectiveClientId,
                client_secret = clientSecret
            )
        )
        return Triple(response.access_token, response.refresh_token, response.expires_in)
    }

    companion object {
        /** Refresh the token this many milliseconds before it actually expires. */
        const val REFRESH_BUFFER_MS = 5 * 60 * 1_000L // 5 minutes
    }
}
