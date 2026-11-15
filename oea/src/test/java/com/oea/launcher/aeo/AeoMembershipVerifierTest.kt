package com.oea.launcher.aeo

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class AeoMembershipVerifierTest {
    private val now = 1_800_000_000L
    private val pair: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val kid = "aeo-issuer-test-01"
    private val issuer = "https://identity.aeo.example"
    private val publicRaw = pair.public.encoded.copyOfRange(pair.public.encoded.size - 32, pair.public.encoded.size)
    private val key = AeoMembershipVerifier.TrustedIssuerKey(
        kid = kid, issuer = issuer, publicKey = publicRaw,
        notBefore = now - 10_000, notAfter = now + 1_000_000
    )

    private fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun part(value: JSONObject) = b64(value.toString().toByteArray(StandardCharsets.UTF_8))

    private fun claims() = JSONObject()
        .put("iss", issuer)
        .put("aud", AeoMembershipVerifier.FRAMEWORK_AUDIENCE)
        .put("ecosystem", "AEO")
        .put("credential_type", "aeo-membership")
        .put("credential_version", 1)
        .put("sub", "com.oea.launcher")
        .put("platform", JSONObject().put("id", "com.oea.launcher").put("name", "EMA"))
        .put("publisher", JSONObject().put("id", "com.aeo.platforms").put("key_id", "ema-test-key"))
        .put("scope", org.json.JSONArray().put("member"))
        .put("jti", "7f4e8c1a-4b8d-4c5a-9e2d-2f7a8b9c0d1e")
        .put("iat", now - 60)
        .put("nbf", now - 60)
        .put("exp", now + 86_340)

    private fun sign(payload: JSONObject = claims()): String {
        val header = JSONObject()
            .put("alg", "EdDSA")
            .put("typ", AeoMembershipVerifier.MEMBERSHIP_TYPE)
            .put("kid", kid)
        val input = "${part(header)}.${part(payload)}"
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(pair.private)
        signer.update(input.toByteArray(StandardCharsets.US_ASCII))
        return "$input.${b64(signer.sign())}"
    }

    private fun verify(token: String, keys: List<AeoMembershipVerifier.TrustedIssuerKey> = listOf(key),
                       revoked: Boolean = false, api: Int = 33) =
        AeoMembershipVerifier.verify(token, keys, { _, _, _ -> revoked }, now, apiLevel = api)


    private val rootPair: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val rootKid = "aeo-root-test-01"
    private val rootRaw = rootPair.public.encoded.copyOfRange(rootPair.public.encoded.size - 32, rootPair.public.encoded.size)
    private val pinnedRoot = AeoMembershipVerifier.PinnedRootKey(
        kid = rootKid, publicKey = rootRaw, notBefore = now - 10_000, notAfter = now + 10_000_000
    )

    private fun signTrustBundle(version: Long = 7): String {
        val header = JSONObject().put("alg", "EdDSA")
            .put("typ", AeoMembershipVerifier.TRUST_BUNDLE_TYPE).put("kid", rootKid)
        val keys = org.json.JSONArray().put(JSONObject()
            .put("kid", kid).put("issuer", issuer).put("algorithm", "Ed25519")
            .put("publicKey", b64(publicRaw)).put("notBefore", now - 10_000)
            .put("notAfter", now + 1_000_000))
        val payload = JSONObject().put("iss", "AEO")
            .put("aud", AeoMembershipVerifier.TRUST_BUNDLE_AUDIENCE)
            .put("bundle_type", "aeo-trust-bundle").put("bundle_version", 1)
            .put("version", version).put("iat", now - 30).put("nbf", now - 30)
            .put("exp", now + 86_370).put("keys", keys)
            .put("revokedCredentialIds", org.json.JSONArray())
        val input = "${part(header)}.${part(payload)}"
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(rootPair.private)
        signer.update(input.toByteArray(StandardCharsets.US_ASCII))
        return "$input.${b64(signer.sign())}"
    }

    @Test fun verifiesRootSignedTrustBundle() {
        val result = AeoMembershipVerifier.verifyTrustBundle(
            signTrustBundle(), listOf(pinnedRoot), nowSeconds = now, apiLevel = 33
        )
        assertTrue(result is AeoMembershipVerifier.Result.Success<*>)
        val bundle = (result as AeoMembershipVerifier.Result.Success<*>).value as AeoMembershipVerifier.VerifiedTrustBundle
        assertEquals(7L, bundle.version)
        assertEquals(kid, bundle.trustedKeys.single().kid)
    }

    @Test fun rejectsTrustBundleRollbackAndUnknownRoot() {
        val rollback = AeoMembershipVerifier.verifyTrustBundle(
            signTrustBundle(7), listOf(pinnedRoot), minimumVersion = 8,
            nowSeconds = now, apiLevel = 33
        )
        assertTrue(rollback is AeoMembershipVerifier.Result.Failure)
        val unknownRoot = AeoMembershipVerifier.verifyTrustBundle(
            signTrustBundle(), emptyList(), nowSeconds = now, apiLevel = 33
        )
        assertTrue(unknownRoot is AeoMembershipVerifier.Result.Failure)
    }

    @Test fun acceptsValidMembershipCredential() {
        val result = verify(sign())
        assertTrue(result is AeoMembershipVerifier.Result.Success<*>)
        val membership = (result as AeoMembershipVerifier.Result.Success<*>).value as AeoMembershipVerifier.Membership
        assertEquals("com.oea.launcher", membership.platformId)
        assertEquals("AEO", membership.ecosystem)
    }

    @Test fun rejectsTamperedSignature() {
        val token = sign()
        val changed = token.dropLast(1) + if (token.last() == 'A') "B" else "A"
        assertTrue(verify(changed) is AeoMembershipVerifier.Result.Failure)
    }

    @Test fun rejectsUnknownIssuerKey() {
        assertTrue(verify(sign(), emptyList()) is AeoMembershipVerifier.Result.Failure)
    }

    @Test fun rejectsRevokedCredential() {
        val result = verify(sign(), revoked = true)
        assertTrue(result is AeoMembershipVerifier.Result.Failure)
        assertEquals("credential_revoked_or_unknown", (result as AeoMembershipVerifier.Result.Failure).code)
    }

    @Test fun rejectsWrongAudience() {
        val result = verify(sign(claims().put("aud", "urn:wrong")))
        assertTrue(result is AeoMembershipVerifier.Result.Failure)
    }

    @Test fun rejectsUnsupportedAndroidApiWithoutDowngrade() {
        val result = verify(sign(), api = 32)
        assertTrue(result is AeoMembershipVerifier.Result.Failure)
        assertEquals("crypto_unavailable", (result as AeoMembershipVerifier.Result.Failure).code)
    }

    @Test fun verifiesRegistrationEnvelopeAndIdentityBinding() {
        val token = sign()
        val registration = JSONObject()
            .put("ecosystem", "AEO")
            .put("protocol", AeoMembershipVerifier.PROTOCOL)
            .put("platformId", "com.oea.launcher")
            .put("displayName", "EMA")
            .put("membershipCredential", token)
        assertTrue(AeoMembershipVerifier.verifyRegistration(
            registration, listOf(key), { _, _, _ -> false }, now, apiLevel = 33
        ) is AeoMembershipVerifier.Result.Success<*>)

        registration.put("displayName", "Other App")
        assertTrue(AeoMembershipVerifier.verifyRegistration(
            registration, listOf(key), { _, _, _ -> false }, now, apiLevel = 33
        ) is AeoMembershipVerifier.Result.Failure)
    }
}
