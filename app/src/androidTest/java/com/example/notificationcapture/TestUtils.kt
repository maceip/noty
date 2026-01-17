package com.example.notificationcapture

import android.content.Context
import androidx.room.Room
import com.example.notificationcapture.db.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import java.util.UUID

/**
 * Test utilities for NotificationCapture E2E tests.
 */
object TestUtils {

    /**
     * Creates an in-memory CaptureDatabase for testing.
     */
    fun createTestCaptureDb(context: Context): CaptureDatabase =
        Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    /**
     * Creates an in-memory LogDatabase for testing.
     */
    fun createTestLogDb(context: Context): LogDatabase =
        Room.inMemoryDatabaseBuilder(context, LogDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    /**
     * Creates a StructuredLogger for testing.
     */
    fun createTestLogger(logDb: LogDatabase, scope: CoroutineScope): StructuredLogger =
        StructuredLogger.create(logDb.dao, scope)

    /**
     * Generates a unique test notification key.
     */
    fun generateTestKey(prefix: String = "test"): String =
        "$prefix-${UUID.randomUUID()}"

    /**
     * Creates a minimal test notification for database insertion.
     */
    fun createTestNotification(
        key: String = generateTestKey(),
        source: NotificationSource = NotificationSource.LOCAL,
        packageName: String = "com.test.app",
        type: NotificationType = NotificationType.MESSAGE,
        title: String? = "Test Title",
        text: String? = "Test text",
        postTime: Long = System.currentTimeMillis()
    ): CapturedNotification = CapturedNotification(
        key = key,
        source = source.name,
        packageName = packageName,
        type = type.name,
        postTime = postTime,
        title = title,
        text = text,
        contentHash = "$packageName|$title|$text".hashCode().toString(16)
    )

    /**
     * Creates test contexts for batch testing.
     */
    fun createBatchTestContexts(count: Int): List<NotificationContext> =
        (1..count).map { i ->
            NotificationContext(
                sbn = null,
                source = NotificationSource.LOCAL,
                type = NotificationType.MESSAGE,
                title = "Batch Test $i",
                text = "Message body $i",
                contentHash = "batch-$i-${UUID.randomUUID()}",
                postTime = System.currentTimeMillis()
            ).apply {
                metadata["remoteId"] = generateTestKey("batch")
                metadata["packageName"] = "com.test.batch"
            }
        }

    /**
     * Asserts that a notification was captured with expected values.
     */
    suspend fun assertNotificationCaptured(
        db: CaptureDatabase,
        expectedTitle: String?,
        expectedSource: String? = null
    ) {
        val notifications = db.dao.allNotifications().first()
        val found = notifications.find { it.title == expectedTitle }
        assert(found != null) { "Notification with title '$expectedTitle' not found" }
        if (expectedSource != null) {
            assert(found!!.source == expectedSource) {
                "Expected source '$expectedSource' but got '${found.source}'"
            }
        }
    }

    /**
     * Asserts that logs contain expected event type.
     */
    suspend fun assertLogContains(
        db: LogDatabase,
        eventType: LogEventType
    ) {
        val logs = db.dao.recentLogs(100).first()
        val found = logs.any { it.eventType == eventType.name }
        assert(found) { "Log with event type '${eventType.name}' not found" }
    }
}

/**
 * Builder for creating test NotificationContext with fluent API.
 */
class TestContextBuilder {
    private var title: String? = "Default Title"
    private var text: String? = "Default Text"
    private var source: NotificationSource = NotificationSource.LOCAL
    private var type: NotificationType = NotificationType.MESSAGE
    private var metadata: MutableMap<String, Any> = mutableMapOf()

    fun title(value: String?) = apply { title = value }
    fun text(value: String?) = apply { text = value }
    fun source(value: NotificationSource) = apply { source = value }
    fun type(value: NotificationType) = apply { type = value }
    fun metadata(key: String, value: Any) = apply { metadata[key] = value }

    fun slack() = apply {
        source = NotificationSource.SLACK
        metadata["packageName"] = "com.slack"
    }

    fun teams() = apply {
        source = NotificationSource.MS_TEAMS
        metadata["packageName"] = "com.microsoft.teams"
    }

    fun discord() = apply {
        source = NotificationSource.DISCORD
        metadata["packageName"] = "com.discord"
    }

    fun money() = apply {
        type = NotificationType.MONEY
        metadata["packageName"] = "com.venmo"
    }

    fun build(): NotificationContext {
        val hash = "$source|$title|$text".hashCode().toString(16).padStart(32, '0')
        return NotificationContext(
            sbn = null,
            source = source,
            type = type,
            title = title,
            text = text,
            contentHash = hash,
            postTime = System.currentTimeMillis()
        ).apply {
            this.metadata["remoteId"] = TestUtils.generateTestKey()
            this.metadata["packageName"] = this@TestContextBuilder.metadata["packageName"]
                ?: source.name
            this@TestContextBuilder.metadata.forEach { (k, v) ->
                this.metadata[k] = v
            }
        }
    }
}

/**
 * DSL for building test contexts.
 */
fun testContext(block: TestContextBuilder.() -> Unit): NotificationContext =
    TestContextBuilder().apply(block).build()
