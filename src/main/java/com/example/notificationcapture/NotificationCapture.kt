package com.example.notificationcapture

import android.content.Context
import androidx.work.*
import com.example.notificationcapture.db.CaptureDatabase
import com.example.notificationcapture.db.CapturedNotification
import com.example.notificationcapture.db.Config
import com.example.notificationcapture.remote.*
import com.example.notificationcapture.service.CaptureService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Main entry point for the notification capture system.
 * Initialize in Application.onCreate().
 */
class NotificationCapture private constructor(private val context: Context) {

    private val db = CaptureDatabase.get(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val providers = mutableMapOf<NotificationSource, RemoteProvider>()

    // P0 FIX: Mutex for thread-safe poll time updates
    private val pollMutex = Mutex()
    private var lastPollTime = Instant.now()

    companion object {
        @Volatile private var instance: NotificationCapture? = null

        fun init(context: Context): NotificationCapture =
            instance ?: synchronized(this) {
                instance ?: NotificationCapture(context.applicationContext).also { instance = it }
            }

        fun get(): NotificationCapture =
            instance ?: throw IllegalStateException("NotificationCapture not initialized. Call init() first.")
    }

    // ============== SERVICE STATE ==============

    /** Whether the NotificationListenerService is connected. */
    val isServiceRunning: StateFlow<Boolean> = CaptureService.isRunning

    /** Real-time pipeline events. */
    val events: SharedFlow<PipelineResult> = CaptureService.events

    // ============== NOTIFICATIONS ==============

    /** All captured notifications, newest first. */
    val notifications: Flow<List<CapturedNotification>> = db.dao.allNotifications()

    /** Notifications filtered by source. */
    fun bySource(source: NotificationSource): Flow<List<CapturedNotification>> =
        db.dao.bySource(source.name)

    /** Notifications filtered by type. */
    fun byType(type: NotificationType): Flow<List<CapturedNotification>> =
        db.dao.byType(type.name)

    /** Count of all notifications (optimized - doesn't load all rows). */
    val count: Flow<Int> = db.dao.notificationCount()

    /** Suspend/resume state. */
    val isSuspended: StateFlow<Boolean> = CaptureService.isSuspended

    /** Set suspend state. */
    fun setSuspended(suspended: Boolean) {
        CaptureService.setSuspended(suspended)
    }

    // ============== CONFIGURATION ==============

    /** Global configuration. */
    val globalConfig: Flow<Config?> = db.dao.globalConfig()

    /** Update global config. */
    suspend fun updateGlobalConfig(
        blockedTerms: String? = null,
        ignorePatterns: String? = null,
        ignoreRepeatSec: Int = 10
    ) {
        db.dao.upsertConfig(Config(
            key = "global",
            blockedTerms = blockedTerms,
            ignorePatterns = ignorePatterns,
            ignoreRepeatSec = ignoreRepeatSec
        ))
    }

    /** Update per-app config. */
    suspend fun updateAppConfig(
        packageName: String,
        enabled: Boolean = true,
        blockedTerms: String? = null,
        skipIfRemote: Boolean = true
    ) {
        db.dao.upsertConfig(Config(
            key = packageName,
            enabled = enabled,
            blockedTerms = blockedTerms,
            skipIfRemote = skipIfRemote
        ))
    }

    // ============== REMOTE PROVIDERS ==============

    /**
     * Register a remote provider. Call this during app init for each supported service.
     */
    fun registerProvider(provider: RemoteProvider) {
        providers[provider.source] = provider
    }

    /**
     * Get OAuth URL to start authentication flow.
     */
    fun getAuthUrl(source: NotificationSource, redirectUri: String): String? =
        providers[source]?.getAuthUrl(redirectUri)

    /**
     * Complete OAuth flow with auth code.
     */
    suspend fun completeAuth(source: NotificationSource, code: String, redirectUri: String): Boolean =
        providers[source]?.exchangeCode(code, redirectUri) ?: false

    /**
     * Check if a remote provider is connected.
     */
    suspend fun isProviderConnected(source: NotificationSource): Boolean =
        providers[source]?.isConnected() ?: false

    /**
     * Disconnect a remote provider.
     */
    suspend fun disconnectProvider(source: NotificationSource) {
        providers[source]?.disconnect()
    }

    /**
     * Get all connected remote providers.
     */
    val connectedProviders: Flow<Set<NotificationSource>> = db.dao.allTokens()
        .map { tokens -> tokens.mapNotNull { NotificationSource.entries.find { s -> s.name == it.provider } }.toSet() }

    /**
     * Manually poll all connected remote providers.
     * P0 FIX: Uses mutex to prevent race conditions on lastPollTime.
     * P1 FIX: Batches mark-as-read operations.
     */
    suspend fun pollRemoteProviders() {
        // P0 FIX: Atomic read-modify-write of lastPollTime
        val since = pollMutex.withLock {
            val s = lastPollTime
            lastPollTime = Instant.now()
            s
        }

        for ((source, provider) in providers) {
            if (!provider.isConnected()) continue

            try {
                val messages = provider.fetchMessages(since)
                val toMarkRead = mutableListOf<Pair<String, String>>()

                for (msg in messages) {
                    // Collect for batch mark-as-read
                    toMarkRead.add(msg.channel to msg.id)

                    db.dao.insert(CapturedNotification(
                        key = "${source.name}:${msg.id}",
                        source = source.name,
                        packageName = source.name,
                        type = NotificationType.MESSAGE.name,
                        postTime = msg.timestamp,
                        title = msg.channel,
                        text = msg.text,
                        senderName = msg.senderName,
                        remoteChannel = msg.channel,
                        remoteThread = msg.thread,
                        contentHash = "${source.name}:${msg.id}".hashCode().toString(16)
                    ))
                }

                // P1 FIX: Batch mark-as-read operation
                if (toMarkRead.isNotEmpty()) {
                    provider.markAsReadBatch(toMarkRead)
                }
            } catch (e: Exception) {
                android.util.Log.e("NotificationCapture", "Poll failed for ${source.name}: ${e.message}")
            }
        }
    }

    /**
     * Schedule periodic polling with WorkManager.
     */
    fun schedulePolling(intervalMinutes: Long = 5) {
        val request = PeriodicWorkRequestBuilder<PollWorker>(intervalMinutes, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build())
            .build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork("remote-poll", ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /**
     * Cancel scheduled polling.
     */
    fun cancelPolling() {
        WorkManager.getInstance(context).cancelUniqueWork("remote-poll")
    }

    // ============== CLEANUP ==============

    /**
     * Delete notifications older than specified days.
     */
    suspend fun deleteOlderThan(days: Int) {
        val cutoff = System.currentTimeMillis() - (days * 24 * 60 * 60 * 1000L)
        db.dao.deleteOlderThan(cutoff)
    }
}

/**
 * WorkManager worker for periodic remote polling.
 */
class PollWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            NotificationCapture.get().pollRemoteProviders()
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}

