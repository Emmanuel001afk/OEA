# OEA AEO membership adapter

OEA now has a native Kotlin verifier for AEO Ed25519 membership credentials, UCCS `uccs/1` registration envelopes, and root-signed trust bundles.

## Boundaries

Membership proves ecosystem registration only. It is not device/session authentication, capability authorization, user consent, or a replacement for OEA's existing local privileges and app security. OEA remains independent; Central Hub is not a mandatory gateway.

## Android compatibility

Built-in JCA Ed25519 is used only on API 33+, while API 26–32 fail closed with `crypto_unavailable`. There is no silent cryptographic downgrade. Supporting older Android releases requires a separately reviewed maintained provider.

## Trust bundles

The verifier checks compact JWS trust bundles against pinned Ed25519 root public keys, validates issuer-key records and credential revocation IDs, and enforces expiry and a caller-supplied minimum version. OEA must persist its highest accepted version in OEA-owned secure storage and pass it as `minimumVersion`. This pure verifier does not persist state, fetch keys, or route requests. Root custody, rotation/recovery, live issuer/revocation distribution, and initial platform registration remain production prerequisites.

## Validation

JVM tests cover membership and registration verification, trust-bundle signatures and rollback, revoked/unknown trust, and API-level fail-closed behavior. CI and device checks must pass before merge.
