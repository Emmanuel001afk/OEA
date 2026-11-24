package com.oea.launcher.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules behind the console's antivirus.
 *
 * Every one of these runs on the phone, from data the phone already has — the
 * point of the pass is that "is this app dangerous?" no longer needs a cloud
 * service, a threat feed or Play Protect to answer at all.
 */
class OeaThreatEngineTest {

    private val store = "com.android.vending"

    private fun app(
        packageName: String = "com.example.app",
        label: String = "Example",
        isSystem: Boolean = false,
        targetSdk: Int = 34,
        installer: String? = store,
        granted: Set<String> = emptySet(),
        requested: Set<String> = emptySet(),
        accessibility: Boolean = false,
        notificationListener: Boolean = false,
        admin: Boolean = false,
        overlay: Boolean = false,
        launcherIcon: Boolean = true,
        signingSubject: String? = "CN=Example Ltd, O=Example, C=NG"
    ) = OeaThreatEngine.AppFacts(
        packageName = packageName,
        label = label,
        isSystem = isSystem,
        targetSdk = targetSdk,
        installer = installer,
        permissions = requested + granted,
        grantedPermissions = granted,
        declaresAccessibility = accessibility,
        enabledAccessibility = accessibility,
        declaresNotificationListener = notificationListener,
        enabledNotificationListener = notificationListener,
        deviceAdmin = admin,
        drawsOverOtherApps = overlay,
        hasLauncherIcon = launcherIcon,
        signingSubject = signingSubject
    )

    private fun ids(facts: OeaThreatEngine.AppFacts): List<String> =
        OeaThreatEngine.assess(facts).findings.map { it.ruleId }

    private val readSms = "android.permission.READ_SMS"
    private val sendSms = "android.permission.SEND_SMS"

    // -------------------------------------------------------------- the dangerous few

    @Test
    fun anAppThatReadsAndSendsTextsIsHighRisk() {
        val found = OeaThreatEngine.assess(app(granted = setOf(readSms, sendSms)))
        assertEquals(listOf("sms-both-ways"), found.findings.map { it.ruleId })
        assertEquals(OeaThreatEngine.Verdict.HIGH, found.verdict)
        assertEquals(OeaThreatEngine.Severity.HIGH, found.highest)
        assertTrue(found.findings.first().detail.contains("code"))
    }

    @Test
    fun askingWithoutHoldingIsNotHolding() {
        // Declared in the manifest, never granted: the app cannot read a text today.
        val facts = app(requested = setOf(readSms, sendSms))
        assertEquals(emptyList<String>(), ids(facts))
        assertEquals(OeaThreatEngine.Verdict.CLEAN, OeaThreatEngine.assess(facts).verdict)
    }

    @Test
    fun installationTimePermissionsCountAsHeld() {
        // RECEIVE_BOOT_COMPLETED is granted at install, so a declaration is enough —
        // and it is not part of any rule, which this checks by using it on its own.
        val facts = app(requested = setOf("android.permission.RECEIVE_BOOT_COMPLETED"))
        assertTrue(facts.holds("android.permission.RECEIVE_BOOT_COMPLETED"))
        assertFalse(facts.holds(readSms))
        assertEquals(emptyList<String>(), ids(facts))
    }

    @Test
    fun accessibilityPlusAnotherDangerousReachIsHighRisk() {
        val facts = app(granted = setOf(readSms), accessibility = true)
        assertTrue(ids(facts).contains("accessibility-plus"))
        assertEquals(OeaThreatEngine.Verdict.HIGH, OeaThreatEngine.assess(facts).verdict)
    }

    @Test
    fun accessibilityAloneIsNotAFinding() {
        // A screen reader or a switch-access app is accessibility and nothing else.
        assertEquals(emptyList<String>(), ids(app(accessibility = true)))
    }

    @Test
    fun anOverlayThatCanInstallAppsIsADropper() {
        val facts = app(overlay = true, requested = setOf("android.permission.REQUEST_INSTALL_PACKAGES"))
        assertEquals(listOf("overlay-installer"), ids(facts))
    }

    @Test
    fun administratorRightsWithMessageAccessIsTheExtortionShape() {
        val facts = app(admin = true, granted = setOf(readSms))
        assertTrue(ids(facts).contains("admin-plus"))
        assertEquals(OeaThreatEngine.Verdict.HIGH, OeaThreatEngine.assess(facts).verdict)
    }

    @Test
    fun notificationAccessWithMessagesIsWorthReviewing() {
        val facts = app(notificationListener = true, granted = setOf(readSms))
        assertEquals(listOf("notifications-and-sms"), ids(facts))
        assertEquals(OeaThreatEngine.Verdict.REVIEW, OeaThreatEngine.assess(facts).verdict)
    }

    @Test
    fun microphoneAndCallHistoryIsWorthReviewing() {
        val facts = app(
            granted = setOf("android.permission.RECORD_AUDIO", "android.permission.READ_CALL_LOG")
        )
        assertEquals(listOf("mic-and-calls"), ids(facts))
    }

    // ------------------------------------------------------------------- signers

    @Test
    fun aDebugCertificateMeansTheSignatureProvesNothing() {
        val facts = app(signingSubject = "CN=Android Debug,O=Android,C=US")
        assertEquals(listOf("debug-signed"), ids(facts))
    }

    @Test
    fun aReleaseCertificateIsNotFlagged() {
        assertEquals(emptyList<String>(), ids(app(signingSubject = "CN=Example Ltd, O=Example, C=NG")))
        // An unreadable certificate is not evidence either.
        assertEquals(emptyList<String>(), ids(app(signingSubject = null)))
    }

    // ------------------------------------------------------- provenance and age

    @Test
    fun aHiddenAppWithPrivilegedAccessIsFlagged() {
        val facts = app(launcherIcon = false, accessibility = true)
        assertTrue(ids(facts).contains("hidden-privileged"))
    }

    @Test
    fun aHiddenAppWithNoPrivilegesIsJustHidden() {
        assertEquals(emptyList<String>(), ids(app(launcherIcon = false)))
    }

    @Test
    fun aStoreInstallIsNotSideloadedAndAnUnknownOneIs() {
        assertEquals(emptyList<String>(), ids(app(installer = store)))
        assertEquals(listOf("sideloaded"), ids(app(installer = null)))
        // The system installer is how a sideload happens, not a store.
        assertEquals(listOf("sideloaded"), ids(app(installer = "com.android.packageinstaller")))
        assertEquals(OeaThreatEngine.Verdict.LOW, OeaThreatEngine.assess(app(installer = null)).verdict)
    }

    @Test
    fun anAppBuiltForAndroidSevenOrOlderIsFlagged() {
        assertEquals(listOf("old-target"), ids(app(targetSdk = 25)))
        assertEquals(listOf("old-target"), ids(app(targetSdk = 24)))
        assertEquals(emptyList<String>(), ids(app(targetSdk = 26)))
        assertEquals(emptyList<String>(), ids(app(targetSdk = 35)))
    }

    // ------------------------------------------------------------------- verdicts

    @Test
    fun systemAppsArePartOfThePlatformNotSuspects() {
        val report = OeaThreatEngine.assessInstalled(
            listOf(
                app(packageName = "com.android.systemui", isSystem = true, granted = setOf(readSms, sendSms)),
                app(packageName = "com.example.clean")
            ),
            ownPackage = "com.oea.launcher"
        )
        assertEquals(0, report.flagged)
        // One system app skipped; the second app was checked and had nothing to say.
        assertEquals(1, report.skipped)
        assertEquals(1, report.checked)
        assertEquals(OeaThreatEngine.Verdict.CLEAN, report.verdict)
    }

    @Test
    fun oeaDoesNotAuditItself() {
        val report = OeaThreatEngine.assessInstalled(
            listOf(app(packageName = "com.oea.launcher", granted = setOf(readSms, sendSms))),
            ownPackage = "com.oea.launcher"
        )
        assertEquals(0, report.flagged)
        assertEquals(1, report.skipped)
    }

    @Test
    fun anAllowedAppLeavesTheListButIsCounted() {
        val risky = app(packageName = "com.example.risky", granted = setOf(readSms, sendSms))
        val first = OeaThreatEngine.assessInstalled(listOf(risky), ownPackage = "com.oea.launcher")
        assertEquals(1, first.high)
        val second = OeaThreatEngine.assessInstalled(listOf(risky), allowed = setOf("com.example.risky"), ownPackage = "com.oea.launcher")
        assertEquals(0, second.high)
        assertEquals(1, second.allowed)
        assertEquals(OeaThreatEngine.Verdict.CLEAN, second.verdict)
    }

    @Test
    fun theWorstAppInThePhoneTripsEveryRule() {
        val nightmare = app(
            packageName = "com.example.nightmare",
            label = "Free Music",
            targetSdk = 22,
            installer = null,
            granted = setOf(
                readSms, sendSms,
                "android.permission.RECORD_AUDIO",
                "android.permission.READ_CALL_LOG",
                "android.permission.READ_CONTACTS",
                "android.permission.CAMERA",
                "android.permission.QUERY_ALL_PACKAGES"
            ),
            requested = setOf("android.permission.REQUEST_INSTALL_PACKAGES", "android.permission.QUERY_ALL_PACKAGES"),
            accessibility = true,
            notificationListener = true,
            admin = true,
            overlay = true,
            launcherIcon = false,
            signingSubject = "CN=Android Debug,O=Android,C=US"
        )
        val found = OeaThreatEngine.assess(nightmare)
        assertEquals(OeaThreatEngine.ruleCount, found.findings.size)
        assertEquals(found.findings.size, found.findings.map { it.ruleId }.distinct().size)
        assertEquals(OeaThreatEngine.Verdict.HIGH, found.verdict)
        assertTrue(found.score >= 40)
    }

    @Test
    fun aCleanUtilityAppIsSilent() {
        // The kind of app the rules must not nag about: a store install, current
        // target, one ordinary dangerous permission, a release certificate.
        val facts = app(
            packageName = "com.example.notes",
            granted = setOf("android.permission.CAMERA"),
            targetSdk = 34
        )
        assertEquals(emptyList<String>(), ids(facts))
    }

    @Test
    fun findingsAreSortedWorstFirstAndThenByScore() {
        val report = OeaThreatEngine.assessInstalled(
            listOf(
                app(packageName = "com.example.low", label = "Low", installer = null),
                app(packageName = "com.example.review", label = "Review", granted = setOf("android.permission.RECORD_AUDIO", "android.permission.READ_CALL_LOG")),
                app(packageName = "com.example.high", label = "High", granted = setOf(readSms, sendSms)),
                app(packageName = "com.example.clean", label = "Clean")
            ),
            ownPackage = "com.oea.launcher"
        )
        assertEquals(
            listOf("com.example.high", "com.example.review", "com.example.low"),
            report.assessments.map { it.facts.packageName }
        )
        assertEquals(1, report.high)
        assertEquals(1, report.review)
        assertEquals(1, report.low)
        assertEquals(3, report.flagged)
    }

    @Test
    fun threeSmallFindingsAreNotAHighRiskVerdict() {
        // Severity is not a sum: three low findings stay low.
        val findings = listOf(
            OeaThreatEngine.Finding("a", "a", "a", OeaThreatEngine.Severity.LOW),
            OeaThreatEngine.Finding("b", "b", "b", OeaThreatEngine.Severity.LOW),
            OeaThreatEngine.Finding("c", "c", "c", OeaThreatEngine.Severity.LOW)
        )
        assertEquals(OeaThreatEngine.Verdict.LOW, OeaThreatEngine.verdictOf(findings))
        assertEquals(
            OeaThreatEngine.Verdict.REVIEW,
            OeaThreatEngine.verdictOf(findings + OeaThreatEngine.Finding("d", "d", "d", OeaThreatEngine.Severity.MEDIUM))
        )
        assertEquals(OeaThreatEngine.Verdict.CLEAN, OeaThreatEngine.verdictOf(emptyList()))
        assertNull(OeaThreatEngine.assess(app()).highest)
    }

    // -------------------------------------------------------------------- wording

    @Test
    fun theReportNeverClaimsThePhoneIsSafe() {
        val clean = OeaThreatEngine.assessInstalled(listOf(app()), ownPackage = "com.oea.launcher")
        assertTrue(OeaThreatEngine.verdictSentence(clean).contains("${OeaThreatEngine.ruleCount} rules"))
        val high = OeaThreatEngine.assessInstalled(
            listOf(app(granted = setOf(readSms, sendSms))), ownPackage = "com.oea.launcher"
        )
        val sentence = OeaThreatEngine.verdictSentence(high)
        assertTrue(sentence.contains("dangerous"))
        assertFalse(sentence.contains("safe"))
        assertEquals("High-risk apps found", OeaThreatEngine.verdictLabel(high.verdict))
        assertEquals("All clear", OeaThreatEngine.verdictLabel(clean.verdict))
    }

    @Test
    fun everySeverityHasAWord() {
        assertEquals("High risk", OeaThreatEngine.severityLabel(OeaThreatEngine.Severity.HIGH))
        assertEquals("Needs review", OeaThreatEngine.severityLabel(OeaThreatEngine.Severity.MEDIUM))
        assertEquals("Worth a look", OeaThreatEngine.severityLabel(OeaThreatEngine.Severity.LOW))
    }
}
