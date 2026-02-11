package com.example.notificationcapture

import android.app.Notification
import android.os.Build
import android.service.notification.StatusBarNotification

/**
 * Notification classification. Protected types are captured but NOT cancelled.
 */
enum class NotificationType(val isProtected: Boolean) {
    CALL(false),
    MESSAGE(false),
    MEDIA(true),         // Protected
    NAVIGATION(true),    // Protected
    FITNESS(true),       // Protected
    ALARM(true),         // Protected
    PROGRESS(false),
    MONEY(false),        // Financial transactions (HIGH feature)
    STANDARD(false);

    companion object {
        private val navPkgs = setOf("com.google.android.apps.maps", "com.waze")
        private val fitPkgs = setOf("com.google.android.apps.fitness", "com.strava", "com.fitbit.FitbitMobile")

        // Money/payment apps (HIGH feature)
        private val moneyPkgs = setOf(
            "com.venmo",
            "com.squareup.cash",                  // Cash App
            "com.paypal.android.p2pmobile",       // PayPal
            "com.google.android.apps.walletnfcrel",  // Google Wallet
            "com.samsung.android.spay",           // Samsung Pay
            "com.samsung.android.samsungpay.gear",
            "com.zellepay.zelle",
            "com.apple.android.wallet",           // Apple Pay (if exists)
            "com.chase.sig.android",              // Chase
            "com.bankofamerica.cashpromobile"     // Bank of America
        )

        fun classify(sbn: StatusBarNotification): NotificationType {
            val n = sbn.notification
            val extras = n.extras
            val cat = n.category
            val pkg = sbn.packageName
            val template = extras?.getString(Notification.EXTRA_TEMPLATE) ?: ""

            return when {
                cat == Notification.CATEGORY_CALL -> CALL
                template.contains("MediaStyle", true) || cat == Notification.CATEGORY_TRANSPORT -> MEDIA
                cat == Notification.CATEGORY_NAVIGATION || pkg in navPkgs -> NAVIGATION
                (Build.VERSION.SDK_INT >= 28 && cat == Notification.CATEGORY_WORKOUT) || pkg in fitPkgs -> FITNESS
                cat in listOf(Notification.CATEGORY_ALARM, Notification.CATEGORY_STOPWATCH) -> ALARM
                extras?.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER) == true -> ALARM
                pkg in moneyPkgs -> MONEY
                template.contains("MessagingStyle", true) || cat == Notification.CATEGORY_MESSAGE -> MESSAGE
                (extras?.getInt(Notification.EXTRA_PROGRESS_MAX, 0) ?: 0) > 0 -> PROGRESS
                else -> STANDARD
            }
        }
    }
}

/**
 * Source of notification - local Android or remote API.
 */
enum class NotificationSource(val remotePkgs: Set<String> = emptySet()) {
    LOCAL,
    SLACK(setOf("com.Slack")),
    MS_TEAMS(setOf("com.microsoft.teams")),
    DISCORD(setOf("com.discord")),
    GITHUB(setOf("com.github.android"));

    companion object {
        private val pkgMap by lazy {
            entries.filter { it != LOCAL }.flatMap { src -> src.remotePkgs.map { it to src } }.toMap()
        }
        fun fromPackage(pkg: String): NotificationSource? = pkgMap[pkg]
    }
}
