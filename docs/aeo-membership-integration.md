# OEA AEO membership adapter

OEA now has a native verifier for AEO Ed25519 membership credentials and the UCCS `uccs/1` registration envelope.

## Boundaries

Membership proves ecosystem registration only. It is not device/session authentication, capability authorization, user consent, or a replacement for OEA's existing local privileges and app security. OEA remains an independent launcher and platform; this adapter does not route OEA traffic through Central Hub.

The caller supplies the trusted issuer public keys and credential-revocation callback. The verifier is local and performs no network or database access.

## Android compatibility

OEA supports Android API 26+. Built-in JCA Ed25519 is used only on API 33+, and API 26–32 fail closed with `crypto_unavailable`. There is no silent crypto downgrade. Supporting older Android releases requires a separately reviewed maintained crypto provider.

## Remaining production requirements

No AEO root key or production membership credential is embedded. Root-signed trust-bundle verification, persistent anti-rollback version storage, trust-root rotation/recovery, and the live issuer/revocation distribution process are not implemented by this adapter. Do not claim OEA is production-conformant until those are completed and peer authentication/authorization remain separately enforced.

JVM tests cover valid credentials, signature tampering, unknown keys, revocation, wrong audience, API-level fail-closed behavior, and registration identity binding.
