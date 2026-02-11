package com.example.notificationcapture.db

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant

// ============== ENTITIES ==============

@Entity(
    tableName = "notifications",
    indices = [
        Index("package_name"),
        Index("post_time"),
        Index("content_hash"),
        Index("source"),
        Index("type"),
        // P1 FIX: Index on key for markDeleted query
        Index("key"),
        // P1 FIX: Composite index for duplicate detection
        Index(value = ["content_hash", "package_name", "post_time"])
    ]
)
data class CapturedNotification(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val key: String,                      // SBN key or remote message ID
    val source: String,                   // NotificationSource.name
    @ColumnInfo(name = "package_name") val packageName: String,
    val type: String,                     // NotificationType.name
    @ColumnInfo(name = "post_time") val postTime: Long,   // epoch millis
    @ColumnInfo(name = "capture_time") val captureTime: Long = System.currentTimeMillis(),

    // Content
    val title: String? = null,
    val text: String? = null,
    @ColumnInfo(name = "big_text") val bigText: String? = null,
    @ColumnInfo(name = "conversation_title") val conversationTitle: String? = null,

    // Additional extras (HIGH feature)
    @ColumnInfo(name = "info_text") val infoText: String? = null,
    @ColumnInfo(name = "sub_text") val subText: String? = null,
    @ColumnInfo(name = "text_lines") val textLines: String? = null,
    @ColumnInfo(name = "people_list") val peopleList: String? = null,

    // Remote-specific
    @ColumnInfo(name = "sender_name") val senderName: String? = null,
    @ColumnInfo(name = "remote_channel") val remoteChannel: String? = null,
    @ColumnInfo(name = "remote_thread") val remoteThread: String? = null,

    // Metadata
    val category: String? = null,
    @ColumnInfo(name = "channel_id") val channelId: String? = null,
    val flags: Int = 0,
    @ColumnInfo(name = "is_ongoing") val isOngoing: Boolean = false,

    // Actions as JSON array
    val actions: String? = null,          // JSON: [{"title":"Reply","hasRemoteInput":true},...]

    // Deduplication
    @ColumnInfo(name = "content_hash") val contentHash: String,

    // Processing state
    @ColumnInfo(name = "was_cancelled") val wasCancelled: Boolean = false,
    @ColumnInfo(name = "marked_read") val markedRead: Boolean = false,
    @ColumnInfo(name = "is_deleted") val isDeleted: Boolean = false,

    // Money tracking (HIGH feature)
    @ColumnInfo(name = "money_type") val moneyType: String? = null,      // PAYMENT, REQUEST, BALANCE, DEPOSIT
    @ColumnInfo(name = "money_amount") val moneyAmount: Double? = null,
    @ColumnInfo(name = "money_currency") val moneyCurrency: String? = null,
    @ColumnInfo(name = "requires_action") val requiresAction: Boolean = false
)

@Serializable
data class NotificationAction(val title: String, val hasRemoteInput: Boolean = false)

@Entity(tableName = "config")
data class Config(
    @PrimaryKey val key: String,          // "global" or package name
    val enabled: Boolean = true,
    @ColumnInfo(name = "blocked_terms") val blockedTerms: String? = null,      // comma-separated, supports "regex:" prefix
    @ColumnInfo(name = "ignore_patterns") val ignorePatterns: String? = null,  // newline-separated regex
    @ColumnInfo(name = "ignore_repeat_sec") val ignoreRepeatSec: Int = 10,
    @ColumnInfo(name = "skip_if_remote") val skipIfRemote: Boolean = true,     // skip local if remote connected
    // Device state filtering (MEDIUM feature)
    @ColumnInfo(name = "skip_screen_off") val skipWhenScreenOff: Boolean = false,
    @ColumnInfo(name = "skip_in_call") val skipWhenInCall: Boolean = false
)

@Entity(tableName = "oauth_tokens")
data class OAuthToken(
    @PrimaryKey val provider: String,     // NotificationSource.name
    @ColumnInfo(name = "access_token") val accessToken: String,
    @ColumnInfo(name = "refresh_token") val refreshToken: String? = null,
    @ColumnInfo(name = "expires_at") val expiresAt: Long? = null,
    @ColumnInfo(name = "user_id") val userId: String? = null,
    @ColumnInfo(name = "team_id") val teamId: String? = null
)

// ============== DAO ==============

@Dao
interface CaptureDao {
    // Notifications
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(notification: CapturedNotification): Long

    @Query("SELECT * FROM notifications ORDER BY post_time DESC")
    fun allNotifications(): Flow<List<CapturedNotification>>

    @Query("SELECT * FROM notifications WHERE source = :source ORDER BY post_time DESC")
    fun bySource(source: String): Flow<List<CapturedNotification>>

    @Query("SELECT * FROM notifications WHERE type = :type ORDER BY post_time DESC")
    fun byType(type: String): Flow<List<CapturedNotification>>

    @Query("SELECT * FROM notifications WHERE content_hash = :hash AND package_name = :pkg AND post_time > :since LIMIT 1")
    suspend fun findDuplicate(hash: String, pkg: String, since: Long): CapturedNotification?

    @Query("UPDATE notifications SET is_deleted = 1 WHERE `key` = :key")
    suspend fun markDeleted(key: String)

    @Query("DELETE FROM notifications WHERE post_time < :before")
    suspend fun deleteOlderThan(before: Long)

    // P2 FIX: Dedicated count query (doesn't load all rows)
    @Query("SELECT COUNT(*) FROM notifications")
    fun notificationCount(): Flow<Int>

    // Money-specific queries (HIGH feature)
    @Query("SELECT * FROM notifications WHERE type = 'MONEY' ORDER BY post_time DESC")
    fun moneyNotifications(): Flow<List<CapturedNotification>>

    @Query("SELECT SUM(money_amount) FROM notifications WHERE type = 'MONEY' AND money_type = :moneyType")
    fun totalByMoneyType(moneyType: String): Flow<Double?>

    // Config
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConfig(config: Config)

    @Query("SELECT * FROM config WHERE `key` = :key")
    suspend fun getConfig(key: String): Config?

    @Query("SELECT * FROM config WHERE `key` = 'global'")
    fun globalConfig(): Flow<Config?>

    // OAuth
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertToken(token: OAuthToken)

    @Query("SELECT * FROM oauth_tokens WHERE provider = :provider")
    suspend fun getToken(provider: String): OAuthToken?

    @Query("SELECT * FROM oauth_tokens")
    fun allTokens(): Flow<List<OAuthToken>>

    @Query("DELETE FROM oauth_tokens WHERE provider = :provider")
    suspend fun deleteToken(provider: String)
}

// ============== TYPE CONVERTERS ==============

class Converters {
    private val json = Json { ignoreUnknownKeys = true }

    @TypeConverter
    fun actionsToJson(actions: List<NotificationAction>?): String? =
        actions?.let { json.encodeToString(it) }

    @TypeConverter
    fun jsonToActions(value: String?): List<NotificationAction>? =
        value?.let { json.decodeFromString(it) }
}

// ============== DATABASE ==============

@Database(
    version = 2,  // Incremented for new index
    entities = [CapturedNotification::class, Config::class, OAuthToken::class],
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class CaptureDatabase : RoomDatabase() {
    abstract val dao: CaptureDao

    companion object {
        @Volatile private var instance: CaptureDatabase? = null

        fun get(context: Context): CaptureDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    CaptureDatabase::class.java,
                    "capture.db"
                )
                    .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                    // Safe fallback for schema changes during development
                    .fallbackToDestructiveMigration(true)
                    .build()
                    .also { instance = it }
            }
    }
}
