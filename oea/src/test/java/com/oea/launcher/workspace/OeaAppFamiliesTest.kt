package com.oea.launcher.workspace

import com.oea.launcher.workspace.OeaAppFamilies.Fact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Smart folders are one publisher's namespace with two or more of its apps
 * installed — nothing written down, and nothing that every app on the phone shares.
 *
 * The second half matters most: the first version of this grouped by Android uid and
 * signing certificate, and on a real phone that put the Camera, the SIM Toolkit and
 * the update service in one folder, because preinstalled apps share the platform's
 * key. These tests hold that door shut.
 */
class OeaAppFamiliesTest {

    private fun fact(pkg: String, label: String) = Fact(key = "$pkg/.Main", packageName = pkg, label = label)

    @Test fun aPublisherNamespaceWithTwoAppsIsAFamily() {
        val families = OeaAppFamilies.discover(
            listOf(
                fact("com.google.android.apps.photos", "Google Photos"),
                fact("com.google.android.gm", "Google Mail"),
                fact("com.google.android.youtube", "YouTube")
            )
        )
        assertEquals(1, families.size)
        assertEquals("ns_com_google_android", families.single().id)
        assertEquals("Google", families.single().title)
        assertEquals(3, families.single().members.size)
        assertEquals("com.google.android", families.single().namespace)
    }

    @Test fun platformAppsThatShareTheSystemKeysNeverFormAFolder() {
        // Camera, SIM Toolkit and the update service have nothing to do with each
        // other. They share a signing key and often one of Android's reserved uids;
        // neither is allowed to make a family out of them.
        val families = OeaAppFamilies.discover(
            listOf(
                fact("com.android.camera", "Camera"),
                fact("com.android.stk", "SIM Toolkit"),
                fact("com.android.settings", "Settings"),
                fact("com.transsion.camera", "Camera")
            )
        )
        assertTrue(families.isEmpty())
    }

    @Test fun oneAppIsNotAFamily() {
        assertTrue(OeaAppFamilies.discover(listOf(fact("com.example.only", "Only"))).isEmpty())
        assertTrue(OeaAppFamilies.discover(emptyList()).isEmpty())
    }

    @Test fun aNamespaceNeedsAThirdSegment() {
        assertNull(OeaAppFamilies.namespaceOf("com.example"))
        assertNull(OeaAppFamilies.namespaceOf("com.android.chrome"))
        assertEquals("com.transsion", OeaAppFamilies.namespaceOf("com.transsion.hilauncher"))
    }

    @Test fun twoAppsOfOnePublisherAreOneFamilyAThirdStrangerIsNot() {
        val families = OeaAppFamilies.discover(
            listOf(
                fact("com.microsoft.office.word", "Word"),
                fact("com.microsoft.office.excel", "Excel"),
                fact("com.other.thing", "Thing")
            )
        )
        assertEquals(1, families.size)
        assertEquals(2, families.single().members.size)
        assertTrue(families.single().members.none { it.startsWith("com.other.") })
    }

    @Test fun theTitleComesFromTheAppsWhenTheyAgree() {
        assertEquals(
            "Google",
            OeaAppFamilies.discover(
                listOf(
                    fact("com.google.android.apps.photos", "Google Photos"),
                    fact("com.google.android.gm", "Google Mail")
                )
            ).single().title
        )
        // Labels that share nothing fall back to the publisher's own name.
        assertEquals(
            "Transsion",
            OeaAppFamilies.discover(
                listOf(
                    fact("com.transsion.phoenix", "Phoenix"),
                    fact("com.transsion.phonemaster", "Phone Master")
                )
            ).single().title
        )
    }

    @Test fun labelsNeverDecideMembership() {
        // Identical labels, different publishers: two apps, no folder.
        assertTrue(
            OeaAppFamilies.discover(
                listOf(
                    fact("com.one.notes", "Notes"),
                    fact("com.two.notes", "Notes")
                )
            ).isEmpty()
        )
    }

    @Test fun onePackageIsOneAppEvenWithTwoActivities() {
        val facts = listOf(
            Fact("com.pub.app/.Main", "com.pub.app", "App"),
            Fact("com.pub.app/.Other", "com.pub.app", "App")
        )
        assertTrue(OeaAppFamilies.discover(facts).isEmpty())
    }

    @Test fun idsAreSanitisedForPreferenceKeys() {
        assertEquals("ns_com_transsion", OeaAppFamilies.sanitize("ns:com.transsion"))
        assertEquals("family", OeaAppFamilies.sanitize(""))
    }
}
