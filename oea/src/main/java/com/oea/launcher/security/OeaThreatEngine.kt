package com.oea.launcher.security

import java.util.Locale

/**
 * OEA's on-device malware rules — no cloud, no threat feed, no Play Protect.
 *
 * The console used to answer "is this phone safe?" by opening the Play Store. That
 * is an external platform doing the work, and it is not available on a phone
 * without Google services, offline, or on a build like this one. What can be done
 * on the phone itself is to look at what an app *is*: what it asks to see, who
 * signed it, where it came from, and which dangerous capabilities it holds at the
 * same time. A single permission is normal; the combinations below are the shapes
 * real Android malware takes, and each rule says which shape it is and why.
 *
 * This is deliberately a **heuristic** engine, and it says so in the UI: it cannot
 * prove an app is malicious and it cannot prove one is clean. It knows nothing
 * about app code (that is the SHA-256 list's job, [OeaSignatureEngine]) and it
 * never sends anything anywhere. Every rule is pure Kotlin, unit-tested, and
 * bundled in the APK — so it works on the first boot, offline, forever.
 */
object OeaThreatEngine {

    /** How much a finding should worry someone. */
    enum class Severity(val weight: Int) {
        LOW(5),
        MEDIUM(15),
        HIGH(40)
    }

    /** The verdict for one app, in plain words. */
    enum class Verdict { CLEAN, LOW, REVIEW, HIGH }

    /**
     * Everything the rules need to know about one installed app, gathered by
     * [OeaAppAudit] and otherwise Android-free so the rules can be tested.
     */
    data class AppFacts(
        val packageName: String,
        val label: String,
        val isSystem: Boolean = false,
        val isUpdatedSystem: Boolean = false,
        val targetSdk: Int = 0,
        /** The store that installed it, or null for a sideload / unknown. */
        val installer: String? = null,
        val permissions: Set<String> = emptySet(),
        val grantedPermissions: Set<String> = emptySet(),
        val declaresAccessibility: Boolean = false,
        val enabledAccessibility: Boolean = false,
        val declaresNotificationListener: Boolean = false,
        val enabledNotificationListener: Boolean = false,
        val deviceAdmin: Boolean = false,
        val drawsOverOtherApps: Boolean = false,
        val hasLauncherIcon: Boolean = true,
        /** X.509 subject of the signing certificate, or null when unreadable. */
        val signingSubject: String? = null,
        val apkBytes: Long = 0L
    ) {
        val isSideloaded: Boolean get() = installer?.let { it !in STORES } ?: true

        /** True when the app holds (not merely asks for) [permission]. */
        fun holds(permission: String): Boolean =
            permission in grantedPermissions || (permission in permissions && permission in SELF_GRANTED)

        /** True when the app asks for [permission] at all. */
        fun asks(permission: String): Boolean = permission in permissions || permission in grantedPermissions
    }

    /** One thing worth telling the user, tied to the rule that found it. */
    data class Finding(
        val ruleId: String,
        val title: String,
        val detail: String,
        val severity: Severity
    )

    /** The engine's answer for one app. */
    data class Assessment(val facts: AppFacts, val findings: List<Finding>) {
        val score: Int get() = findings.sumOf { it.severity.weight }
        val verdict: Verdict get() = verdictOf(findings)
        val highest: Severity? get() = findings.maxByOrNull { it.severity }?.severity
    }

    /**
     * Permissions that are granted at install time on every supported release —
     * a non-system app that asks for one of these holds it, so the rules can rely
     * on the declaration when the grant table is unavailable.
     */
    private val SELF_GRANTED = setOf(
        "android.permission.RECEIVE_BOOT_COMPLETED",
        "android.permission.REQUEST_INSTALL_PACKAGES",
        "android.permission.FOREGROUND_SERVICE",
        "android.permission.WAKE_LOCK"
    )

    /** Package installers that mean "an app store the user chose". */
    val STORES = setOf(
        "com.android.vending",
        "com.sec.android.app.samsungapps",
        "com.huawei.appmarket",
        "com.xiaomi.mipicks",
        "com.heytap.market",
        "com.oppo.market",
        "com.vivo.appstore",
        "com.amazon.venezia",
        "com.aurora.store",
        "org.fdroid.fdroid"
    )

    // --------------------------------------------------------------- permissions

    private const val READ_SMS = "android.permission.READ_SMS"
    private const val RECEIVE_SMS = "android.permission.RECEIVE_SMS"
    private const val SEND_SMS = "android.permission.SEND_SMS"
    private const val READ_CALL_LOG = "android.permission.READ_CALL_LOG"
    private const val RECORD_AUDIO = "android.permission.RECORD_AUDIO"
    private const val READ_CONTACTS = "android.permission.READ_CONTACTS"
    private const val CAMERA = "android.permission.CAMERA"
    private const val OVERLAY = "android.permission.SYSTEM_ALERT_WINDOW"
    private const val INSTALL_PACKAGES = "android.permission.REQUEST_INSTALL_PACKAGES"
    private const val QUERY_ALL = "android.permission.QUERY_ALL_PACKAGES"

    /**
     * Every rule, in the order they are reported. A rule returns a [Finding] when
     * it applies, and nothing when it does not — a clean app produces no rows.
     */
    private val RULES: List<(AppFacts) -> Finding?> = listOf(
        { app ->
            // The banking-trojan shape: read the text, send the text.
            if (!app.isSystem && app.holds(READ_SMS) && app.holds(SEND_SMS)) {
                Finding(
                    "sms-both-ways",
                    "Can read and send your text messages",
                    "One-time bank and login codes arrive by SMS. An app that can read and " +
                        "send texts can take a code and answer as you. This is the usual way " +
                        "SMS-based accounts are emptied.",
                    Severity.HIGH
                )
            } else null
        },
        { app ->
            // Accessibility is the most abused permission on Android: it can read the
            // screen and press buttons, which is enough to drive a banking app.
            if (!app.isSystem && app.enabledAccessibility && (app.drawsOverOtherApps || app.holds(READ_SMS) || app.enabledNotificationListener)) {
                Finding(
                    "accessibility-plus",
                    "Accessibility control plus screen or message access",
                    "This app has been given Accessibility (it can read the screen and tap " +
                        "for you) together with the ability to draw over other apps, read " +
                        "texts or read notifications. That combination can drive another " +
                        "app's screens while you watch.",
                    Severity.HIGH
                )
            } else null
        },
        { app ->
            // A dropper: show a fake prompt over the screen and install something.
            if (!app.isSystem && app.drawsOverOtherApps && app.asks(INSTALL_PACKAGES)) {
                Finding(
                    "overlay-installer",
                    "Draws over apps and can install other apps",
                    "It can draw on top of whatever you are looking at and start an " +
                        "install. Fake update prompts built this way are how unwanted " +
                        "apps get onto a phone.",
                    Severity.HIGH
                )
            } else null
        },
        { app ->
            if (!app.isSystem && app.deviceAdmin && (app.holds(READ_SMS) || app.drawsOverOtherApps)) {
                Finding(
                    "admin-plus",
                    "Device administrator with message or screen access",
                    "Device administrator rights are what an app uses to lock the screen, " +
                        "change the password or refuse to be uninstalled. Held together " +
                        "with text or screen access this is the shape of screen-locking " +
                        "extortion apps.",
                    Severity.HIGH
                )
            } else null
        },
        { app ->
            if (!app.isSystem && app.enabledNotificationListener && app.holds(READ_SMS)) {
                Finding(
                    "notifications-and-sms",
                    "Reads notifications and text messages",
                    "Login codes now arrive as notifications as often as messages. An app " +
                        "with both can collect them from either route.",
                    Severity.MEDIUM
                )
            } else null
        },
        { app ->
            if (!app.isSystem && app.holds(RECORD_AUDIO) && app.holds(READ_CALL_LOG)) {
                Finding(
                    "mic-and-calls",
                    "Microphone and call history",
                    "It can record audio and read who called you and when. Legitimate for " +
                        "a recorder or a dialler; unusual in one app otherwise.",
                    Severity.MEDIUM
                )
            } else null
        },
        { app ->
            // A debug certificate is public: anyone can sign with it, so nothing it
            // signs can be traced back to a developer.
            val subject = app.signingSubject
            if (subject != null && (subject.contains("CN=Android Debug", ignoreCase = true) || subject.contains("O=Android Debug", ignoreCase = true))) {
                Finding(
                    "debug-signed",
                    "Signed with a public developer (debug) certificate",
                    "Debug keys ship with the Android tools and are not secret, so this " +
                        "app's signature proves nothing about who built it and it cannot " +
                        "receive secure updates. Apps given to you outside a store are " +
                        "often signed this way.",
                    Severity.MEDIUM
                )
            } else null
        },
        { app ->
            if (!app.isSystem && !app.hasLauncherIcon && (app.enabledAccessibility || app.enabledNotificationListener || app.deviceAdmin)) {
                Finding(
                    "hidden-privileged",
                    "No icon, but privileged access",
                    "There is no way to open this app from the launcher, yet it holds " +
                        "Accessibility, notification access or device administrator rights. " +
                        "Hidden apps with that kind of reach are worth understanding before " +
                        "you keep them.",
                    Severity.MEDIUM
                )
            } else null
        },
        { app ->
            if (!app.isSystem && app.isSideloaded) {
                Finding(
                    "sideloaded",
                    "Installed outside an app store",
                    "Android recorded no store as the installer, so this app did not come " +
                        "through Play or another market. That is not a fault on its own — " +
                        "but nothing checked it either.",
                    Severity.LOW
                )
            } else null
        },
        { app ->
            if (!app.isSystem && app.targetSdk in 1..25) {
                Finding(
                    "old-target",
                    "Built for Android 7 or older",
                    "Android only applies its newer permission and privacy rules to apps " +
                        "that declare a newer target (this one declares " +
                        "${app.targetSdk}). Apps this old can reach things a current app " +
                        "cannot.",
                    Severity.LOW
                )
            } else null
        },
        { app ->
            if (!app.isSystem && app.holds(READ_CONTACTS) && app.asks(QUERY_ALL) && app.holds(CAMERA)) {
                Finding(
                    "contacts-camera-catalog",
                    "Contacts, camera and the full app list",
                    "It can read your address book, use the camera and see every app on " +
                        "the phone. Each is common; all three in one utility app is a lot " +
                        "of the phone in one place.",
                    Severity.LOW
                )
            } else null
        }
    )

    /** How many rules are bundled — shown in the UI so the claim is checkable. */
    val ruleCount: Int get() = RULES.size

    /** Every rule that applies to one app. */
    fun assess(facts: AppFacts): Assessment =
        Assessment(facts, RULES.mapNotNull { runCatching { it(facts) }.getOrNull() })

    /**
     * The verdict from a set of findings: any HIGH is HIGH, otherwise two MEDIUMs
     * or one MEDIUM is REVIEW, otherwise a LOW is LOW. Severity is not additive
     * past that — three low findings are still not "high risk".
     */
    fun verdictOf(findings: List<Finding>): Verdict {
        if (findings.isEmpty()) return Verdict.CLEAN
        if (findings.any { it.severity == Severity.HIGH }) return Verdict.HIGH
        if (findings.any { it.severity == Severity.MEDIUM }) return Verdict.REVIEW
        return Verdict.LOW
    }

    /**
     * Assesses a whole phone, honouring the user's allow list.
     *
     * System apps are skipped: they are part of the platform, they cannot be
     * removed, and flagging them would bury the findings that matter. An allowed
     * package is still scanned — it is simply moved to the allowed count, so the
     * report can say "2 apps you allowed" rather than silently forgetting them.
     */
    fun assessInstalled(
        apps: List<AppFacts>,
        allowed: Set<String> = emptySet(),
        ownPackage: String? = null
    ): Report {
        var skipped = 0
        var allowedCount = 0
        val assessed = ArrayList<Assessment>()
        apps.forEach { app ->
            if (app.isSystem || app.packageName == ownPackage) {
                skipped++
                return@forEach
            }
            if (app.packageName in allowed) {
                allowedCount++
                return@forEach
            }
            val assessment = assess(app)
            if (assessment.findings.isNotEmpty()) assessed.add(assessment)
        }
        val order = mapOf(Verdict.HIGH to 0, Verdict.REVIEW to 1, Verdict.LOW to 2, Verdict.CLEAN to 3)
        val sorted = assessed.sortedWith(
            compareBy({ order[it.verdict] ?: 9 }, { -(it.score) }, { it.facts.label.lowercase(Locale.getDefault()) })
        )
        return Report(
            scanned = apps.size,
            skipped = skipped,
            allowed = allowedCount,
            assessments = sorted,
            high = sorted.count { it.verdict == Verdict.HIGH },
            review = sorted.count { it.verdict == Verdict.REVIEW },
            low = sorted.count { it.verdict == Verdict.LOW }
        )
    }

    /** A whole-phone scan result. */
    data class Report(
        val scanned: Int,
        val skipped: Int,
        val allowed: Int,
        val assessments: List<Assessment>,
        val high: Int,
        val review: Int,
        val low: Int
    ) {
        val flagged: Int get() = high + review + low

        /** Apps actually assessed: everything installed minus system and OEA itself. */
        val checked: Int get() = (scanned - skipped).coerceAtLeast(0)
        val verdict: Verdict get() = when {
            high > 0 -> Verdict.HIGH
            review > 0 -> Verdict.REVIEW
            low > 0 -> Verdict.LOW
            else -> Verdict.CLEAN
        }
    }

    // ------------------------------------------------------------------ wording

    fun severityLabel(severity: Severity): String = when (severity) {
        Severity.HIGH -> "High risk"
        Severity.MEDIUM -> "Needs review"
        Severity.LOW -> "Worth a look"
    }

    fun verdictLabel(verdict: Verdict): String = when (verdict) {
        Verdict.HIGH -> "High-risk apps found"
        Verdict.REVIEW -> "Apps need a look"
        Verdict.LOW -> "Nothing urgent"
        Verdict.CLEAN -> "All clear"
    }

    /** The one sentence under the verdict. Never claims the phone is safe. */
    fun verdictSentence(report: Report): String = when (report.verdict) {
        Verdict.HIGH ->
            "${report.high} ${if (report.high == 1) "app combines" else "apps combine"} " +
                "dangerous access. Read each reason before you open it again."
        Verdict.REVIEW ->
            "${report.flagged} ${if (report.flagged == 1) "app has" else "apps have"} access " +
                "worth checking. None of these findings is proof of malware."
        Verdict.LOW ->
            "${report.flagged} ${if (report.flagged == 1) "app is" else "apps are"} worth a look, " +
                "mostly sideloading or age. Nothing here is an emergency."
        Verdict.CLEAN ->
            "Nothing in ${report.checked} ${if (report.checked == 1) "app" else "apps"} matched the " +
                "$ruleCount rules on this phone."
    }
}
