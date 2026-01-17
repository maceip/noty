package com.example.notificationcapture

/**
 * Parsed information from a financial notification.
 */
data class MoneyInfo(
    val type: String,           // PAYMENT, REQUEST, BALANCE, DEPOSIT, TRANSACTION
    val amount: Double?,
    val currency: String,
    val requiresAction: Boolean
)

/**
 * Parser for financial/payment notification content.
 * Extracts transaction type, amounts, and action requirements.
 */
object MoneyNotificationParser {

    // Pattern to match currency amounts like $50, $1,234.56, 50 USD, etc.
    private val amountPattern = Regex(
        """\$[\d,]+\.?\d*|[\d,]+\.?\d*\s*(?:USD|EUR|GBP|CAD|AUD|JPY)|€[\d,]+\.?\d*|£[\d,]+\.?\d*"""
    )

    // Keywords for transaction type detection
    private val requestKeywords = listOf("request", "asking", "requested", "wants", "owes")
    private val paymentKeywords = listOf("paid", "sent", "transferred", "payment to", "you paid")
    private val depositKeywords = listOf("received", "deposited", "deposit", "credited", "you got", "sent you")
    private val balanceKeywords = listOf("balance", "available", "current balance", "account balance")

    // Keywords that indicate user action is required
    private val actionKeywords = listOf(
        "accept", "decline", "approve", "reject", "confirm", "complete",
        "review", "respond", "action required", "pending your", "waiting for you",
        "tap to", "click to", "swipe to"
    )

    /**
     * Parse a financial notification to extract structured money info.
     *
     * @param pkg Package name of the source app (for app-specific parsing)
     * @param title Notification title
     * @param text Notification text/body
     * @return MoneyInfo if parseable, null if not a recognizable money notification
     */
    fun parse(pkg: String, title: String?, text: String?): MoneyInfo? {
        val content = "${title ?: ""} ${text ?: ""}".lowercase()

        if (content.isBlank()) return null

        // Determine transaction type
        val type = when {
            requestKeywords.any { content.contains(it) } -> "REQUEST"
            paymentKeywords.any { content.contains(it) } -> "PAYMENT"
            depositKeywords.any { content.contains(it) } -> "DEPOSIT"
            balanceKeywords.any { content.contains(it) } -> "BALANCE"
            else -> "TRANSACTION"
        }

        // Check if action is required
        val requiresAction = type == "REQUEST" ||
            actionKeywords.any { content.contains(it) }

        // Extract amount
        val amount = extractAmount(content)

        // Detect currency (default USD for US payment apps)
        val currency = detectCurrency(content, pkg)

        return MoneyInfo(
            type = type,
            amount = amount,
            currency = currency,
            requiresAction = requiresAction
        )
    }

    // P0 FIX: Maximum amount to prevent overflow issues
    private const val MAX_AMOUNT = 999_999_999.99

    /**
     * Extract monetary amount from text.
     * P0 FIX: Added validation for multiple decimal points and bounds checking.
     */
    private fun extractAmount(content: String): Double? {
        val match = amountPattern.find(content) ?: return null
        val amountStr = match.value
            .replace(Regex("[^\\d.]"), "") // Remove everything except digits and decimal
            .trim()

        // P0 FIX: Reject malformed amounts with multiple decimal points
        if (amountStr.count { it == '.' } > 1) {
            return null
        }

        // P0 FIX: Reject empty or invalid strings
        if (amountStr.isEmpty() || amountStr == ".") {
            return null
        }

        val amount = amountStr.toDoubleOrNull() ?: return null

        // P0 FIX: Bounds validation to prevent overflow in sum calculations
        if (amount < 0 || amount > MAX_AMOUNT) {
            return null
        }

        // P0 FIX: Reject NaN or Infinity
        if (amount.isNaN() || amount.isInfinite()) {
            return null
        }

        return amount
    }

    /**
     * Detect currency from content or package name.
     */
    private fun detectCurrency(content: String, pkg: String): String {
        return when {
            content.contains("€") || content.contains("eur", ignoreCase = true) -> "EUR"
            content.contains("£") || content.contains("gbp", ignoreCase = true) -> "GBP"
            content.contains("cad", ignoreCase = true) -> "CAD"
            content.contains("aud", ignoreCase = true) -> "AUD"
            content.contains("jpy", ignoreCase = true) || content.contains("¥") -> "JPY"
            // Default to USD for common US payment apps
            pkg in setOf(
                "com.venmo",
                "com.squareup.cash",
                "com.paypal.android.p2pmobile",
                "com.zellepay.zelle"
            ) -> "USD"
            else -> "USD"
        }
    }

    /**
     * Check if notification content likely represents a payment request that needs response.
     */
    fun isActionRequired(title: String?, text: String?): Boolean {
        val content = "${title ?: ""} ${text ?: ""}".lowercase()
        return actionKeywords.any { content.contains(it) }
    }

    /**
     * Get a summary description for the money notification type.
     */
    fun getTypeDescription(type: String): String = when (type) {
        "REQUEST" -> "Payment Request"
        "PAYMENT" -> "Payment Sent"
        "DEPOSIT" -> "Money Received"
        "BALANCE" -> "Balance Update"
        else -> "Transaction"
    }
}
