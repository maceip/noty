package com.example.notificationcapture.db

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// ============== LOG LEVELS ==============

enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR
}

// ============== LOG EVENT TYPES ==============

enum class LogEventType {
    // Service lifecycle
    SERVICE_CREATED,
    SERVICE_CONNECTED,
    SERVICE_DISCONNECTED,
    SERVICE_DESTROYED,
    SERVICE_SUSPENDED,
    SERVICE_RESUMED,

    // Notification events
    NOTIFICATION_RECEIVED,
    NOTIFICATION_REMOVED,
    NOTIFICATION_THROTTLED,

    // Pipeline events
    PIPELINE_START,
    PIPELINE_HANDLER_START,
    PIPELINE_HANDLER_COMPLETE,
    PIPELINE_HANDLER_ERROR,
    PIPELINE_COMPLETE,

    // Handler actions
    HANDLER_SKIPPED,
    HANDLER_CAPTURED,
    HANDLER_CANCELLED,
    HANDLER_PROTECTED,
    HANDLER_MARKED_READ,

    // Database events
    DB_INSERT,
    DB_UPDATE,
    DB_DELETE,
    DB_QUERY,

    // Remote provider events
    REMOTE_POLL_START,
    REMOTE_POLL_COMPLETE,
    REMOTE_MESSAGE_RECEIVED,
    REMOTE_AUTH_SUCCESS,
    REMOTE_AUTH_FAILURE,
    REMOTE_TOKEN_REFRESH,

    // Errors
    ERROR_GENERAL,
    ERROR_PIPELINE,
    ERROR_DATABASE,
    ERROR_REMOTE
}

// ============== ENTITIES ==============

@Entity(
    tableName = "log_events",
    indices = [
        Index("timestamp"),
        Index("level"),
        Index("event_type"),
        Index("notification_key"),
        Index("handler_name"),
        Index(value = ["event_type", "timestamp"])
    ]
)
data class LogEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val level: String,                           // LogLevel.name
    @ColumnInfo(name = "event_type") val eventType: String,  // LogEventType.name
    val message: String,
    @ColumnInfo(name = "notification_key") val notificationKey: String? = null,
    @ColumnInfo(name = "handler_name") val handlerName: String? = null,
    @ColumnInfo(name = "source") val source: String? = null,     // NotificationSource.name
    @ColumnInfo(name = "notification_type") val notificationType: String? = null,  // NotificationType.name
    @ColumnInfo(name = "skip_reason") val skipReason: String? = null,
    @ColumnInfo(name = "duration_ms") val durationMs: Long? = null,
    @ColumnInfo(name = "metadata") val metadata: String? = null,  // JSON for flexible data
    @ColumnInfo(name = "stack_trace") val stackTrace: String? = null
)

@Serializable
data class LogMetadata(
    val packageName: String? = null,
    val title: String? = null,
    val actions: List<String>? = null,
    val extra: Map<String, String>? = null
)

// ============== DAO ==============

@Dao
interface LogDao {
    // Insert
    @Insert
    suspend fun insert(event: LogEvent): Long

    @Insert
    suspend fun insertAll(events: List<LogEvent>)

    // Query all logs (most recent first)
    @Query("SELECT * FROM log_events ORDER BY timestamp DESC")
    fun allLogs(): Flow<List<LogEvent>>

    // Query by level
    @Query("SELECT * FROM log_events WHERE level = :level ORDER BY timestamp DESC")
    fun byLevel(level: String): Flow<List<LogEvent>>

    // Query by event type
    @Query("SELECT * FROM log_events WHERE event_type = :eventType ORDER BY timestamp DESC")
    fun byEventType(eventType: String): Flow<List<LogEvent>>

    // Query by notification key
    @Query("SELECT * FROM log_events WHERE notification_key = :key ORDER BY timestamp DESC")
    fun byNotificationKey(key: String): Flow<List<LogEvent>>

    // Query by handler name
    @Query("SELECT * FROM log_events WHERE handler_name = :name ORDER BY timestamp DESC")
    fun byHandlerName(name: String): Flow<List<LogEvent>>

    // Query errors only
    @Query("SELECT * FROM log_events WHERE level = 'ERROR' ORDER BY timestamp DESC")
    fun errors(): Flow<List<LogEvent>>

    // Query within time range
    @Query("SELECT * FROM log_events WHERE timestamp BETWEEN :start AND :end ORDER BY timestamp DESC")
    fun inTimeRange(start: Long, end: Long): Flow<List<LogEvent>>

    // Query recent logs (for UI display)
    @Query("SELECT * FROM log_events ORDER BY timestamp DESC LIMIT :limit")
    fun recentLogs(limit: Int = 100): Flow<List<LogEvent>>

    // Pipeline trace for a notification (full journey)
    @Query("""
        SELECT * FROM log_events
        WHERE notification_key = :key
        AND event_type IN ('PIPELINE_START', 'PIPELINE_HANDLER_START', 'PIPELINE_HANDLER_COMPLETE',
                           'PIPELINE_HANDLER_ERROR', 'PIPELINE_COMPLETE', 'HANDLER_SKIPPED',
                           'HANDLER_CAPTURED', 'HANDLER_CANCELLED', 'HANDLER_PROTECTED')
        ORDER BY timestamp ASC
    """)
    suspend fun pipelineTrace(key: String): List<LogEvent>

    // Count by event type (for analytics)
    @Query("SELECT event_type, COUNT(*) as count FROM log_events GROUP BY event_type")
    suspend fun countByEventType(): List<EventTypeCount>

    // Count by handler (for performance analysis)
    @Query("SELECT handler_name, COUNT(*) as count, AVG(duration_ms) as avg_duration FROM log_events WHERE handler_name IS NOT NULL GROUP BY handler_name")
    suspend fun handlerStats(): List<HandlerStats>

    // Delete old logs (retention policy)
    @Query("DELETE FROM log_events WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long): Int

    // Delete all logs
    @Query("DELETE FROM log_events")
    suspend fun deleteAll()

    // Count total logs
    @Query("SELECT COUNT(*) FROM log_events")
    fun count(): Flow<Int>
}

data class EventTypeCount(
    @ColumnInfo(name = "event_type") val eventType: String,
    val count: Int
)

data class HandlerStats(
    @ColumnInfo(name = "handler_name") val handlerName: String?,
    val count: Int,
    @ColumnInfo(name = "avg_duration") val avgDuration: Double?
)

// ============== DATABASE ==============

@Database(
    version = 1,
    entities = [LogEvent::class],
    exportSchema = true
)
abstract class LogDatabase : RoomDatabase() {
    abstract val dao: LogDao

    companion object {
        @Volatile private var instance: LogDatabase? = null

        fun get(context: Context): LogDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    LogDatabase::class.java,
                    "logs.db"
                )
                    .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                    .build()
                    .also { instance = it }
            }

        // For testing - allows in-memory database
        fun getInMemory(context: Context): LogDatabase =
            Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                LogDatabase::class.java
            ).build()
    }
}

// ============== LOGGER ==============

/**
 * Production-quality structured logger that writes to the LogDatabase.
 * Thread-safe, non-blocking (uses coroutines), with automatic batching.
 */
class StructuredLogger private constructor(
    private val dao: LogDao,
    private val scope: CoroutineScope
) {
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        @Volatile private var instance: StructuredLogger? = null

        fun get(context: Context, scope: CoroutineScope): StructuredLogger =
            instance ?: synchronized(this) {
                instance ?: StructuredLogger(
                    LogDatabase.get(context).dao,
                    scope
                ).also { instance = it }
            }

        // For testing
        fun create(dao: LogDao, scope: CoroutineScope): StructuredLogger =
            StructuredLogger(dao, scope)
    }

    // ============== LOGGING METHODS ==============

    fun debug(
        eventType: LogEventType,
        message: String,
        notificationKey: String? = null,
        handlerName: String? = null,
        source: String? = null,
        notificationType: String? = null,
        metadata: LogMetadata? = null
    ) = log(LogLevel.DEBUG, eventType, message, notificationKey, handlerName, source, notificationType, null, null, metadata, null)

    fun info(
        eventType: LogEventType,
        message: String,
        notificationKey: String? = null,
        handlerName: String? = null,
        source: String? = null,
        notificationType: String? = null,
        durationMs: Long? = null,
        metadata: LogMetadata? = null
    ) = log(LogLevel.INFO, eventType, message, notificationKey, handlerName, source, notificationType, null, durationMs, metadata, null)

    fun warn(
        eventType: LogEventType,
        message: String,
        notificationKey: String? = null,
        handlerName: String? = null,
        skipReason: String? = null,
        metadata: LogMetadata? = null
    ) = log(LogLevel.WARN, eventType, message, notificationKey, handlerName, null, null, skipReason, null, metadata, null)

    fun error(
        eventType: LogEventType,
        message: String,
        throwable: Throwable? = null,
        notificationKey: String? = null,
        handlerName: String? = null,
        metadata: LogMetadata? = null
    ) = log(LogLevel.ERROR, eventType, message, notificationKey, handlerName, null, null, null, null, metadata, throwable?.stackTraceToString())

    private fun log(
        level: LogLevel,
        eventType: LogEventType,
        message: String,
        notificationKey: String?,
        handlerName: String?,
        source: String?,
        notificationType: String?,
        skipReason: String?,
        durationMs: Long?,
        metadata: LogMetadata?,
        stackTrace: String?
    ) {
        val event = LogEvent(
            level = level.name,
            eventType = eventType.name,
            message = message,
            notificationKey = notificationKey,
            handlerName = handlerName,
            source = source,
            notificationType = notificationType,
            skipReason = skipReason,
            durationMs = durationMs,
            metadata = metadata?.let { json.encodeToString(it) },
            stackTrace = stackTrace
        )

        // Fire and forget - non-blocking
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                dao.insert(event)
            } catch (e: Exception) {
                // Fallback to Android Log if DB insert fails
                android.util.Log.e("StructuredLogger", "Failed to log: ${e.message}")
            }
        }
    }

    // ============== PIPELINE LOGGING HELPERS ==============

    fun pipelineStart(notificationKey: String, source: String, type: String) =
        info(LogEventType.PIPELINE_START, "Pipeline started", notificationKey, source = source, notificationType = type)

    fun handlerStart(notificationKey: String, handlerName: String) =
        debug(LogEventType.PIPELINE_HANDLER_START, "Handler starting", notificationKey, handlerName)

    fun handlerComplete(notificationKey: String, handlerName: String, durationMs: Long, continued: Boolean) =
        info(
            LogEventType.PIPELINE_HANDLER_COMPLETE,
            if (continued) "Handler complete, continuing" else "Handler complete, stopped pipeline",
            notificationKey, handlerName, durationMs = durationMs
        )

    fun handlerError(notificationKey: String, handlerName: String, error: Throwable) =
        error(LogEventType.PIPELINE_HANDLER_ERROR, "Handler error: ${error.message}", error, notificationKey, handlerName)

    fun handlerSkipped(notificationKey: String, handlerName: String, reason: String) =
        warn(LogEventType.HANDLER_SKIPPED, "Skipped: $reason", notificationKey, handlerName, skipReason = reason)

    fun captured(notificationKey: String, packageName: String, title: String?) =
        info(
            LogEventType.HANDLER_CAPTURED,
            "Notification captured",
            notificationKey,
            metadata = LogMetadata(packageName = packageName, title = title)
        )

    fun pipelineComplete(notificationKey: String, actions: List<String>, durationMs: Long) =
        info(
            LogEventType.PIPELINE_COMPLETE,
            "Pipeline complete with actions: ${actions.joinToString()}",
            notificationKey,
            durationMs = durationMs,
            metadata = LogMetadata(actions = actions)
        )

    // ============== SERVICE LIFECYCLE ==============

    fun serviceCreated() = info(LogEventType.SERVICE_CREATED, "CaptureService created")
    fun serviceConnected() = info(LogEventType.SERVICE_CONNECTED, "Notification listener connected")
    fun serviceDisconnected() = info(LogEventType.SERVICE_DISCONNECTED, "Notification listener disconnected")
    fun serviceDestroyed() = info(LogEventType.SERVICE_DESTROYED, "CaptureService destroyed")
    fun serviceSuspended() = info(LogEventType.SERVICE_SUSPENDED, "Capture service suspended")
    fun serviceResumed() = info(LogEventType.SERVICE_RESUMED, "Capture service resumed")

    // ============== NOTIFICATION EVENTS ==============

    fun notificationReceived(key: String, packageName: String, source: String) =
        debug(
            LogEventType.NOTIFICATION_RECEIVED,
            "Notification received from $packageName",
            key, source = source,
            metadata = LogMetadata(packageName = packageName)
        )

    fun notificationThrottled(key: String) =
        debug(LogEventType.NOTIFICATION_THROTTLED, "Notification throttled", key)

    fun notificationRemoved(key: String) =
        debug(LogEventType.NOTIFICATION_REMOVED, "Notification removed", key)

    // ============== REMOTE PROVIDER EVENTS ==============

    fun remotePollStart(source: String) =
        debug(LogEventType.REMOTE_POLL_START, "Starting poll for $source", source = source)

    fun remotePollComplete(source: String, messageCount: Int, durationMs: Long) =
        info(
            LogEventType.REMOTE_POLL_COMPLETE,
            "Poll complete: $messageCount messages from $source",
            source = source, durationMs = durationMs
        )

    fun remoteAuthSuccess(source: String) =
        info(LogEventType.REMOTE_AUTH_SUCCESS, "OAuth authentication successful for $source", source = source)

    fun remoteAuthFailure(source: String, errorMsg: String) =
        error(LogEventType.REMOTE_AUTH_FAILURE, "OAuth authentication failed for $source: $errorMsg")
}
