package com.oea.launcher.workspace

import com.oea.launcher.workspace.OeaAppFamilies.Fact
import com.oea.launcher.workspace.OeaAppFamilies.Signal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Smart folders are identified from the phone — shared uid, signing certificate,
 * publisher namespace — and named from the apps themselves. These tests hold that
 * down: no list of family names to keep up to date, and no display label deciding
 * who belongs with whom.
 */
class OeaAppFamiliesTest {

    private fun fact(
        pkg: String,
        label: String,
        uid: Int = 0,
        signature: String = ""
    ) = Fact(
        key = "$pkg/.Main",
        packageName = pkg,
        label = label,
        uid = uid,
        signature = signature
    )

    @Test fun appsSharingOneUidAreOneFamily() {
        val families = OeaAppFamilies.discover(
            listOf(
                fact("com.facebook.katana", "Facebook", uid = 10064),
                fact("com.whatsapp", "WhatsApp", uid = 10064),
                fact("com.instagram.android", "Instagram", uid = 10064),
                fact("com.example.notes", "Notes", uid = 10101)
            )
        )
        assertEquals(1, families.size)
        assertEquals("uid10064", families.single().id)
        assertEquals(Signal.SHARED_UID, families.single().signal)
        assertEquals(3, families.single().members.size)
    }

    @Test fun differentUidsAreNeverFoldedTogether() {
        val families = OeaAppFamilies.discover(
            listOf(
                fact("com.example.a", "One", uid = 1),
                fact("com.example.b", "Two", uid = 2)
            )
        )
        assertTrue(families.isEmpty())
    }

    @Test fun publisherNamespaceGroupsAppsThatShareNoUid() {
        val families = OeaAppFamilies.discover(
            listOf(
                fact("com.transsion.hilauncher", "HiOS Launcher", uid = 10001),
                fact("com.transsion.phoenix", "Phoenix", uid = 10002),
                fact("com.other.thing", "Thing", uid = 10003)
            )
        )
        assertEquals(1, families.size)
        assertEquals("ns_com_transsion", families.single().id)
        assertEquals("Transsion", families.single().title)
        assertEquals(Signal.NAMESPACE, families.single().signal)
    }

    @Test fun oneAppIsNotAFamily() {
        assertTrue(OeaAppFamilies.discover(listOf(fact("com.example.only", "Only"))).isEmpty())
        assertTrue(OeaAppFamilies.discover(emptyList()).isEmpty())
    }

    @Test fun namespaceIsOnlyUsedWhenItDescribesAPublisher() {
        assertNull(OeaAppFamilies.namespaceOf("com.example"))
        assertNull(OeaAppFamilies.namespaceOf("com.android.chrome"))
        assertNull(OeaAppFamilies.namespaceOf("com.android.settings"))
        assertEquals("com.transsion", OeaAppFamilies.namespaceOf("com.transsion.hilauncher"))
    }

    @Test fun aSignatureGroupsWhatTheOtherSignalsLeftApart() {
        var looked = 0
        val facts = listOf(
            fact("com.acme.one", "Acme One", uid = 1),
            fact("org.acme.two", "Acme Two", uid = 2),
            fact("com.other.three", "Other", uid = 3)
        )
        val families = OeaAppFamilies.discover(facts, signatureOf = { pkg ->
            looked++
            if (pkg.startsWith("com.acme") || pkg.startsWith("org.acme")) "abcd1234" else "zzzz9999"
        })
        assertEquals(1, families.size)
        assertEquals(Signal.SHARED_SIGNATURE, families.single().signal)
        assertEquals(2, families.single().members.size)
        // The certificate is read for leftovers only, once each — not for every app.
        assertEquals(3, looked)
    }

    @Test fun theCertificateIsNotReadWhenUidsAlreadyAnswerIt() {
        var looked = 0
        OeaAppFamilies.discover(
            listOf(
                fact("com.facebook.katana", "Facebook", uid = 10064),
                fact("com.whatsapp", "WhatsApp", uid = 10064)
            ),
            signatureOf = { looked++; "sig" }
        )
        assertEquals(0, looked)
    }

    @Test fun labelsNameAFamilyButNeverDecideMembership() {
        val named = OeaAppFamilies.discover(
            listOf(
                fact("com.google.android.apps.photos", "Google Photos", uid = 1),
                fact("com.google.android.gm", "Google Mail", uid = 2)
            )
        )
        assertEquals("Google", named.single().title)

        // Identical labels, different publishers: still two separate apps.
        val sameLabel = OeaAppFamilies.discover(
            listOf(
                fact("com.one.notes", "Notes", uid = 11),
                fact("com.two.notes", "Notes", uid = 22)
            )
        )
        assertTrue(sameLabel.isEmpty())
    }

    @Test fun aFamilyAlwaysGetsANameAndASafeId() {
        val families = OeaAppFamilies.discover(
            listOf(
                fact("com.example.x", "X", uid = 7),
                fact("com.example.y", "Y", uid = 7)
            )
        )
        val family = families.single()
        assertTrue(family.title.isNotBlank())
        assertTrue(family.id.all { it.isLetterOrDigit() || it == '_' })
    }

    @Test fun idsAreSanitisedForPreferenceKeys() {
        assertEquals("ns_com_transsion", OeaAppFamilies.sanitize("ns:com.transsion"))
        assertEquals("family", OeaAppFamilies.sanitize(""))
    }
}
