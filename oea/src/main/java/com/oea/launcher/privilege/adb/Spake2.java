package com.oea.launcher.privilege.adb;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SPAKE2 exactly as BoringSSL implements it ({@code crypto/curve25519/spake25519.cc}),
 * which is what Android's {@code adbd} runs for wireless-debugging pairing.
 *
 * The protocol, constants and transcript encoding all follow the C code:
 *
 *  - the client is {@code spake2_role_alice} and masks with point M; the server
 *    (adbd) is {@code spake2_role_bob} and masks with N;
 *  - names are the NUL-terminated C literals "adb pair client" / "adb pair server"
 *    (16 bytes each, the NUL included);
 *  - the private key is a random 32-byte value reduced mod L, then multiplied by
 *    eight (the curve cofactor) as a 32-byte little-endian shift;
 *  - the password scalar is SHA-512(password) reduced mod L, then adjusted up by
 *    multiples of the subgroup order until the low three bits are zero (BoringSSL
 *    fixes its missing cofactor clear this way);
 *  - the transcript hashes, for the client, SHA-512 over length-prefixed
 *    (8-byte little-endian lengths): my name, their name, my message, their
 *    message, the shared point, the un-reduced SHA-512 of the password.
 *
 * The key material is the full 64-byte SHA-512 output; the pairing layer narrows
 * it to the AES key with HKDF (see {@link PairingCipher}).
 */
public final class Spake2 {
    /** Max message size on the wire (BoringSSL's SPAKE2_MAX_MSG_SIZE). */
    public static final int MSG_SIZE = 32;
    /** Max key material size (BoringSSL's SPAKE2_MAX_KEY_SIZE). */
    public static final int KEY_SIZE = 64;

    /** Which side of the exchange this context plays. */
    public enum Role {
        /** The connecting side (OEA, pairing with this phone). */
        CLIENT,
        /** The listening side (adbd). Only needed for tests. */
        SERVER
    }

    private static final byte[] CLIENT_NAME = "adb pair client\u0000".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SERVER_NAME = "adb pair server\u0000".getBytes(StandardCharsets.US_ASCII);

    // Points M and N from BoringSSL's spake25519.cc (generated from the
    // "edwards25519 point generation seed (M/N)" strings).
    private static final Ed25519.Point M = Ed25519.decode(hex(
            "5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e"));
    private static final Ed25519.Point N = Ed25519.decode(hex(
            "10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778"));

    static {
        if (M == null || N == null) throw new AssertionError("SPAKE2 constants must decode");
    }

    private final Role role;
    private final byte[] password;
    private final byte[] myName;
    private final byte[] theirName;

    private final byte[] privateKey = new byte[32];     // little-endian, multiple of eight
    private BigInteger passwordScalar;
    private byte[] passwordHash;                        // un-reduced SHA-512(password)
    private final byte[] myMsg = new byte[MSG_SIZE];
    private boolean finished;

    /** Creates a context and generates this side's SPAKE2 message. */
    public Spake2(Role role, byte[] password) {
        this(role, password, null);
    }

    /**
     * Creates a context with a fixed private scalar (32 little-endian bytes,
     * reduced mod L). Used by tests for reproducible vectors; production code
     * uses the random constructor.
     */
    Spake2(Role role, byte[] password, byte[] fixedPrivateKey) {
        if (password == null || password.length == 0) {
            throw new IllegalArgumentException("password must not be empty");
        }
        this.role = role;
        this.password = password.clone();
        boolean client = role == Role.CLIENT;
        this.myName = client ? CLIENT_NAME : SERVER_NAME;
        this.theirName = client ? SERVER_NAME : CLIENT_NAME;
        generateMsg(fixedPrivateKey);
    }

    /** This side's 32-byte SPAKE2 message. */
    public byte[] message() {
        return myMsg.clone();
    }

    /**
     * Processes the peer's message and derives the shared key material.
     * Returns null when the peer's message is not a valid curve point (which is
     * how a wrong password or a non-pairing server shows up).
     */
    public byte[] processMessage(byte[] theirMsg) {
        if (finished || theirMsg == null || theirMsg.length != MSG_SIZE) return null;
        Ed25519.Point qStar = Ed25519.decode(theirMsg);
        if (qStar == null) return null;

        // Unmask the peer's point: Q = Q* - (password_scalar * their mask point).
        Ed25519.Point peersMask = Ed25519.multiply(role == Role.CLIENT ? N : M, passwordScalar);
        Ed25519.Point negated = new Ed25519.Point(
                peersMask.x.negate().mod(Ed25519.P), peersMask.y, peersMask.z, peersMask.t.negate().mod(Ed25519.P));
        Ed25519.Point q = Ed25519.add(qStar, negated);
        Ed25519.Point shared = Ed25519.multiply(q, Ed25519.fromLittle(privateKey));
        byte[] sharedEncoded = shared.encode();

        finished = true;
        boolean client = role == Role.CLIENT;

        MessageDigest sha512 = sha512();
        updateWithLength(sha512, client ? myName : theirName);
        updateWithLength(sha512, client ? theirName : myName);
        if (client) {
            updateWithLength(sha512, myMsg);
            updateWithLength(sha512, theirMsg);
        } else {
            updateWithLength(sha512, theirMsg);
            updateWithLength(sha512, myMsg);
        }
        updateWithLength(sha512, sharedEncoded);
        updateWithLength(sha512, passwordHash);
        return sha512.digest();
    }

    private void generateMsg(byte[] fixedPrivateKey) {
        byte[] random = new byte[64];
        if (fixedPrivateKey != null) {
            if (fixedPrivateKey.length != 32) throw new IllegalArgumentException("need 32 bytes");
            System.arraycopy(fixedPrivateKey, 0, random, 0, 32);
        } else {
            java.security.SecureRandom sr = new java.security.SecureRandom();
            sr.nextBytes(random);
        }
        BigInteger reduced = Ed25519.scReduce(random);
        // Multiply by the cofactor (eight): a 32-byte little-endian shift by 3.
        BigInteger shifted = reduced.shiftLeft(3).and(BigInteger.TWO.pow(256).subtract(BigInteger.ONE));
        byte[] le = Ed25519.toLittle32(shifted);
        System.arraycopy(le, 0, privateKey, 0, 32);

        byte[] pwHash = sha512().digest(password);
        passwordHash = pwHash;

        // BoringSSL: x25519_sc_reduce(SHA512(password)) reduces the full
        // 64-byte digest (the Ed25519 sc_reduce takes 64 bytes), then adds
        // multiples of the subgroup order until the low three bits are clear.
        // Each add re-reads the bits of the value as it is at that moment.
        BigInteger scalar = Ed25519.scReduce(pwHash);
        scalar = scalar.add(scalar.testBit(0) ? Ed25519.L : BigInteger.ZERO);
        scalar = scalar.add(scalar.testBit(1) ? Ed25519.L.shiftLeft(1) : BigInteger.ZERO);
        scalar = scalar.add(scalar.testBit(2) ? Ed25519.L.shiftLeft(2) : BigInteger.ZERO);
        passwordScalar = scalar;

        // The private scalar is used as-is (a 32-byte little-endian value,
        // already a multiple of eight) — BoringSSL never reduces it again.
        BigInteger privateScalar = Ed25519.fromLittle(privateKey);
        Ed25519.Point base = Ed25519.multiply(Ed25519.BASE, privateScalar);
        Ed25519.Point mask = Ed25519.multiply(role == Role.CLIENT ? M : N, scalar);
        Ed25519.Point star = Ed25519.add(base, mask);
        byte[] encoded = star.encode();
        System.arraycopy(encoded, 0, myMsg, 0, MSG_SIZE);
    }

    private static void updateWithLength(MessageDigest digest, byte[] data) {
        byte[] len = new byte[8];
        long l = data.length;
        for (int i = 0; i < 8; i++) {
            len[i] = (byte) (l & 0xFF);
            l >>= 8;
        }
        digest.update(len);
        digest.update(data);
    }

    private static MessageDigest sha512() {
        try {
            return MessageDigest.getInstance("SHA-512");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-512 missing", e);
        }
    }

    private static byte[] hex(String s) {
        int n = s.length();
        byte[] out = new byte[n / 2];
        for (int i = 0; i < n; i += 2) {
            out[i / 2] = (byte) Integer.parseInt(s.substring(i, i + 2), 16);
        }
        return out;
    }
}
