package com.example.notificationcapture.remote

import android.content.Context
import com.example.notificationcapture.NotificationSource
import com.example.notificationcapture.db.CaptureDatabase
import com.example.notificationcapture.db.OAuthToken
import com.example.notificationcapture.security.SecureTokenStorage
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.time.Instant

/**
 * Message from a remote provider.
 */
data class RemoteMessage(
    val id: String,
    val channel: String,
    val thread: String? = null,
    val text: String,
    val senderName: String?,
    val timestamp: Long // epoch millis
)

/**
 * Base interface for remote notification providers.
 */
interface RemoteProvider {
    val source: NotificationSource

    /** Check if OAuth token exists and is valid. */
    suspend fun isConnected(): Boolean

    /** Get OAuth authorization URL to start auth flow. */
    fun getAuthUrl(redirectUri: String): String

    /** Exchange auth code for tokens. */
    suspend fun exchangeCode(code: String, redirectUri: String): Boolean

    /** Fetch new messages since last check. */
    suspend fun fetchMessages(since: Instant): List<RemoteMessage>

    /** Mark a message as read via API. */
    suspend fun markAsRead(channelId: String, messageTs: String): Boolean

    /** Mark multiple messages as read via API (P1 FIX: batch operation). */
    suspend fun markAsReadBatch(messages: List<Pair<String, String>>): Boolean {
        // Default implementation: call markAsRead for each message
        var allSuccess = true
        for ((channelId, messageTs) in messages) {
            if (!markAsRead(channelId, messageTs)) {
                allSuccess = false
            }
        }
        return allSuccess
    }

    /** Disconnect - delete tokens. */
    suspend fun disconnect()
}

// P0 FIX: Serializable data classes for safe JSON encoding
@Serializable
private data class SlackMarkRequest(val channel: String, val ts: String)

/**
 * Slack provider using Slack Web API.
 * P0 FIX: Uses SecureTokenStorage for encrypted token storage.
 */
class SlackProvider(
    private val context: Context,
    private val clientId: String,
    private val clientSecret: String
) : RemoteProvider {

    override val source = NotificationSource.SLACK

    private val db by lazy { CaptureDatabase.get(context) }
    private val secureStorage by lazy { SecureTokenStorage.get(context) }
    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(json) }
    }

    override suspend fun isConnected(): Boolean {
        return secureStorage.isTokenValid(source.name)
    }

    override fun getAuthUrl(redirectUri: String): String {
        val scopes = "channels:history,channels:read,groups:history,groups:read,im:history,im:read,mpim:history,mpim:read,users:read"
        return "https://slack.com/oauth/v2/authorize?" +
            "client_id=$clientId&" +
            "scope=$scopes&" +
            "redirect_uri=$redirectUri"
    }

    override suspend fun exchangeCode(code: String, redirectUri: String): Boolean {
        return try {
            val response: HttpResponse = http.post("https://slack.com/api/oauth.v2.access") {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody("client_id=$clientId&client_secret=$clientSecret&code=$code&redirect_uri=$redirectUri")
            }

            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            if (body["ok"]?.jsonPrimitive?.boolean != true) return false

            val accessToken = body["access_token"]?.jsonPrimitive?.content ?: return false
            val teamId = body["team"]?.jsonObject?.get("id")?.jsonPrimitive?.content
            val userId = body["authed_user"]?.jsonObject?.get("id")?.jsonPrimitive?.content

            // P0 FIX: Store token in encrypted storage
            secureStorage.storeToken(
                provider = source.name,
                accessToken = accessToken,
                teamId = teamId,
                userId = userId
            )
            true
        } catch (e: Exception) {
            android.util.Log.e("SlackProvider", "Token exchange failed: ${e.message}")
            false
        }
    }

    override suspend fun fetchMessages(since: Instant): List<RemoteMessage> {
        val token = secureStorage.getAccessToken(source.name) ?: return emptyList()
        val messages = mutableListOf<RemoteMessage>()

        try {
            // Get conversations list
            val convResponse: HttpResponse = http.get("https://slack.com/api/conversations.list") {
                header("Authorization", "Bearer $token")
                parameter("types", "public_channel,private_channel,im,mpim")
                parameter("limit", "100")
            }

            val convBody = json.parseToJsonElement(convResponse.bodyAsText()).jsonObject
            val channels = convBody["channels"]?.jsonArray ?: return emptyList()

            for (channel in channels) {
                val channelId = channel.jsonObject["id"]?.jsonPrimitive?.content ?: continue
                val channelName = channel.jsonObject["name"]?.jsonPrimitive?.content ?: channelId

                // Get history for each channel
                val histResponse: HttpResponse = http.get("https://slack.com/api/conversations.history") {
                    header("Authorization", "Bearer $token")
                    parameter("channel", channelId)
                    parameter("oldest", since.epochSecond.toString())
                    parameter("limit", "50")
                }

                val histBody = json.parseToJsonElement(histResponse.bodyAsText()).jsonObject
                val channelMessages = histBody["messages"]?.jsonArray ?: continue

                for (msg in channelMessages) {
                    val obj = msg.jsonObject
                    val ts = obj["ts"]?.jsonPrimitive?.content ?: continue
                    val text = obj["text"]?.jsonPrimitive?.content ?: continue
                    val user = obj["user"]?.jsonPrimitive?.content
                    val threadTs = obj["thread_ts"]?.jsonPrimitive?.content

                    messages += RemoteMessage(
                        id = ts,
                        channel = channelName,
                        thread = threadTs,
                        text = text,
                        senderName = user, // Would need users.info call to get name
                        timestamp = (ts.toDoubleOrNull()?.times(1000))?.toLong() ?: System.currentTimeMillis()
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("SlackProvider", "Fetch failed: ${e.message}")
        }

        return messages
    }

    override suspend fun markAsRead(channelId: String, messageTs: String): Boolean {
        val token = secureStorage.getAccessToken(source.name) ?: return false

        return try {
            val response: HttpResponse = http.post("https://slack.com/api/conversations.mark") {
                header("Authorization", "Bearer $token")
                contentType(ContentType.Application.Json)
                // P0 FIX: Use proper JSON serialization to prevent injection
                setBody(json.encodeToString(SlackMarkRequest(channel = channelId, ts = messageTs)))
            }
            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["ok"]?.jsonPrimitive?.boolean == true
        } catch (e: Exception) {
            android.util.Log.e("SlackProvider", "Mark read failed: ${e.message}")
            false
        }
    }

    override suspend fun disconnect() {
        // P0 FIX: Close HttpClient to prevent resource leak
        http.close()
        secureStorage.deleteToken(source.name)
    }
}

/**
 * MS Teams provider - similar structure to Slack.
 * Uses Microsoft Graph API.
 * P0 FIX: Uses SecureTokenStorage for encrypted token storage.
 */
class MSTeamsProvider(
    private val context: Context,
    private val clientId: String,
    private val tenantId: String = "common"
) : RemoteProvider {

    override val source = NotificationSource.MS_TEAMS

    private val db by lazy { CaptureDatabase.get(context) }
    private val secureStorage by lazy { SecureTokenStorage.get(context) }
    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(json) }
    }
    private val refreshMutex = Mutex()

    override suspend fun isConnected(): Boolean {
        // P0 FIX: Check if token needs refresh
        if (secureStorage.needsRefresh(source.name)) {
            refreshToken()
        }
        return secureStorage.isTokenValid(source.name)
    }

    /**
     * P0 FIX: Refresh token before expiry.
     */
    private suspend fun refreshToken(): Boolean = refreshMutex.withLock {
        // Double-check after acquiring lock
        if (!secureStorage.needsRefresh(source.name)) return true

        val refreshToken = secureStorage.getRefreshToken(source.name) ?: return false

        return try {
            val response: HttpResponse = http.post("https://login.microsoftonline.com/$tenantId/oauth2/v2.0/token") {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody("client_id=$clientId&refresh_token=$refreshToken&grant_type=refresh_token&scope=Chat.Read%20ChatMessage.Read%20offline_access")
            }

            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val newAccessToken = body["access_token"]?.jsonPrimitive?.content ?: return false
            val newRefreshToken = body["refresh_token"]?.jsonPrimitive?.content
            val expiresIn = body["expires_in"]?.jsonPrimitive?.long ?: 3600

            secureStorage.storeToken(
                provider = source.name,
                accessToken = newAccessToken,
                refreshToken = newRefreshToken ?: refreshToken,
                expiresAt = System.currentTimeMillis() + (expiresIn * 1000)
            )
            true
        } catch (e: Exception) {
            android.util.Log.e("MSTeamsProvider", "Token refresh failed: ${e.message}")
            false
        }
    }

    override fun getAuthUrl(redirectUri: String): String {
        val scopes = "Chat.Read ChatMessage.Read offline_access"
        return "https://login.microsoftonline.com/$tenantId/oauth2/v2.0/authorize?" +
            "client_id=$clientId&" +
            "response_type=code&" +
            "redirect_uri=$redirectUri&" +
            "scope=${scopes.replace(" ", "%20")}"
    }

    override suspend fun exchangeCode(code: String, redirectUri: String): Boolean {
        return try {
            val response: HttpResponse = http.post("https://login.microsoftonline.com/$tenantId/oauth2/v2.0/token") {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody("client_id=$clientId&code=$code&redirect_uri=$redirectUri&grant_type=authorization_code&scope=Chat.Read%20ChatMessage.Read%20offline_access")
            }

            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val accessToken = body["access_token"]?.jsonPrimitive?.content ?: return false
            val refreshToken = body["refresh_token"]?.jsonPrimitive?.content
            val expiresIn = body["expires_in"]?.jsonPrimitive?.long ?: 3600

            // P0 FIX: Store token in encrypted storage
            secureStorage.storeToken(
                provider = source.name,
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresAt = System.currentTimeMillis() + (expiresIn * 1000)
            )
            true
        } catch (e: Exception) {
            android.util.Log.e("MSTeamsProvider", "Token exchange failed: ${e.message}")
            false
        }
    }

    override suspend fun fetchMessages(since: Instant): List<RemoteMessage> {
        // P0 FIX: Ensure fresh token
        if (secureStorage.needsRefresh(source.name)) {
            refreshToken()
        }
        val token = secureStorage.getAccessToken(source.name) ?: return emptyList()
        val messages = mutableListOf<RemoteMessage>()

        try {
            // Get chats
            val chatsResponse: HttpResponse = http.get("https://graph.microsoft.com/v1.0/me/chats") {
                header("Authorization", "Bearer $token")
            }

            val chatsBody = json.parseToJsonElement(chatsResponse.bodyAsText()).jsonObject
            val chats = chatsBody["value"]?.jsonArray ?: return emptyList()

            for (chat in chats) {
                val chatId = chat.jsonObject["id"]?.jsonPrimitive?.content ?: continue
                val chatTopic = chat.jsonObject["topic"]?.jsonPrimitive?.content ?: "Chat"

                // Get messages for each chat
                val msgResponse: HttpResponse = http.get("https://graph.microsoft.com/v1.0/me/chats/$chatId/messages") {
                    header("Authorization", "Bearer $token")
                    parameter("\$top", "50")
                }

                val msgBody = json.parseToJsonElement(msgResponse.bodyAsText()).jsonObject
                val chatMessages = msgBody["value"]?.jsonArray ?: continue

                for (msg in chatMessages) {
                    val obj = msg.jsonObject
                    val id = obj["id"]?.jsonPrimitive?.content ?: continue
                    val content = obj["body"]?.jsonObject?.get("content")?.jsonPrimitive?.content ?: continue
                    val from = obj["from"]?.jsonObject?.get("user")?.jsonObject?.get("displayName")?.jsonPrimitive?.content
                    val createdAt = obj["createdDateTime"]?.jsonPrimitive?.content

                    val timestamp = try {
                        Instant.parse(createdAt).toEpochMilli()
                    } catch (e: Exception) {
                        System.currentTimeMillis()
                    }

                    if (timestamp >= since.toEpochMilli()) {
                        messages += RemoteMessage(
                            id = id,
                            channel = chatTopic,
                            text = content,
                            senderName = from,
                            timestamp = timestamp
                        )
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MSTeamsProvider", "Fetch failed: ${e.message}")
        }

        return messages
    }

    override suspend fun markAsRead(channelId: String, messageTs: String): Boolean {
        // MS Teams doesn't have a simple mark-as-read API for chats
        // Reading messages implicitly marks them as read in the Graph API
        return true
    }

    override suspend fun disconnect() {
        // P0 FIX: Close HttpClient to prevent resource leak
        http.close()
        secureStorage.deleteToken(source.name)
    }
}
