package com.oea.launcher.privilege.adb;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * HKDF (RFC 5869) plus the TLS 1.3 secret-derivation helpers of RFC 8446
 * section 7.1: {@code HKDF-Expand-Label} and {@code Derive-Secret}.
 *
 * Only the SHA-256 schedule is implemented: pairing and the ADB connection both
 * negotiate TLS_AES_128_GCM_SHA256, whose hash is SHA-256.
 */
public final class Hkdf {
    private Hkdf() {}

    public static final int HASH_LEN = 32;

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 missing", e);
        }
    }

    /** SHA-256 of exactly the given bytes. */
    public static byte[] sha256Of(byte[] data) {
        return sha256().digest(data);
    }

    /** HKDF-Extract (RFC 5869 step 1) with an all-zero default salt. */
    public static byte[] extract(byte[] salt, byte[] ikm) {
        byte[] realSalt = salt != null && salt.length > 0 ? salt : new byte[HASH_LEN];
        return hmacSha256(realSalt, ikm);
    }

    /** HKDF-Expand (RFC 5869 step 2): T(i) = HMAC(PRK, T(i-1) | info | i). */
    public static byte[] expand(byte[] prk, byte[] info, int length) {
        byte[] out = new byte[length];
        byte[] t = new byte[0];
        int pos = 0;
        byte counter = 1;
        while (pos < length) {
            byte[] data = new byte[t.length + (info != null ? info.length : 0) + 1];
            System.arraycopy(t, 0, data, 0, t.length);
            if (info != null && info.length > 0) {
                System.arraycopy(info, 0, data, t.length, info.length);
            }
            data[data.length - 1] = counter;
            t = hmacSha256(prk, data);
            int n = Math.min(HASH_LEN, length - pos);
            System.arraycopy(t, 0, out, pos, n);
            pos += n;
            counter++;
        }
        return out;
    }

    public static byte[] hmacSha256(byte[] key, byte[] data) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    /**
     * RFC 8446 section 7.1: HKDF-Expand-Label(Secret, Label, Context, Length).
     * The wire label is the fixed string "tls13 " followed by {@code label}.
     */
    public static byte[] expandLabel(byte[] secret, String label, byte[] context, int length) {
        byte[] labelBytes = (label != null ? label : "").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] wireLabel = new byte[6 + labelBytes.length];
        byte[] prefix = "tls13 ".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(prefix, 0, wireLabel, 0, 6);
        System.arraycopy(labelBytes, 0, wireLabel, 6, labelBytes.length);
        byte[] ctx = context != null ? context : new byte[0];

        // HkdfLabel = uint16 length || opaque label<7..255> || opaque context<0..255>
        byte[] hkdfLabel = new byte[2 + 1 + wireLabel.length + 1 + ctx.length];
        hkdfLabel[0] = (byte) (length >> 8);
        hkdfLabel[1] = (byte) length;
        hkdfLabel[2] = (byte) wireLabel.length;
        System.arraycopy(wireLabel, 0, hkdfLabel, 3, wireLabel.length);
        hkdfLabel[3 + wireLabel.length] = (byte) ctx.length;
        System.arraycopy(ctx, 0, hkdfLabel, 4 + wireLabel.length, ctx.length);
        return expand(secret, hkdfLabel, length);
    }

    /**
     * RFC 8446 section 7.1: Derive-Secret(Secret, Label, Messages). A null
     * transcript means the empty message, whose hash is Hash("") — not zeros.
     */
    public static byte[] deriveSecret(byte[] secret, String label, Transcript transcript) {
        byte[] hash = transcript != null ? transcript.currentHash() : sha256Of(new byte[0]);
        return expandLabel(secret, label, hash, HASH_LEN);
    }

    /**
     * The handshake transcript: the SHA-256 of every handshake message, in
     * order. A whole exchange is a few kilobytes, so the raw bytes are kept and
     * hashed on demand — exact and simple.
     */
    public static final class Transcript {
        private final ByteArrayOutputStream raw = new ByteArrayOutputStream(2048);

        public void update(byte[] message) {
            raw.write(message, 0, message.length);
        }

        /** SHA-256 of everything recorded so far. */
        public byte[] currentHash() {
            return sha256().digest(raw.toByteArray());
        }

        byte[] rawBytes() {
            return raw.toByteArray();
        }
    }
}
