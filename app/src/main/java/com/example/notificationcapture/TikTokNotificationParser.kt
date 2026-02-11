package com.example.notificationcapture

import android.util.Log

/**
 * Parses TikTok-specific notification content to extract rich context.
 */
object TikTokNotificationParser {
    private const val TAG = "TikTokParser"

    data class TikTokInfo(
        val senderHandle: String?,
        val content: String?,
        val videoTitle: String? = null
    )

    fun parse(packageName: String, title: String?, text: String?): TikTokInfo? {
        if (!packageName.contains("tiktok", ignoreCase = true) && 
            !packageName.contains("musically", ignoreCase = true)) {
            return null
        }

        // TikTok comment format examples:
        // Title: "TikTok", Text: "user123 commented: ur content is fire bro 🔥🔥"
        // Title: "TikTok", Text: "user123 replied to your comment: thanks!"
        
        val content = text ?: ""
        
        return when {
            content.contains(" commented: ") -> {
                val parts = content.split(" commented: ", limit = 2)
                TikTokInfo(
                    senderHandle = parts[0].trim(),
                    content = parts[1].trim()
                )
            }
            content.contains(" replied to your comment: ") -> {
                val parts = content.split(" replied to your comment: ", limit = 2)
                TikTokInfo(
                    senderHandle = parts[0].trim(),
                    content = parts[1].trim()
                )
            }
            else -> {
                // Generic fallback if format is different
                TikTokInfo(
                    senderHandle = title,
                    content = text
                )
            }
        }
    }
}
