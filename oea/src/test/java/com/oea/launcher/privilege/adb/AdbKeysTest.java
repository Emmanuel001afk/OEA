package com.oea.launcher.privilege.adb;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.Base64;

/**
 * The ADB identity has to satisfy adbd's checks byte for byte: the public-key
 * blob is decoded by {@code android_pubkey_decode} (Montgomery parameters
 * included) and the TLS certificate has to carry the very same RSA key.
 */
public class AdbKeysTest {

    @Test
    public void publicKeyBlobMatchesAndroidFormat() throws Exception {
        AdbKeys keys = AdbKeys.generate("oea@phone");
        String line = keys.publicKeyLine();
        int space = line.lastIndexOf(' ');
        org.junit.Assert.assertEquals("oea@phone", line.substring(space + 1));
        byte[] blob = Base64.getDecoder().decode(line.substring(0, space));
        org.junit.Assert.assertEquals(524, blob.length);

        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger rrExpected = BigInteger.ONE.shiftLeft(4096); // Montgomery R^2
        long keyBits = leU32(blob, 0);
        BigInteger n0 = le(blob, 4, 4);
        BigInteger n = le(blob, 8, 256);
        BigInteger rr = le(blob, 264, 256);
        long exponent = leU32(blob, 520);

        org.junit.Assert.assertEquals(2048, keyBits);
        org.junit.Assert.assertEquals(65537, exponent);
        // n0inv = -N[0]^-1 mod 2^32 and rr = 2^2048 mod N, exactly as the C decoder checks
        org.junit.Assert.assertEquals(r32.subtract(n.mod(r32).modInverse(r32)).mod(r32), n0);
        org.junit.Assert.assertEquals(rrExpected.mod(n), rr);

        // The certificate must carry the same key as the blob.
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        Certificate cert = factory.generateCertificate(new ByteArrayInputStream(keys.certificateDer()));
        RSAPublicKey certKey = (RSAPublicKey) cert.getPublicKey();
        org.junit.Assert.assertEquals(n, certKey.getModulus());
        org.junit.Assert.assertEquals(BigInteger.valueOf(exponent), certKey.getPublicExponent());

        // And the private key must match: sign/verify round-trip.
        java.security.Signature signature = java.security.Signature.getInstance("SHA256withRSA");
        signature.initSign((PrivateKey) keys.privateKey());
        signature.update(new byte[]{1, 2, 3});
        byte[] sig = signature.sign();
        signature.initVerify(certKey);
        signature.update(new byte[]{1, 2, 3});
        org.junit.Assert.assertTrue(signature.verify(sig));
    }

    private static long leU32(byte[] b, int off) {
        return (b[off] & 0xFFL) | ((b[off + 1] & 0xFFL) << 8) | ((b[off + 2] & 0xFFL) << 16)
                | ((b[off + 3] & 0xFFL) << 24);
    }

    private static BigInteger le(byte[] b, int off, int len) {
        byte[] be = new byte[len];
        for (int i = 0; i < len; i++) be[i] = b[off + len - 1 - i];
        return new BigInteger(1, be);
    }
}
