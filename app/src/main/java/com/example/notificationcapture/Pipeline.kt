package com.example.notificationcapture

import android.app.Notification
import android.service.notification.StatusBarNotification
import com.example.notificationcapture.db.Config
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.regex.PatternSyntaxException

/**
 * What a handler did with a notification.
 */
enum class HandlerAction {
    NONE,           // Did nothing
    CAPTURED,       // Stored in DB
    MARKED_READ,    // Triggered contentIntent or API mark-read
    CANCELLED,      // Called cancelNotification
    SKIPPED,        // Intentionally skipped (duplicate, filtered, etc.)
    PROTECTED       // Captured but protected from cancellation
}

/**
 * Result of processing a notification through the pipeline.
 */
data class PipelineResult(
    val notificationKey: String,
    val source: NotificationSource,
    val type: NotificationType,
    val actions: Set<HandlerAction>,
    val skipReason: String? = null
) {
    val wasCaptured get() = HandlerAction.CAPTURED in actions
    val wasCancelled get() = HandlerAction.CANCELLED in actions
    val wasSkipped get() = HandlerAction.SKIPPED in actions
}

/**
 * Context passed through the pipeline - handlers can read and modify.
 */
data class NotificationContext(
    val sbn: StatusBarNotification?,        // null for remote notifications
    val source: NotificationSource,
    val type: NotificationType,
    val title: String?,
    val text: String?,
    val contentHash: String,
    val postTime: Long,
    // Mutable state - handlers update this
    var shouldCapture: Boolean = true,
    var shouldCancel: Boolean = true,
    var shouldMarkRead: Boolean = true,
    var skipReason: String? = null,
    val actionsPerformed: MutableSet<HandlerAction> = mutableSetOf(),
    val metadata: MutableMap<String, Any> = mutableMapOf()
) {
    fun skip(reason: String) {
        shouldCapture = false
        shouldCancel = false
        skipReason = reason
        actionsPerformed += HandlerAction.SKIPPED
    }

    fun protect() {
        shouldCancel = false
        actionsPerformed += HandlerAction.PROTECTED
    }
}

/**
 * A handler in the notification pipeline.
 * Return true to continue processing, false to stop the pipeline.
 */
fun interface NotificationHandler {
    suspend fun handle(ctx: NotificationContext): Boolean
}

/**
 * Simple notification processing pipeline.
 * Handlers are called in order. Any handler can stop the pipeline.
 */
class NotificationPipeline(
    // P1 FIX: Allow registering error callback for critical handler failures
    private val onHandlerError: ((handlerName: String, error: Exception) -> Unit)? = null
) {
    private val handlers = mutableListOf<Pair<String, NotificationHandler>>()
    private val criticalHandlers = mutableSetOf<String>()

    // P1 FIX: Add BufferOverflow.DROP_OLDEST to prevent unbounded buffering
    private val _results = MutableSharedFlow<PipelineResult>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val results: SharedFlow<PipelineResult> = _results.asSharedFlow()

    /**
     * Add a named handler to the end of the pipeline.
     */
    fun addHandler(name: String, handler: NotificationHandler) {
        handlers += name to handler
    }

    /**
     * Add handler at a specific position.
     */
    fun addHandlerAt(index: Int, name: String, handler: NotificationHandler) {
        handlers.add(index, name to handler)
    }

    /**
     * Remove a handler by name.
     */
    fun removeHandler(name: String) {
        handlers.removeAll { it.first == name }
        criticalHandlers.remove(name)
    }

    /**
     * P1 FIX: Mark a handler as critical - errors will invoke the error callback.
     */
    fun markCritical(name: String) {
        criticalHandlers.add(name)
    }

    /**
     * Process a notification through all handlers.
     */
    suspend fun process(ctx: NotificationContext): PipelineResult {
        for ((name, handler) in handlers) {
            val shouldContinue = try {
                handler.handle(ctx)
            } catch (e: Exception) {
                // Log the error
                android.util.Log.e("Pipeline", "Handler '$name' failed: ${e.message}")

                // P1 FIX: Invoke error callback for critical handlers
                if (name in criticalHandlers) {
                    onHandlerError?.invoke(name, e)
                }

                // Continue processing - but skip capture if critical handler failed
                if (name in criticalHandlers) {
                    ctx.skip("Critical handler failed: $name")
                    false
                } else {
                    true
                }
            }
            if (!shouldContinue) break
        }

        val result = PipelineResult(
            notificationKey = ctx.sbn?.key ?: ctx.metadata["remoteId"] as? String ?: "unknown",
            source = ctx.source,
            type = ctx.type,
            actions = ctx.actionsPerformed.toSet(),
            skipReason = ctx.skipReason
        )

        _results.emit(result)
        return result
    }

    /**
     * Get current handler names in order.
     */
    fun handlerNames(): List<String> = handlers.map { it.first }
}

// ============== BUILT-IN HANDLERS ==============

/**
 * Handler that skips notifications from packages with connected remote providers.
 * Prevents duplicates when user has both app installed AND OAuth connected.
 */
class RemoteDedupeHandler(
    private val connectedProviders: () -> Set<NotificationSource>
) : NotificationHandler {
    override suspend fun handle(ctx: NotificationContext): Boolean {
        if (ctx.source != NotificationSource.LOCAL) return true

        val remoteSource = ctx.sbn?.packageName?.let { NotificationSource.fromPackage(it) }
        if (remoteSource != null && remoteSource in connectedProviders()) {
            ctx.skip("Remote provider connected for ${remoteSource.name}")
            return false
        }
        return true
    }
}

/**
 * Handler that applies protection to certain notification types.
 */
class ProtectionHandler : NotificationHandler {
    override suspend fun handle(ctx: NotificationContext): Boolean {
        if (ctx.type.isProtected) {
            ctx.protect()
        }
        return true
    }
}

/**
 * Handler that checks for duplicates within a configurable time window.
 */
class DuplicateHandler(
    private val findDuplicate: suspend (hash: String, pkg: String, windowMs: Long) -> Boolean,
    private val getWindowMs: suspend (pkg: String) -> Long = { 10_000L }
) : NotificationHandler {
    override suspend fun handle(ctx: NotificationContext): Boolean {
        val pkg = ctx.sbn?.packageName ?: ctx.metadata["packageName"] as? String ?: return true
        val windowMs = getWindowMs(pkg)
        if (findDuplicate(ctx.contentHash, pkg, windowMs)) {
            ctx.skip("Duplicate within ${windowMs}ms")
            return false
        }
        return true
    }
}

/**
 * Handler that filters by blocked terms with regex support.
 * Terms starting with "regex:" are treated as regex patterns.
 */
class BlockedTermsHandler(
    private val getBlockedTerms: suspend (pkg: String) -> List<String>
) : NotificationHandler {
    override suspend fun handle(ctx: NotificationContext): Boolean {
        val pkg = ctx.sbn?.packageName ?: return true
        val terms = getBlockedTerms(pkg)
        val content = "${ctx.title ?: ""} ${ctx.text ?: ""}"

        for (term in terms) {
            if (term.isBlank()) continue

            val matched = if (term.startsWith("regex:")) {
                try {
                    val pattern = Regex(term.removePrefix("regex:"))
                    pattern.containsMatchIn(content)
                } catch (e: PatternSyntaxException) {
                    // Invalid regex, skip this term
                    false
                }
            } else {
                content.contains(term, ignoreCase = true)
            }

            if (matched) {
                ctx.skip("Blocked term: $term")
                return false
            }
        }
        return true
    }
}

/**
 * Handler that skips ongoing/foreground service notifications.
 */
class OngoingHandler(private val skipOngoing: Boolean = true) : NotificationHandler {
    override suspend fun handle(ctx: NotificationContext): Boolean {
        if (skipOngoing && ctx.sbn?.isOngoing == true) {
            ctx.skip("Ongoing notification")
            return false
        }
        return true
    }
}

/**
 * Handler that skips group summary notifications.
 */
class GroupSummaryHandler : NotificationHandler {
    override suspend fun handle(ctx: NotificationContext): Boolean {
        val flags = ctx.sbn?.notification?.flags ?: 0
        if (flags and Notification.FLAG_GROUP_SUMMARY != 0) {
            ctx.skip("Group summary notification")
            return false
        }
        return true
    }
}

/**
 * Handler that skips empty notifications (no title AND no text).
 */
class EmptyMessageHandler : NotificationHandler {
    override suspend fun handle(ctx: NotificationContext): Boolean {
        if (ctx.title.isNullOrBlank() && ctx.text.isNullOrBlank()) {
            ctx.skip("Empty notification")
            return false
        }
        return true
    }
}

/**
 * Handler that skips CATEGORY_SYSTEM notifications.
 */
class SystemCategoryHandler : NotificationHandler {
    override suspend fun handle(ctx: NotificationContext): Boolean {
        if (ctx.sbn?.notification?.category == Notification.CATEGORY_SYSTEM) {
            ctx.skip("System notification")
            return false
        }
        return true
    }
}

/**
 * Handler that filters based on device state (screen, call, etc.).
 */
class DeviceStateHandler(
    private val isScreenOn: () -> Boolean,
    private val isInCall: () -> Boolean,
    private val getConfig: suspend () -> Config?
) : NotificationHandler {
    override suspend fun handle(ctx: NotificationContext): Boolean {
        val config = getConfig()

        // Skip if screen is off and config says to
        if (config?.skipWhenScreenOff == true && !isScreenOn()) {
            ctx.skip("Screen is off")
            return false
        }

        // Skip if in call and config says to
        if (config?.skipWhenInCall == true && isInCall()) {
            ctx.skip("In call")
            return false
        }

        return true
    }
}
