package com.oea.launcher.privilege.adb;

import org.junit.Test;

/**
 * SPAKE2 must be bit-exact with adbd's BoringSSL {@code spake25519}: the
 * pairing code handshake only succeeds if both sides derive the same key.
 * The vectors come from {@code tools/pyspake25519.py}, an independent Python
 * mirror of the BoringSSL implementation.
 */
public class Spake2VectorTest {

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    @Test
    public void goldenVectorsMatchBoringSsl() {
        byte[] pw = "012345".getBytes();
        // Fixed private keys: privA = LE bytes(range(32)) % l * 8, privB = LE bytes(31..0) % l * 8
        byte[] privA = new byte[32];
        for (int i = 0; i < 32; i++) privA[i] = (byte) i;
        byte[] privB = new byte[32];
        for (int i = 0; i < 32; i++) privB[i] = (byte) (31 - i);

        Spake2 a = new Spake2(Spake2.Role.CLIENT, pw, privA);
        Spake2 b = new Spake2(Spake2.Role.SERVER, pw, privB);

        org.junit.Assert.assertArrayEquals(hex("b3d635ac7f3f6db154d5b7fe6e82ee580714e64197d7eeca8918d5c4904c599a"), a.message());
        org.junit.Assert.assertArrayEquals(hex("8e52e2bc67906b448431f4f55dc10a8e782737d505ea8d5b58b52a0631216f12"), b.message());

        byte[] keyA = a.processMessage(b.message());
        byte[] keyB = b.processMessage(a.message());
        org.junit.Assert.assertNotNull(keyA);
        org.junit.Assert.assertNotNull(keyB);
        org.junit.Assert.assertArrayEquals(keyA, keyB);
        org.junit.Assert.assertEquals(
                "8fbe6686fe76fe7c4dbaf828a1208108990245882ce18bf54cd2023847d0ba66c"
                        + "cc597bfea3fb06f2521e0eb75ce9640003c9592e2e86c73aec9f01617157b79",
                hexString(keyA));
    }

    @Test
    public void randomKeysAgree() {
        for (int round = 0; round < 8; round++) {
            byte[] pw = ("password-" + round).getBytes();
            Spake2 a = new Spake2(Spake2.Role.CLIENT, pw);
            Spake2 b = new Spake2(Spake2.Role.SERVER, pw);
            byte[] keyA = a.processMessage(b.message());
            byte[] keyB = b.processMessage(a.message());
            org.junit.Assert.assertNotNull(keyA);
            org.junit.Assert.assertNotNull(keyB);
            org.junit.Assert.assertArrayEquals(keyA, keyB);
            org.junit.Assert.assertEquals(64L, (long) keyA.length);
        }
    }

    @Test
    public void wrongPasswordMismatch() {
        Spake2 a = new Spake2(Spake2.Role.CLIENT, "012345".getBytes());
        Spake2 b = new Spake2(Spake2.Role.SERVER, "654321".getBytes());
        // Like BoringSSL, a wrong password still completes the point
        // arithmetic — but the two sides derive DIFFERENT keys, so the first
        // pairing-packet decrypt fails (which is how adb rejects a bad code).
        byte[] keyA = a.processMessage(b.message());
        byte[] keyB = b.processMessage(a.message());
        org.junit.Assert.assertNotNull(keyA);
        org.junit.Assert.assertNotNull(keyB);
        org.junit.Assert.assertTrue(!java.util.Arrays.equals(keyA, keyB));
    }

    private static String hexString(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
