package com.oea.launcher.aeo

import android.os.Build
import java.util.Base64
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Local AEO membership verifier for EMA.
 *
 * Membership proves ecosystem registration only. It does not authenticate a
 * live device, grant capability access, or represent user consent.
 *
 * Android's built-in Ed25519 JCA provider is available from API 33. On older
 * devices this verifier fails closed; callers must not downgrade algorithms.
 */
object AeoMembershipVerifier {
    const val PROTOCOL = "uccs/1"
    const val MEMBERSHIP_TYPE = "AEO-MEMBERSHIP+jwt"
    const val FRAMEWORK_AUDIENCE = "urn:aeo:framework"
    private const val MAX_TOKEN_LENGTH = 16_384
    private const val MAX_LIFETIME_SECONDS = 90L * 24 * 60 * 60
    private val rawEd25519Prefix = byteArrayOf(
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00
    )

    data class TrustedIssuerKey(
        val kid: String,
        val issuer: String,
        val publicKey: ByteArray,
        val notBefore: Long? = null,
        val notAfter: Long? = null,
        val revokedAt: Long? = null
    )

    data class Membership(
        val ecosystem: String,
        val issuer: String,
        val platformId: String,
        val platformName: String,
        val publisherId: String,
        val publisherKeyId: String,
        val credentialId: String,
        val issuedAt: Long,
        val expiresAt: Long,
        val scopes: Set<String>
    )

    data class VerifiedRegistration(
        val protocol: String,
        val platformId: String,
        val displayName: String,
        val membership: Membership
    )

    sealed class Result<out T> {
        data class Success<T>(val value: T) : Result<T>()
        data class Failure(val code: String, val message: String) : Result<Nothing>()
    }

    fun verify(
        compactJws: String,
        trustedKeys: List<TrustedIssuerKey>,
        isCredentialRevoked: (issuer: String, credentialId: String, platformId: String) -> Boolean,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
        clockSkewSeconds: Long = 60,
        apiLevel: Int = Build.VERSION.SDK_INT
    ): Result<Membership> {
        return try {
            require(apiLevel >= 33) {
                "Ed25519 verification requires Android API 33 or newer; no crypto downgrade is permitted"
            }
            require(compactJws.isNotBlank() && compactJws.length <= MAX_TOKEN_LENGTH) {
                "Credential is missing or exceeds the size limit"
            }
            require(clockSkewSeconds in 0..300) { "Invalid clock-skew configuration" }
            require(trustedKeys.isNotEmpty()) { "AEO trusted issuer keys are required" }

            val parts = compactJws.split('.')
            require(parts.size == 3 && parts.all { it.isNotEmpty() }) {
                "Credential must be a three-part compact JWS"
            }
            val header = JSONObject(decodeUtf8(parts[0]))
            val claims = JSONObject(decodeUtf8(parts[1]))
            require(header.length() == 3 &&
                header.optString("alg") == "EdDSA" &&
                header.optString("typ") == MEMBERSHIP_TYPE &&
                header.optString("kid").matches(Regex("[A-Za-z0-9._-]{1,128}"))) {
                "Credential does not match the AEO EdDSA membership profile"
            }

            val kid = header.getString("kid")
            val matching = trustedKeys.filter { it.kid == kid }
            require(matching.size == 1) { "Signing key is missing or ambiguous" }
            val key = matching.single()
            require(key.issuer.isNotBlank() && key.publicKey.size == 32) {
                "Trusted issuer key is invalid"
            }
            require(key.notBefore == null || nowSeconds + clockSkewSeconds >= key.notBefore) {
                "Signing key is not yet valid"
            }
            require(key.notAfter == null || nowSeconds - clockSkewSeconds <= key.notAfter) {
                "Signing key has expired"
            }
            require(key.revokedAt == null || nowSeconds < key.revokedAt) {
                "Signing key has been revoked"
            }

            val issuer = claims.getString("iss")
            require(issuer == key.issuer) { "Credential issuer does not match its trusted key" }
            require(claims.getString("aud") == FRAMEWORK_AUDIENCE &&
                claims.getString("ecosystem") == "AEO" &&
                claims.getString("credential_type") == "aeo-membership" &&
                claims.getInt("credential_version") == 1) {
                "Credential is not an AEO membership credential v1"
            }

            val platformId = claims.getString("sub")
            require(isPlatformId(platformId)) { "Invalid subject platform ID" }
            val platform = claims.getJSONObject("platform")
            require(platform.getString("id") == platformId) {
                "Platform identity does not match credential subject"
            }
            val platformName = platform.getString("name")
            require(platformName.isNotBlank() && platformName.length <= 80) {
                "Invalid platform name"
            }
            val publisher = claims.getJSONObject("publisher")
            val publisherId = publisher.getString("id")
            val publisherKeyId = publisher.getString("key_id")
            require(isPlatformId(publisherId) && publisherKeyId.isNotBlank() &&
                publisherKeyId.length <= 128) { "Invalid publisher identity" }

            val credentialId = claims.getString("jti")
            require(credentialId.matches(Regex(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}"
            ))) { "Credential ID must be a UUIDv4" }

            val scopeArray = claims.getJSONArray("scope")
            val scopes = (0 until scopeArray.length()).map { scopeArray.getString(it) }
            require(scopes.contains("member") && scopes.toSet().size == scopes.size) {
                "Membership scope must contain a unique member entry"
            }

            val iat = claims.getLong("iat")
            val nbf = claims.getLong("nbf")
            val exp = claims.getLong("exp")
            require(exp > iat && nbf >= iat && nbf <= exp &&
                iat <= nowSeconds + clockSkewSeconds &&
                nbf <= nowSeconds + clockSkewSeconds &&
                exp > nowSeconds - clockSkewSeconds &&
                exp - iat <= MAX_LIFETIME_SECONDS) {
                "Credential is expired, not yet valid, or has an invalid lifetime"
            }

            val signatureBytes = decodeBase64Url(parts[2])
            require(signatureBytes.size == 64) { "Ed25519 signature must be 64 bytes" }
            val x509 = rawEd25519Prefix + key.publicKey
            val publicKey = KeyFactory.getInstance("Ed25519")
                .generatePublic(X509EncodedKeySpec(x509))
            val verifier = Signature.getInstance("Ed25519")
            verifier.initVerify(publicKey)
            verifier.update("${parts[0]}.${parts[1]}".toByteArray(StandardCharsets.US_ASCII))
            require(verifier.verify(signatureBytes)) { "Credential signature is invalid" }

            require(!isCredentialRevoked(issuer, credentialId, platformId)) {
                "Credential is revoked or its status is unknown"
            }

            Result.Success(Membership(
                ecosystem = "AEO",
                issuer = issuer,
                platformId = platformId,
                platformName = platformName,
                publisherId = publisherId,
                publisherKeyId = publisherKeyId,
                credentialId = credentialId,
                issuedAt = iat,
                expiresAt = exp,
                scopes = scopes.toSet()
            ))
        } catch (error: Throwable) {
            Result.Failure(
                code = when {
                    error.message?.contains("API 33") == true -> "crypto_unavailable"
                    error.message?.contains("revoked") == true -> "credential_revoked_or_unknown"
                    error is java.security.SignatureException -> "invalid_signature"
                    else -> "invalid_membership_credential"
                },
                message = error.message ?: "Membership verification failed"
            )
        }
    }

    fun verifyRegistration(
        registration: JSONObject,
        trustedKeys: List<TrustedIssuerKey>,
        isCredentialRevoked: (issuer: String, credentialId: String, platformId: String) -> Boolean,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
        apiLevel: Int = Build.VERSION.SDK_INT
    ): Result<VerifiedRegistration> {
        return try {
            require(registration.getString("ecosystem") == "AEO" &&
                registration.getString("protocol") == PROTOCOL) {
                "Unsupported AEO ecosystem or UCCS protocol"
            }
            val platformId = registration.getString("platformId")
            val displayName = registration.getString("displayName")
            require(isPlatformId(platformId) && displayName.isNotBlank() && displayName.length <= 80) {
                "Invalid registration identity"
            }
            val credential = registration.getString("membershipCredential")
            when (val result = verify(credential, trustedKeys, isCredentialRevoked, nowSeconds, apiLevel = apiLevel)) {
                is Result.Failure -> result
                is Result.Success<*> -> {
                    val membership = result.value as Membership
                    require(membership.platformId == platformId &&
                        membership.platformName == displayName) {
                        "Registration identity does not match signed membership credential"
                    }
                    Result.Success(VerifiedRegistration(PROTOCOL, platformId, displayName, membership))
                }
            }
        } catch (error: Throwable) {
            Result.Failure("invalid_registration", error.message ?: "Invalid AEO registration")
        }
    }

    const val TRUST_BUNDLE_TYPE = "AEO-TRUST-BUNDLE+jwt"
    const val TRUST_BUNDLE_AUDIENCE = "urn:aeo:trust-bundle"

    data class PinnedRootKey(val kid: String, val publicKey: ByteArray, val notBefore: Long? = null, val notAfter: Long? = null, val revokedAt: Long? = null)
    data class VerifiedTrustBundle(val version: Long, val issuedAt: Long, val expiresAt: Long, val trustedKeys: List<TrustedIssuerKey>, val revokedCredentialIds: Set<String>)

    /** Caller must persist the accepted version and pass it as minimumVersion to prevent rollback. */
    fun verifyTrustBundle(compactJws: String, pinnedRootKeys: List<PinnedRootKey>, minimumVersion: Long = 0, nowSeconds: Long = System.currentTimeMillis() / 1000, clockSkewSeconds: Long = 60, apiLevel: Int = Build.VERSION.SDK_INT): Result<VerifiedTrustBundle> {
        return try {
            require(apiLevel >= 33) { "Ed25519 trust-bundle verification requires Android API 33 or newer" }
            require(compactJws.isNotBlank() && compactJws.length <= MAX_TOKEN_LENGTH * 4) { "Trust bundle is missing or exceeds the size limit" }
            require(pinnedRootKeys.isNotEmpty() && minimumVersion >= 0 && clockSkewSeconds in 0..300) { "Pinned AEO root keys and valid verifier settings are required" }
            val parts = compactJws.split('.')
            require(parts.size == 3 && parts.all { it.isNotEmpty() }) { "Trust bundle must be a three-part compact JWS" }
            val header = JSONObject(decodeUtf8(parts[0]))
            val payload = JSONObject(decodeUtf8(parts[1]))
            require(header.length() == 3 && header.optString("alg") == "EdDSA" && header.optString("typ") == TRUST_BUNDLE_TYPE && header.optString("kid").matches(Regex("[A-Za-z0-9._-]{1,128}"))) { "Trust bundle does not match the AEO root-signature profile" }
            val roots = pinnedRootKeys.filter { it.kid == header.getString("kid") }
            require(roots.size == 1) { "Pinned AEO root key is missing or ambiguous" }
            val root = roots.single()
            require(root.publicKey.size == 32 && (root.notBefore == null || nowSeconds + clockSkewSeconds >= root.notBefore) && (root.notAfter == null || nowSeconds - clockSkewSeconds <= root.notAfter) && (root.revokedAt == null || nowSeconds < root.revokedAt)) { "Pinned AEO root key is invalid, expired, or revoked" }
            require(payload.getString("iss") == "AEO" && payload.getString("aud") == TRUST_BUNDLE_AUDIENCE && payload.getString("bundle_type") == "aeo-trust-bundle" && payload.getInt("bundle_version") == 1) { "Trust bundle issuer, audience, or profile version is invalid" }
            val version = payload.getLong("version"); val iat = payload.getLong("iat"); val nbf = payload.getLong("nbf"); val exp = payload.getLong("exp")
            require(version >= 1 && version >= minimumVersion && exp > iat && nbf >= iat && nbf <= exp && iat <= nowSeconds + clockSkewSeconds && nbf <= nowSeconds + clockSkewSeconds && exp > nowSeconds - clockSkewSeconds && exp - iat <= 7L * 24 * 60 * 60) { "Trust bundle is expired, stale, or has an invalid lifetime/version" }
            val signatureBytes = decodeBase64Url(parts[2])
            require(signatureBytes.size == 64) { "Ed25519 trust-bundle signature must be 64 bytes" }
            val rootPublic = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(rawEd25519Prefix + root.publicKey))
            val verifier = Signature.getInstance("Ed25519"); verifier.initVerify(rootPublic); verifier.update((parts[0] + "." + parts[1]).toByteArray(StandardCharsets.US_ASCII))
            require(verifier.verify(signatureBytes)) { "Trust bundle signature is invalid" }
            val keyArray = payload.getJSONArray("keys")
            require(keyArray.length() in 1..100) { "Trust bundle must contain 1 to 100 issuer keys" }
            val seen = mutableSetOf<String>()
            val issuerKeys = (0 until keyArray.length()).map { index ->
                val item = keyArray.getJSONObject(index); val keyId = item.getString("kid"); val issuer = item.getString("issuer"); val algorithm = item.getString("algorithm"); val publicKey = decodeBase64Url(item.getString("publicKey"))
                require(keyId.matches(Regex("[A-Za-z0-9._-]{1,128}")) && seen.add(keyId) && issuer.isNotBlank() && algorithm == "Ed25519" && publicKey.size == 32) { "Trust bundle contains an invalid or duplicate issuer key" }
                val notBefore = if (item.has("notBefore")) item.getLong("notBefore") else null; val notAfter = if (item.has("notAfter")) item.getLong("notAfter") else null; val revokedAt = if (item.has("revokedAt")) item.getLong("revokedAt") else null
                require(notBefore == null || notAfter == null || notAfter > notBefore) { "Issuer key validity interval is invalid" }
                TrustedIssuerKey(keyId, issuer, publicKey, notBefore, notAfter, revokedAt)
            }
            val revokedArray = payload.optJSONArray("revokedCredentialIds") ?: org.json.JSONArray()
            require(revokedArray.length() <= 10_000) { "Trust bundle revocation list is too large" }
            val revokedIds = (0 until revokedArray.length()).map { revokedArray.getString(it) }
            require(revokedIds.all { it.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")) } && revokedIds.toSet().size == revokedIds.size) { "Trust bundle revocation list is invalid" }
            Result.Success(VerifiedTrustBundle(version, iat, exp, issuerKeys, revokedIds.toSet()))
        } catch (error: Throwable) {
            Result.Failure(if (error.message?.contains("API 33") == true) "crypto_unavailable" else if (error.message?.contains("signature") == true) "invalid_trust_bundle_signature" else "invalid_trust_bundle", error.message ?: "Trust-bundle verification failed")
        }
    }

    private fun decodeUtf8(segment: String): String =
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(decodeBase64Url(segment))).toString()

    private fun decodeBase64Url(value: String): ByteArray {
        require(value.isNotEmpty() && value.matches(Regex("[A-Za-z0-9_-]+"))) {
            "Invalid base64url encoding"
        }
        val decoded = Base64.getUrlDecoder().decode(value)
        require(Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) == value) {
            "Non-canonical base64url encoding"
        }
        return decoded
    }

    private fun isPlatformId(value: String): Boolean {
        if (value.isBlank() || value.length > 255) return false
        val labels = value.split('.')
        return labels.size >= 2 && labels.all {
            it.length <= 63 && it.matches(Regex("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?"))
        }
    }
}
