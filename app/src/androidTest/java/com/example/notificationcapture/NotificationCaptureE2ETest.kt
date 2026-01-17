package com.example.notificationcapture

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.example.notificationcapture.db.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Flexible E2E tests for NotificationCapture that run in an emulator.
 *
 * Test Strategy:
 * 1. Uses in-memory Room databases for fast, isolated tests
 * 2. Tests pipeline logic via direct API calls (no actual notifications needed)
 * 3. Results validated via database queries and Flow emissions
 * 4. Logs captured to separate LogDatabase for full traceability
 *
 * How to run:
 * ./gradlew connectedAndroidTest
 * or
 * adb shell am instrument -w com.example.notificationcapture.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Results validation:
 * - Check CaptureDatabase for captured notifications
 * - Check LogDatabase for pipeline execution trace
 * - Use pipelineTrace(key) to see full journey of any notification
 */
@RunWith(AndroidJUnit4::class)
class NotificationCaptureE2ETest {

    private lateinit var context: Context
    private lateinit var captureDb: CaptureDatabase
    private lateinit var logDb: LogDatabase
    private lateinit var testScope: CoroutineScope
    private lateinit var pipeline: NotificationPipeline
    private lateinit var logger: StructuredLogger

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        testScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        // Create in-memory databases for test isolation
        captureDb = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        logDb = Room.inMemoryDatabaseBuilder(context, LogDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        logger = StructuredLogger.create(logDb.dao, testScope)

        // Create a test pipeline with all handlers
        pipeline = createTestPipeline()
    }

    @After
    fun tearDown() {
        captureDb.close()
        logDb.close()
    }

    /**
     * Creates a pipeline similar to CaptureService but with test-friendly dependencies.
     */
    private fun createTestPipeline(): NotificationPipeline {
        val p = NotificationPipeline()

        // Minimal pipeline for testing - add handlers as needed per test
        p.addHandler("empty-message", EmptyMessageHandler())
        p.addHandler("group-summary", GroupSummaryHandler())
        p.addHandler("system-category", SystemCategoryHandler())

        // Duplicate handler with test-friendly implementation
        p.addHandler("duplicate", DuplicateHandler(
            findDuplicate = { hash, pkg, windowMs ->
                val since = System.currentTimeMillis() - windowMs
                captureDb.dao.findDuplicate(hash, pkg, since) != null
            },
            getWindowMs = { 10_000L } // Fixed 10 second window for tests
        ))

        // Capture handler
        p.addHandler("capture") { ctx ->
            if (!ctx.shouldCapture) return@addHandler true

            val notification = CapturedNotification(
                key = ctx.metadata["remoteId"] as? String ?: "test-${UUID.randomUUID()}",
                source = ctx.source.name,
                packageName = ctx.metadata["packageName"] as? String ?: ctx.source.name,
                type = ctx.type.name,
                postTime = ctx.postTime,
                title = ctx.title,
                text = ctx.text,
                contentHash = ctx.contentHash
            )
            captureDb.dao.insert(notification)
            ctx.actionsPerformed += HandlerAction.CAPTURED
            true
        }

        return p
    }

    // ============== BASIC CAPTURE TESTS ==============

    @Test
    fun testBasicNotificationCapture() = runTest {
        // Arrange: Create a test notification context
        val ctx = createTestContext(
            title = "Test Title",
            text = "Test message body",
            source = NotificationSource.SLACK
        )

        // Act: Process through pipeline
        val result = pipeline.process(ctx)

        // Assert: Check pipeline result
        assertTrue("Should be captured", result.wasCaptured)
        assertEquals(NotificationSource.SLACK, result.source)

        // Verify in database
        val notifications = captureDb.dao.allNotifications().first()
        assertEquals(1, notifications.size)
        assertEquals("Test Title", notifications[0].title)
        assertEquals("Test message body", notifications[0].text)
        assertEquals("SLACK", notifications[0].source)
    }

    @Test
    fun testEmptyNotificationSkipped() = runTest {
        // Arrange: Create empty notification
        val ctx = createTestContext(
            title = null,
            text = null,
            source = NotificationSource.LOCAL
        )

        // Act
        val result = pipeline.process(ctx)

        // Assert
        assertTrue("Should be skipped", result.wasSkipped)
        assertEquals("Empty notification", result.skipReason)

        // Verify nothing in database
        val notifications = captureDb.dao.allNotifications().first()
        assertEquals(0, notifications.size)
    }

    @Test
    fun testDuplicateNotificationSkipped() = runTest {
        // Arrange: Insert first notification
        val ctx1 = createTestContext(
            title = "Duplicate Test",
            text = "Same content",
            source = NotificationSource.LOCAL
        )
        pipeline.process(ctx1)

        // Create duplicate with same content hash
        val ctx2 = createTestContext(
            title = "Duplicate Test",
            text = "Same content",
            source = NotificationSource.LOCAL,
            contentHash = ctx1.contentHash // Same hash
        )

        // Act: Process duplicate
        val result = pipeline.process(ctx2)

        // Assert
        assertTrue("Should be skipped as duplicate", result.wasSkipped)
        assertTrue(result.skipReason?.contains("Duplicate") == true)

        // Verify only one in database
        val notifications = captureDb.dao.allNotifications().first()
        assertEquals(1, notifications.size)
    }

    // ============== REMOTE PROVIDER TESTS ==============

    @Test
    fun testSlackMessageCapture() = runTest {
        val ctx = createTestContext(
            title = "John Doe",
            text = "Hey, are you coming to the meeting?",
            source = NotificationSource.SLACK,
            metadata = mapOf(
                "remoteId" to "slack-msg-123",
                "packageName" to "com.slack",
                "channel" to "#general",
                "senderName" to "John Doe"
            )
        )

        val result = pipeline.process(ctx)

        assertTrue(result.wasCaptured)
        val notifications = captureDb.dao.bySource("SLACK").first()
        assertEquals(1, notifications.size)
        assertEquals("John Doe", notifications[0].title)
    }

    @Test
    fun testMSTeamsMessageCapture() = runTest {
        val ctx = createTestContext(
            title = "Project Update",
            text = "The deployment is complete",
            source = NotificationSource.MS_TEAMS,
            metadata = mapOf(
                "remoteId" to "teams-msg-456",
                "channel" to "Engineering"
            )
        )

        val result = pipeline.process(ctx)

        assertTrue(result.wasCaptured)
        val notifications = captureDb.dao.bySource("MS_TEAMS").first()
        assertEquals(1, notifications.size)
    }

    @Test
    fun testDiscordMessageCapture() = runTest {
        val ctx = createTestContext(
            title = "Gaming Server",
            text = "Anyone up for a game?",
            source = NotificationSource.DISCORD,
            metadata = mapOf(
                "remoteId" to "discord-msg-789",
                "channel" to "gaming"
            )
        )

        val result = pipeline.process(ctx)

        assertTrue(result.wasCaptured)
    }

    // ============== MONEY NOTIFICATION TESTS ==============

    @Test
    fun testMoneyNotificationParsing() = runTest {
        // Test Venmo payment received
        val result = MoneyNotificationParser.parse(
            pkg = "com.venmo",
            title = "John paid you",
            text = "John paid you $50.00 for dinner"
        )

        assertNotNull(result)
        assertEquals("DEPOSIT", result!!.type)
        assertEquals(50.0, result.amount!!, 0.01)
        assertEquals("USD", result.currency)
        assertFalse(result.requiresAction)
    }

    @Test
    fun testMoneyRequestParsing() = runTest {
        val result = MoneyNotificationParser.parse(
            pkg = "com.squareup.cash",
            title = "Jane requested $25",
            text = "Jane is requesting $25.00 for lunch"
        )

        assertNotNull(result)
        assertEquals("REQUEST", result!!.type)
        assertEquals(25.0, result.amount!!, 0.01)
        assertTrue(result.requiresAction)
    }

    @Test
    fun testMoneyAmountValidation() {
        // Test invalid amounts are rejected
        val result = MoneyNotificationParser.parse(
            pkg = "com.venmo",
            title = "Payment",
            text = "You received $999,999,999,999.99" // Too large
        )

        // Amount should be null due to validation
        assertNull(result?.amount)
    }

    // ============== NOTIFICATION TYPE TESTS ==============

    @Test
    fun testNotificationTypeCapture() = runTest {
        val types = listOf(
            NotificationType.MESSAGE,
            NotificationType.CALL,
            NotificationType.MONEY,
            NotificationType.STANDARD
        )

        for (type in types) {
            val ctx = createTestContext(
                title = "Test $type",
                text = "Message for $type",
                type = type
            )

            val result = pipeline.process(ctx)
            assertTrue("$type should be captured", result.wasCaptured)
            assertEquals(type, result.type)
        }

        val notifications = captureDb.dao.allNotifications().first()
        assertEquals(types.size, notifications.size)
    }

    // ============== LOGGING TESTS ==============

    @Test
    fun testLoggingCapture() = runTest {
        // Log some events
        logger.serviceCreated()
        logger.serviceConnected()
        logger.notificationReceived("test-key", "com.test.app", "LOCAL")

        // Give time for async inserts
        Thread.sleep(100)

        // Verify logs captured
        val logs = logDb.dao.recentLogs(10).first()
        assertTrue("Should have logs", logs.isNotEmpty())

        // Check specific event types
        val eventTypes = logs.map { it.eventType }
        assertTrue(eventTypes.contains("SERVICE_CREATED"))
        assertTrue(eventTypes.contains("SERVICE_CONNECTED"))
        assertTrue(eventTypes.contains("NOTIFICATION_RECEIVED"))
    }

    @Test
    fun testPipelineTraceLogging() = runTest {
        val notificationKey = "trace-test-${UUID.randomUUID()}"

        // Log pipeline events
        logger.pipelineStart(notificationKey, "LOCAL", "MESSAGE")
        logger.handlerStart(notificationKey, "empty-message")
        logger.handlerComplete(notificationKey, "empty-message", 5, true)
        logger.captured(notificationKey, "com.test", "Test Title")
        logger.pipelineComplete(notificationKey, listOf("CAPTURED"), 25)

        Thread.sleep(100)

        // Get pipeline trace
        val trace = logDb.dao.pipelineTrace(notificationKey)
        assertTrue("Should have trace entries", trace.isNotEmpty())

        // Verify trace order
        val eventTypes = trace.map { it.eventType }
        assertTrue(eventTypes.contains("PIPELINE_START"))
        assertTrue(eventTypes.contains("PIPELINE_COMPLETE"))
    }

    // ============== FLOW EMISSION TESTS ==============

    @Test
    fun testPipelineResultEmission() = runTest {
        pipeline.results.test {
            val ctx = createTestContext(
                title = "Flow Test",
                text = "Testing emissions"
            )

            pipeline.process(ctx)

            val emission = awaitItem()
            assertTrue(emission.wasCaptured)
            assertEquals("Flow Test", ctx.title)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun testNotificationCountFlow() = runTest {
        captureDb.dao.notificationCount().test {
            assertEquals(0, awaitItem())

            // Insert notifications
            repeat(3) { i ->
                val ctx = createTestContext(
                    title = "Count Test $i",
                    text = "Message $i"
                )
                pipeline.process(ctx)
            }

            // Should see count updates
            val count = awaitItem()
            assertTrue("Count should increase", count > 0)

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ============== DATABASE QUERY TESTS ==============

    @Test
    fun testQueryBySource() = runTest {
        // Insert mixed sources
        pipeline.process(createTestContext("Slack 1", "msg", NotificationSource.SLACK))
        pipeline.process(createTestContext("Slack 2", "msg", NotificationSource.SLACK))
        pipeline.process(createTestContext("Teams 1", "msg", NotificationSource.MS_TEAMS))
        pipeline.process(createTestContext("Local 1", "msg", NotificationSource.LOCAL))

        val slackOnly = captureDb.dao.bySource("SLACK").first()
        assertEquals(2, slackOnly.size)

        val teamsOnly = captureDb.dao.bySource("MS_TEAMS").first()
        assertEquals(1, teamsOnly.size)
    }

    @Test
    fun testQueryByType() = runTest {
        pipeline.process(createTestContext("Msg 1", "text", type = NotificationType.MESSAGE))
        pipeline.process(createTestContext("Msg 2", "text", type = NotificationType.MESSAGE))
        pipeline.process(createTestContext("Money 1", "text", type = NotificationType.MONEY))

        val messagesOnly = captureDb.dao.byType("MESSAGE").first()
        assertEquals(2, messagesOnly.size)
    }

    @Test
    fun testLogQueryByEventType() = runTest {
        logger.serviceCreated()
        logger.serviceConnected()
        logger.serviceDisconnected()
        logger.error(LogEventType.ERROR_GENERAL, "Test error")

        Thread.sleep(100)

        val errors = logDb.dao.errors().first()
        assertEquals(1, errors.size)
        assertEquals("ERROR_GENERAL", errors[0].eventType)
    }

    // ============== HELPER METHODS ==============

    private fun createTestContext(
        title: String?,
        text: String?,
        source: NotificationSource = NotificationSource.LOCAL,
        type: NotificationType = NotificationType.MESSAGE,
        contentHash: String? = null,
        metadata: Map<String, Any> = emptyMap()
    ): NotificationContext {
        val hash = contentHash ?: computeTestHash(source.name, title, text)
        return NotificationContext(
            sbn = null,
            source = source,
            type = type,
            title = title,
            text = text,
            contentHash = hash,
            postTime = System.currentTimeMillis()
        ).apply {
            this.metadata["remoteId"] = metadata["remoteId"] ?: "test-${UUID.randomUUID()}"
            this.metadata["packageName"] = metadata["packageName"] ?: source.name
            metadata.forEach { (k, v) -> this.metadata[k] = v }
        }
    }

    private fun computeTestHash(pkg: String, title: String?, text: String?): String {
        val content = "$pkg|${title ?: ""}|${text ?: ""}"
        return content.hashCode().toString(16).padStart(32, '0')
    }
}
