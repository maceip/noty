@file:Suppress("DEPRECATION")

package com.example.notificationcapture.service

import android.app.Notification
import android.app.Person
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.annotation.RequiresApi
import java.util.concurrent.ConcurrentHashMap
import com.example.notificationcapture.*
import com.example.notificationcapture.db.CaptureDatabase
import com.example.notificationcapture.db.CapturedNotification
import com.example.notificationcapture.db.NotificationAction
import com.example.notificationcapture.db.LogEventType
import com.example.notificationcapture.db.LogMetadata
import com.example.notificationcapture.db.StructuredLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.Collections

class CaptureService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var db: CaptureDatabase
    private lateinit var pipeline: NotificationPipeline
    private lateinit var logger: StructuredLogger

    // Track connected remote providers
    private val _connectedProviders = MutableStateFlow<Set<NotificationSource>>(emptySet())
    val connectedProviders: StateFlow<Set<NotificationSource>> = _connectedProviders.asStateFlow()

    // P1 FIX: Use ConcurrentHashMap with compute() for atomic operations
    private val lastUpdate = ConcurrentHashMap<String, Long>()
    private val throttleMs = 200L
    private val maxThrottleEntries = 500

    // Device state for filtering
    private lateinit var audioManager: AudioManager
    private lateinit var powerManager: PowerManager
    private lateinit var telephonyManager: TelephonyManager
    private var screenOn = true
    @Volatile private var isInCall = false

    // P0 FIX: Phone state listener for tracking call state
    private var phoneStateListener: PhoneStateListener? = null
    private var telephonyCallback: TelephonyCallback? = null

    companion object {
        private const val TAG = "CaptureService"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        // P1 FIX: Add BufferOverflow.DROP_OLDEST to prevent unbounded buffering
        private val _events = MutableSharedFlow<PipelineResult>(
            extraBufferCapacity = 64,
            onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
        )
        val events: SharedFlow<PipelineResult> = _events.asSharedFlow()

        // Suspend/Resume toggle
        private val _isSuspended = MutableStateFlow(false)
        val isSuspended: StateFlow<Boolean> = _isSuspended.asStateFlow()

        fun setSuspended(suspended: Boolean) {
            _isSuspended.value = suspended
        }

        // P2 FIX: Cached MessageDigest per thread
        private val digestThreadLocal = ThreadLocal.withInitial {
            MessageDigest.getInstance("SHA-256")
        }
        private val HEX_CHARS = "0123456789abcdef".toCharArray()
    }

    override fun onCreate() {
        super.onCreate()
        db = CaptureDatabase.get(applicationContext)
        logger = StructuredLogger.get(applicationContext, scope)
        logger.serviceCreated()

        // Initialize device state managers
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        screenOn = powerManager.isInteractive

        // Register screen state receiver
        // P0 FIX: Add RECEIVER_NOT_EXPORTED flag for Android 14+
        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, screenFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenReceiver, screenFilter)
        }

        // P0 FIX: Register phone state listener for isInCall tracking
        registerPhoneStateListener()

        pipeline = createPipeline()

        // Forward pipeline results to static flow
        scope.launch {
            pipeline.results.collect { _events.emit(it) }
        }

        // Load connected providers from DB
        scope.launch {
            db.dao.allTokens().collect { tokens ->
                _connectedProviders.value = tokens
                    .mapNotNull { NotificationSource.entries.find { s -> s.name == it.provider } }
                    .toSet()
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            screenOn = intent?.action == Intent.ACTION_SCREEN_ON
        }
    }

    /**
     * P0 FIX: Register phone state listener to track call state.
     */
    private fun registerPhoneStateListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ uses TelephonyCallback
            telephonyCallback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    isInCall = state != TelephonyManager.CALL_STATE_IDLE
                }
            }
            try {
                telephonyManager.registerTelephonyCallback(
                    mainExecutor,
                    telephonyCallback!!
                )
            } catch (e: SecurityException) {
                Log.w(TAG, "Cannot register telephony callback: ${e.message}")
            }
        } else {
            // Pre-Android 12 uses PhoneStateListener
            @Suppress("DEPRECATION")
            phoneStateListener = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    isInCall = state != TelephonyManager.CALL_STATE_IDLE
                }
            }
            try {
                @Suppress("DEPRECATION")
                telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
            } catch (e: SecurityException) {
                Log.w(TAG, "Cannot register phone state listener: ${e.message}")
            }
        }
    }

    /**
     * P0 FIX: Unregister phone state listener.
     */
    private fun unregisterPhoneStateListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyCallback?.let {
                try {
                    telephonyManager.unregisterTelephonyCallback(it)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to unregister telephony callback: ${e.message}")
                }
            }
        } else {
            phoneStateListener?.let {
                try {
                    @Suppress("DEPRECATION")
                    telephonyManager.listen(it, PhoneStateListener.LISTEN_NONE)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to unregister phone state listener: ${e.message}")
                }
            }
        }
    }

    private fun createPipeline(): NotificationPipeline {
        val p = NotificationPipeline()

        // 1. Suspend check - skip all if service is suspended
        p.addHandler("suspend-check") { ctx ->
            if (_isSuspended.value) {
                ctx.skip("Service suspended")
                logger.handlerSkipped(ctx.sbn?.key ?: "unknown", "suspend-check", "Service suspended")
                return@addHandler false
            }
            true
        }

        // 2. Skip if remote provider is connected for this package
        p.addHandler("remote-dedupe", RemoteDedupeHandler { _connectedProviders.value })

        // 3. Skip group summary notifications
        p.addHandler("group-summary", GroupSummaryHandler())

        // 4. Skip system category notifications
        p.addHandler("system-category", SystemCategoryHandler())

        // 5. Skip empty notifications
        p.addHandler("empty-message", EmptyMessageHandler())

        // 6. Skip ongoing/foreground notifications
        p.addHandler("ongoing", OngoingHandler(skipOngoing = true))

        // 7. Device state filtering
        p.addHandler("device-state", DeviceStateHandler(
            isScreenOn = { screenOn },
            isInCall = { isInCall },
            getConfig = { db.dao.getConfig("global") }
        ))

        // 8. Apply protection for media/nav/fitness/alarm
        p.addHandler("protection", ProtectionHandler())

        // 9. Check duplicates with configurable window
        p.addHandler("duplicate", DuplicateHandler(
            findDuplicate = { hash, pkg, windowMs ->
                val since = System.currentTimeMillis() - windowMs
                db.dao.findDuplicate(hash, pkg, since) != null
            },
            getWindowMs = { pkg ->
                val config = db.dao.getConfig(pkg) ?: db.dao.getConfig("global")
                (config?.ignoreRepeatSec ?: 10) * 1000L
            }
        ))

        // 10. Check blocked terms with regex support
        p.addHandler("blocked-terms", BlockedTermsHandler { pkg ->
            val global = db.dao.getConfig("global")?.blockedTerms
            val app = db.dao.getConfig(pkg)?.blockedTerms
            listOfNotNull(global, app).flatMap { it.split(",") }.filter { it.isNotBlank() }
        })

        // 11. Capture to DB
        p.addHandler("capture") { ctx ->
            if (!ctx.shouldCapture) return@addHandler true

            val captured = buildCapturedNotification(ctx)
            db.dao.insert(captured)
            ctx.actionsPerformed += HandlerAction.CAPTURED
            logger.captured(
                ctx.sbn?.key ?: ctx.metadata["remoteId"] as? String ?: "unknown",
                ctx.sbn?.packageName ?: ctx.source.name,
                ctx.title
            )
            true
        }

        // 12. Try to mark as read (trigger contentIntent)
        p.addHandler("mark-read") { ctx ->
            if (!ctx.shouldMarkRead || ctx.sbn == null) return@addHandler true

            ctx.sbn.notification.contentIntent?.let { intent ->
                try {
                    // Note: Actually sending would open app - we just record capability
                    ctx.actionsPerformed += HandlerAction.MARKED_READ
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to trigger contentIntent: ${e.message}")
                }
            }
            true
        }

        // 13. Cancel notification if not protected
        p.addHandler("cancel") { ctx ->
            if (!ctx.shouldCancel || ctx.sbn == null) return@addHandler true

            try {
                cancelNotification(ctx.sbn.key)
                ctx.actionsPerformed += HandlerAction.CANCELLED
            } catch (e: Exception) {
                Log.w(TAG, "Failed to cancel: ${e.message}")
            }
            true
        }

        return p
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "Notification listener connected")
        logger.serviceConnected()
        _isRunning.value = true
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.i(TAG, "Notification listener disconnected")
        logger.serviceDisconnected()
        _isRunning.value = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Skip self
        if (sbn.packageName == packageName) return

        logger.notificationReceived(sbn.key, sbn.packageName, NotificationSource.LOCAL.name)

        // P1 FIX: Atomic throttle check using ConcurrentHashMap.compute()
        val now = System.currentTimeMillis()
        var shouldProcess = false
        lastUpdate.compute(sbn.key) { _, lastTime ->
            if (now - (lastTime ?: 0) >= throttleMs) {
                shouldProcess = true
                now
            } else {
                lastTime
            }
        }

        if (!shouldProcess) {
            logger.notificationThrottled(sbn.key)
            return
        }

        // Periodically clean up old entries
        cleanupThrottleMap()

        scope.launch {
            processLocal(sbn)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap) {
        onNotificationPosted(sbn) // Delegate to simpler version
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        logger.notificationRemoved(sbn.key)
        lastUpdate.remove(sbn.key)
        scope.launch {
            db.dao.markDeleted(sbn.key)
        }
    }

    private suspend fun processLocal(sbn: StatusBarNotification) {
        val type = NotificationType.classify(sbn)
        val extras = sbn.notification.extras

        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()

        // Extract additional extras
        val bigText = extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val infoText = extras?.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString()
        val subText = extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        val textLines = extras?.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.joinToString("\n") { it.toString() }
        val peopleList = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val people = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                extras?.getParcelableArrayList(Notification.EXTRA_PEOPLE_LIST, Person::class.java)
            } else {
                @Suppress("DEPRECATION")
                extras?.getParcelableArrayList(Notification.EXTRA_PEOPLE_LIST)
            }
            people?.mapNotNull { it.name?.toString() }?.joinToString(",")
        } else null

        val ctx = NotificationContext(
            sbn = sbn,
            source = NotificationSource.LOCAL,
            type = type,
            title = title,
            text = text,
            contentHash = computeHash(sbn.packageName, title, text),
            postTime = sbn.postTime
        ).apply {
            // Store additional extracted data in metadata
            metadata["bigText"] = bigText ?: ""
            metadata["infoText"] = infoText ?: ""
            metadata["subText"] = subText ?: ""
            metadata["textLines"] = textLines ?: ""
            metadata["peopleList"] = peopleList ?: ""

            // Parse money info if MONEY type
            if (type == NotificationType.MONEY) {
                val moneyInfo = MoneyNotificationParser.parse(sbn.packageName, title, text)
                if (moneyInfo != null) {
                    metadata["moneyType"] = moneyInfo.type
                    metadata["moneyAmount"] = moneyInfo.amount ?: 0.0
                    metadata["moneyCurrency"] = moneyInfo.currency
                    metadata["requiresAction"] = moneyInfo.requiresAction
                }
            }
        }

        // Log pipeline start and complete with timing
        val startTime = System.currentTimeMillis()
        logger.pipelineStart(sbn.key, NotificationSource.LOCAL.name, type.name)

        val result = pipeline.process(ctx)

        val durationMs = System.currentTimeMillis() - startTime
        logger.pipelineComplete(
            sbn.key,
            result.actions.map { it.name },
            durationMs
        )
    }

    /**
     * Process a notification from a remote source (called by RemoteProvider).
     */
    suspend fun processRemote(
        source: NotificationSource,
        messageId: String,
        title: String?,
        text: String?,
        senderName: String?,
        channel: String?,
        thread: String?,
        postTime: Long
    ): PipelineResult {
        val ctx = NotificationContext(
            sbn = null,
            source = source,
            type = NotificationType.MESSAGE,
            title = title,
            text = text,
            contentHash = computeHash(source.name, title, text),
            postTime = postTime
        ).apply {
            metadata["remoteId"] = messageId
            metadata["packageName"] = source.name
            metadata["senderName"] = senderName ?: ""
            metadata["channel"] = channel ?: ""
            metadata["thread"] = thread ?: ""
            // Remote notifications: capture and mark-read via API, but no Android cancel
            shouldCancel = false
        }

        // Log pipeline start and complete with timing
        val startTime = System.currentTimeMillis()
        logger.pipelineStart(messageId, source.name, NotificationType.MESSAGE.name)

        val result = pipeline.process(ctx)

        val durationMs = System.currentTimeMillis() - startTime
        logger.pipelineComplete(
            messageId,
            result.actions.map { it.name },
            durationMs
        )

        return result
    }

    private fun buildCapturedNotification(ctx: NotificationContext): CapturedNotification {
        val sbn = ctx.sbn
        val extras = sbn?.notification?.extras

        val actions = sbn?.notification?.actions?.map { action ->
            NotificationAction(
                title = action.title?.toString() ?: "",
                hasRemoteInput = action.remoteInputs?.isNotEmpty() == true
            )
        }

        return CapturedNotification(
            key = sbn?.key ?: ctx.metadata["remoteId"] as? String ?: "",
            source = ctx.source.name,
            packageName = sbn?.packageName ?: ctx.source.name,
            type = ctx.type.name,
            postTime = ctx.postTime,
            title = ctx.title,
            text = ctx.text,
            bigText = ctx.metadata["bigText"] as? String
                ?: extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            conversationTitle = extras?.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString(),
            infoText = ctx.metadata["infoText"] as? String,
            subText = ctx.metadata["subText"] as? String,
            textLines = ctx.metadata["textLines"] as? String,
            peopleList = ctx.metadata["peopleList"] as? String,
            senderName = ctx.metadata["senderName"] as? String,
            remoteChannel = ctx.metadata["channel"] as? String,
            remoteThread = ctx.metadata["thread"] as? String,
            category = sbn?.notification?.category,
            channelId = sbn?.notification?.channelId,
            flags = sbn?.notification?.flags ?: 0,
            isOngoing = sbn?.isOngoing ?: false,
            actions = actions?.let { Json.encodeToString(it) },
            contentHash = ctx.contentHash,
            wasCancelled = HandlerAction.CANCELLED in ctx.actionsPerformed,
            markedRead = HandlerAction.MARKED_READ in ctx.actionsPerformed,
            // Money fields
            moneyType = ctx.metadata["moneyType"] as? String,
            moneyAmount = ctx.metadata["moneyAmount"] as? Double,
            moneyCurrency = ctx.metadata["moneyCurrency"] as? String,
            requiresAction = ctx.metadata["requiresAction"] as? Boolean ?: false
        )
    }

    // P2 FIX: Optimized hash computation using cached MessageDigest
    // P1 FIX: Use 16 bytes (128 bits) instead of 8 to reduce collision risk
    private fun computeHash(pkg: String, title: String?, text: String?): String {
        val digest = digestThreadLocal.get()!!
        digest.reset()
        digest.update(pkg.toByteArray())
        digest.update('|'.code.toByte())
        title?.let { digest.update(it.toByteArray()) }
        digest.update('|'.code.toByte())
        text?.let { digest.update(it.toByteArray()) }

        val bytes = digest.digest()
        return buildString(32) {
            for (i in 0 until 16) {  // 16 bytes = 128 bits
                append(HEX_CHARS[(bytes[i].toInt() shr 4) and 0xF])
                append(HEX_CHARS[bytes[i].toInt() and 0xF])
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        logger.serviceDestroyed()
        try {
            unregisterReceiver(screenReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister receiver: ${e.message}")
        }
        // P0 FIX: Unregister phone state listener
        unregisterPhoneStateListener()
        scope.cancel()
    }

    /**
     * P1 FIX: Periodically clean up old throttle entries to prevent memory growth.
     */
    private fun cleanupThrottleMap() {
        if (lastUpdate.size > maxThrottleEntries) {
            val now = System.currentTimeMillis()
            lastUpdate.entries.removeIf { now - it.value > 60_000 }
        }
    }
}
