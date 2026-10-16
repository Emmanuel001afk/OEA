package com.oea.launcher.privilege.adb;

import java.io.IOException;
import java.security.GeneralSecurityException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The AES-128-GCM cipher of adb's wireless pairing ({@code pairing_auth/aes_128_gcm.cpp}):
 *
 *  - key = HKDF-SHA256(spaKe2 key material, salt=empty, info="adb pairing_auth
 *    aes-128-gcm key"), 16 bytes;
 *  - nonce = 12 zero bytes with the first 4 overwritten by the little-endian
 *    per-direction sequence number, starting at 0 for each direction;
 *  - no additional data, 16-byte tag.
 *
 * The key is derived from the SPAKE2 shared key by both sides, so a wrong
 * pairing code yields two different keys and the first decrypt fails.
 */
final class PairingCipher {
    private static final String HKDF_INFO = "adb pairing_auth aes-128-gcm key";
    private static final int KEY_LEN = 16;
    private static final int NONCE_LEN = 12;
    static final int TAG_LEN = 16;

    private final javax.crypto.spec.SecretKeySpec key;
    private long writeSequence;
    private long readSequence;

    PairingCipher(byte[] spake2KeyMaterial) {
        // HKDF-Extract with an empty salt, then Expand with adb's info string.
        byte[] prk = Hkdf.extract(new byte[0], spake2KeyMaterial);
        byte[] aesKey = Hkdf.expand(prk,
                HKDF_INFO.getBytes(java.nio.charset.StandardCharsets.US_ASCII), KEY_LEN);
        this.key = new javax.crypto.spec.SecretKeySpec(aesKey, "AES");
    }

    /** Ciphertext length for a plaintext of {@code n} bytes. */
    static int encryptedSize(int n) {
        return n + TAG_LEN;
    }

    /** Plaintext length hidden in a ciphertext of {@code n} bytes. */
    static int decryptedSize(int n) {
        if (n < TAG_LEN) throw new IllegalArgumentException("ciphertext shorter than the GCM tag");
        return n - TAG_LEN;
    }

    byte[] seal(byte[] plaintext) throws IOException {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce(writeSequence++)));
            return cipher.doFinal(plaintext);
        } catch (GeneralSecurityException e) {
            throw new IOException("pairing cipher: encryption failed", e);
        }
    }

    byte[] open(byte[] ciphertext) throws IOException {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce(readSequence++)));
            return cipher.doFinal(ciphertext);
        } catch (GeneralSecurityException e) {
            // Wrong pairing code, corrupted packet, or desynchronized streams.
            throw new IOException("pairing cipher: decryption failed", e);
        }
    }

    private static byte[] nonce(long sequence) {
        byte[] nonce = new byte[NONCE_LEN];
        nonce[0] = (byte) sequence;
        nonce[1] = (byte) (sequence >> 8);
        nonce[2] = (byte) (sequence >> 16);
        nonce[3] = (byte) (sequence >> 24);
        return nonce;
    }
}
