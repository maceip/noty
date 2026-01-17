package com.example.notificationcapture.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Secure storage for OAuth tokens using EncryptedSharedPreferences.
 * Tokens are encrypted at rest using AES-256-GCM.
 */
class SecureTokenStorage private constructor(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "secure_tokens",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    companion object {
        @Volatile
        private var instance: SecureTokenStorage? = null

        fun get(context: Context): SecureTokenStorage =
            instance ?: synchronized(this) {
                instance ?: SecureTokenStorage(context.applicationContext).also { instance = it }
            }

        private const val KEY_ACCESS_TOKEN = "_access_token"
        private const val KEY_REFRESH_TOKEN = "_refresh_token"
        private const val KEY_EXPIRES_AT = "_expires_at"
        private const val KEY_USER_ID = "_user_id"
        private const val KEY_TEAM_ID = "_team_id"
    }

    /**
     * Store a token securely.
     */
    fun storeToken(
        provider: String,
        accessToken: String,
        refreshToken: String? = null,
        expiresAt: Long? = null,
        userId: String? = null,
        teamId: String? = null
    ) {
        prefs.edit().apply {
            putString(provider + KEY_ACCESS_TOKEN, accessToken)
            refreshToken?.let { putString(provider + KEY_REFRESH_TOKEN, it) }
                ?: remove(provider + KEY_REFRESH_TOKEN)
            expiresAt?.let { putLong(provider + KEY_EXPIRES_AT, it) }
                ?: remove(provider + KEY_EXPIRES_AT)
            userId?.let { putString(provider + KEY_USER_ID, it) }
                ?: remove(provider + KEY_USER_ID)
            teamId?.let { putString(provider + KEY_TEAM_ID, it) }
                ?: remove(provider + KEY_TEAM_ID)
            apply()
        }
    }

    /**
     * Get access token for a provider.
     */
    fun getAccessToken(provider: String): String? =
        prefs.getString(provider + KEY_ACCESS_TOKEN, null)

    /**
     * Get refresh token for a provider.
     */
    fun getRefreshToken(provider: String): String? =
        prefs.getString(provider + KEY_REFRESH_TOKEN, null)

    /**
     * Get token expiry time for a provider.
     */
    fun getExpiresAt(provider: String): Long? {
        val value = prefs.getLong(provider + KEY_EXPIRES_AT, -1L)
        return if (value == -1L) null else value
    }

    /**
     * Check if token exists and is not expired.
     * Returns true if token exists and either has no expiry or hasn't expired.
     */
    fun isTokenValid(provider: String): Boolean {
        val token = getAccessToken(provider) ?: return false
        val expiresAt = getExpiresAt(provider)
        return expiresAt == null || expiresAt > System.currentTimeMillis()
    }

    /**
     * Check if token needs refresh (within 5 minutes of expiry).
     */
    fun needsRefresh(provider: String): Boolean {
        val expiresAt = getExpiresAt(provider) ?: return false
        val refreshBuffer = 5 * 60 * 1000L // 5 minutes
        return System.currentTimeMillis() > (expiresAt - refreshBuffer)
    }

    /**
     * Delete token for a provider.
     */
    fun deleteToken(provider: String) {
        prefs.edit().apply {
            remove(provider + KEY_ACCESS_TOKEN)
            remove(provider + KEY_REFRESH_TOKEN)
            remove(provider + KEY_EXPIRES_AT)
            remove(provider + KEY_USER_ID)
            remove(provider + KEY_TEAM_ID)
            apply()
        }
    }

    /**
     * Get all providers that have tokens stored.
     */
    fun getStoredProviders(): Set<String> {
        return prefs.all.keys
            .filter { it.endsWith(KEY_ACCESS_TOKEN) }
            .map { it.removeSuffix(KEY_ACCESS_TOKEN) }
            .toSet()
    }

    /**
     * Delete all stored tokens.
     */
    fun clearAll() {
        prefs.edit().clear().apply()
    }
}
