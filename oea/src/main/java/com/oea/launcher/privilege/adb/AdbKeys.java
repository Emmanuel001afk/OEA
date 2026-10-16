package com.oea.launcher.privilege.adb;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAKeyGenParameterSpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * The RSA identity OEA presents when pairing with this phone's adbd and when
 * connecting afterwards, exactly the role of {@code ~/.android/adbkey} on a
 * computer:
 *
 *  - an RSA-2048 key pair, kept in OEA's private storage;
 *  - a self-signed X.509 certificate (DER/PEM) carrying the public key, which
 *    is what the TLS handshakes exchange;
 *  - the Android public-key blob ({@code android_pubkey_encode}: 2048-bit
 *    modulus, Montgomery n0inv and R^2, exponent 65537), base64-encoded with a
 *    name suffix, which is what pairing stores as this device's ADB public key
 *    (adb's PeerInfo type ADB_RSA_PUB_KEY).
 *
 * The certificate is built by hand in DER — OEA does not need a general-purpose
 * certificate library for one fixed, self-signed structure.
 */
public final class AdbKeys {

    /** PEM text of the private key (PKCS#8). */
    private final String privateKeyPem;
    /** PEM text of the self-signed certificate. */
    private final String certificatePem;
    /** DER of the certificate. */
    private final byte[] certificateDer;
    /** The Android ADB public key line ("base64blob name"), as sent in PeerInfo. */
    private final String publicKeyLine;
    private final PrivateKey privateKey;

    private AdbKeys(String privateKeyPem, String certificatePem, byte[] certificateDer,
                    String publicKeyLine, PrivateKey privateKey) {
        this.privateKeyPem = privateKeyPem;
        this.certificatePem = certificatePem;
        this.certificateDer = certificateDer;
        this.publicKeyLine = publicKeyLine;
        this.privateKey = privateKey;
    }

    public String privateKeyPem() { return privateKeyPem; }
    public String certificatePem() { return certificatePem; }
    public byte[] certificateDer() { return certificateDer; }
    public String publicKeyLine() { return publicKeyLine; }
    public PrivateKey privateKey() { return privateKey; }

    public Tls13Client.Identity identity() {
        return new Tls13Client.Identity() {
            @Override public byte[] certificateDer() { return AdbKeys.this.certificateDer; }
            @Override public PrivateKey privateKey() { return AdbKeys.this.privateKey; }
        };
    }

    // ------------------------------------------------------------------ creation + persistence

    /** Where the identity is stored (two small files in OEA's private dir). */
    public static File keyFile(File dir) { return new File(dir, "oea_adb_key.pem"); }
    public static File certFile(File dir) { return new File(dir, "oea_adb_cert.pem"); }

    /** Loads the stored identity, or creates and stores a fresh one. */
    public static AdbKeys loadOrCreate(File dir) throws IOException {
        File kf = keyFile(dir);
        File cf = certFile(dir);
        if (kf.isFile() && cf.isFile()) {
            try {
                String keyPem = new String(Files.readAllBytes(kf.toPath()), StandardCharsets.US_ASCII);
                String certPem = new String(Files.readAllBytes(cf.toPath()), StandardCharsets.US_ASCII);
                AdbKeys keys = fromPem(keyPem, certPem);
                if (keys != null) return keys;
            } catch (Throwable ignored) {
                // fall through and regenerate
            }
        }
        AdbKeys keys = generate(dir.getName());
        dir.mkdirs();
        Files.write(kf.toPath(), keys.privateKeyPem.getBytes(StandardCharsets.US_ASCII));
        Files.write(cf.toPath(), keys.certificatePem.getBytes(StandardCharsets.US_ASCII));
        try {
            kf.setReadable(true, true); kf.setWritable(true, true); kf.setExecutable(false, false);
            cf.setReadable(true, true); cf.setWritable(true, true); cf.setExecutable(false, false);
        } catch (Throwable ignored) {
        }
        return keys;
    }

    /** Builds an identity from PEM text (private key PKCS#8, certificate). */
    public static AdbKeys fromPem(String privateKeyPem, String certificatePem) {
        try {
            byte[] keyDer = pemBody(privateKeyPem, "PRIVATE KEY");
            byte[] certDer = pemBody(certificatePem, "CERTIFICATE");
            KeyFactory factory = KeyFactory.getInstance("RSA");
            PrivateKey key = factory.generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(keyDer));
            java.security.cert.CertificateFactory certFactory =
                    java.security.cert.CertificateFactory.getInstance("X.509");
            java.security.cert.X509Certificate cert = (java.security.cert.X509Certificate)
                    certFactory.generateCertificate(new java.io.ByteArrayInputStream(certDer));
            String pubLine = publicKeyLineFor((RSAPublicKey) cert.getPublicKey(), "OEA");
            return new AdbKeys(privateKeyPem.trim() + "\n", certificatePem.trim() + "\n",
                    certDer, pubLine, key);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Generates a fresh RSA-2048 identity. */
    public static AdbKeys generate(String name) throws IOException {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(new RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4), new SecureRandom());
            KeyPair pair = kpg.generateKeyPair();
            PrivateKey priv = pair.getPrivate();
            RSAPublicKey pub = (RSAPublicKey) pair.getPublic();
            byte[] certDer = selfSignedCertDer(priv, pub, "OEA");
            String pubLine = publicKeyLineFor(pub, name);
            String keyPem = "-----BEGIN PRIVATE KEY-----\n" + wrap64(Base64.getEncoder().encodeToString(priv.getEncoded()))
                    + "\n-----END PRIVATE KEY-----\n";
            String certPem = "-----BEGIN CERTIFICATE-----\n" + wrap64(Base64.getEncoder().encodeToString(certDer))
                    + "\n-----END CERTIFICATE-----\n";
            return new AdbKeys(keyPem, certPem, certDer, pubLine, priv);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("could not create an ADB identity", e);
        }
    }

    // ------------------------------------------------------------------ Android public key blob

    /**
     * The "adb pubkey" line: base64 of Android's binary RSAPublicKey
     * (key_bits, n0inv, modulus LE, R^2 LE, exponent), then a space and a name.
     * adbd parses this with {@code android_pubkey_decode} when it checks our
     * signature or our TLS certificate, so the layout must match exactly.
     */
    public static String publicKeyLineFor(RSAPublicKey pub, String name) {
        BigInteger n = pub.getModulus();
        BigInteger e = pub.getPublicExponent();
        byte[] struct = androidPubkeyEncode(n, e);
        String b64 = Base64.getEncoder().encodeToString(struct);
        return b64 + " " + name;
    }

    private static final int MODULUS_BYTES = 2048 / 8;

    static byte[] androidPubkeyEncode(BigInteger n, BigInteger e) {
        byte[] out = new byte[4 + 4 + MODULUS_BYTES + MODULUS_BYTES + 4];
        // key_bits (uint32 LE)
        putU32(out, 0, MODULUS_BYTES * 8);
        // n0inv = -1 / n[0] mod 2^32  (Montgomery parameter)
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger nMod = n.mod(r32);
        BigInteger n0inv = nMod.modInverse(r32);
        BigInteger n0 = r32.subtract(n0inv).mod(r32);
        putU32(out, 4, n0.longValue());
        // modulus, little-endian
        byte[] modulus = toLeFixed(n, MODULUS_BYTES);
        System.arraycopy(modulus, 0, out, 8, MODULUS_BYTES);
        // rr = (2^(rsa_size))^2 mod n, little-endian
        BigInteger rr = BigInteger.ONE.shiftLeft(MODULUS_BYTES * 8 * 2).mod(n);
        byte[] rrLe = toLeFixed(rr, MODULUS_BYTES);
        System.arraycopy(rrLe, 0, out, 8 + MODULUS_BYTES, MODULUS_BYTES);
        // exponent (uint32 LE)
        putU32(out, 8 + 2 * MODULUS_BYTES, e.longValue());
        return out;
    }

    private static void putU32(byte[] out, int off, long v) {
        out[off] = (byte) v;
        out[off + 1] = (byte) (v >> 8);
        out[off + 2] = (byte) (v >> 16);
        out[off + 3] = (byte) (v >> 24);
    }

    private static byte[] toLeFixed(BigInteger v, int len) {
        byte[] le = new byte[len];
        byte[] mag = v.toByteArray();
        int n = Math.min(len, mag.length);
        for (int i = 0; i < n; i++) le[i] = mag[mag.length - 1 - i];
        return le;
    }

    // ------------------------------------------------------------------ X.509 (DER, by hand)

    /**
     * A self-signed X.509v3 certificate for the key, mirroring adb's
     * {@code GenerateX509Certificate}: CN=OEA (adb uses "Adb"), 10-year
     * validity, critical basicConstraints CA:TRUE, critical keyUsage
     * (digitalSignature | keyCertSign | cRLSign) and subjectKeyIdentifier.
     * Signed with SHA-256withRSA (PKCS#1 v1.5), like X509_sign(EVP_sha256()).
     */
    public static byte[] selfSignedCertDer(PrivateKey signer, RSAPublicKey pub, String cn) throws Exception {
        byte[] spki = spkiRsa(pub);
        long now = System.currentTimeMillis() / 1000L;
        long notAfter = now + 10L * 365 * 24 * 60 * 60;

        byte[] serial = derInteger(BigInteger.ONE);
        byte[] tbs = concat(
                new byte[]{(byte) 0xA0, 0x03, 0x02, 0x01, 0x02},          // [0] version = v3
                serial,
                sigAlgSha256Rsa(),
                nameCn(cn),                                         // issuer
                derSequence(derUtcTime(now), derUtcTime(notAfter)), // validity
                nameCn(cn),                                         // subject
                spki,
                derTaggedExplicit(3, derSequence(
                        extension(oidBasicConstraints, true, derSequence(new byte[]{0x01, 0x01, (byte) 0xFF})),
                        extension(oidKeyUsage, true, new byte[]{0x03, 0x02, 0x01, (byte) 0x86}),
                        extension(oidSubjectKeyIdentifier, false, derOctetString(
                                derOctetInner(sha1(spki)))))));
        byte[] tbsDer = derSequence(tbs);
        java.security.Signature sig = java.security.Signature.getInstance("SHA256withRSA");
        sig.initSign(signer);
        sig.update(tbsDer);
        byte[] signature = sig.sign();
        return derSequence(tbsDer, sigAlgSha256Rsa(), derBitString(signature));
    }

    private static byte[] sha1(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-1").digest(data);
    }

    private static byte[] derOctetInner(byte[] content) {
        // subjectKeyIdentifier = OCTET STRING containing the 20-byte hash;
        // the extension value itself is a DER OCTET STRING wrapping it.
        return content;
    }

    private static final byte[] oidBasicConstraints = {0x06, 0x03, 0x55, 0x1D, 0x13};
    private static final byte[] oidKeyUsage = {0x06, 0x03, 0x55, 0x1D, 0x0F};
    private static final byte[] oidSubjectKeyIdentifier = {0x06, 0x03, 0x55, 0x1D, 0x0E};

    private static byte[] extension(byte[] oid, boolean critical, byte[] value) {
        return critical
                ? derSequence(oid, new byte[]{0x01, 0x01, (byte) 0xFF}, derOctetString(value))
                : derSequence(oid, derOctetString(value));
    }

    private static byte[] nameCn(String cn) {
        // SEQUENCE { SET { SEQUENCE { OID(2.5.4.3 commonName), UTF8String } } }
        byte[] oid = {0x06, 0x03, 0x55, 0x04, 0x03};
        byte[] utf8 = derUtf8(cn);
        return derSequence(derSet(derSequence(oid, utf8)));
    }

    private static byte[] sigAlgSha256Rsa() {
        // SEQUENCE { OID 1.2.840.113549.1.1.11 sha256WithRSAEncryption, NULL }
        return derSequence(new byte[]{0x06, 0x09, 0x2A, (byte) 0x86, 0x48, (byte) 0x86,
                (byte) 0xF7, 0x0D, 0x01, 0x01, 0x0B}, new byte[]{0x05, 0x00});
    }

    private static byte[] spkiRsa(RSAPublicKey pub) {
        // SEQUENCE { SEQUENCE { OID rsaEncryption, NULL }, BIT STRING { RSAPublicKey DER } }
        byte[] modulus = trimLeadingZero(pub.getModulus().toByteArray());
        byte[] exponent = trimLeadingZero(pub.getPublicExponent().toByteArray());
        byte[] rsaKey = derSequence(derUnsignedInteger(modulus), derUnsignedInteger(exponent));
        byte[] alg = new byte[]{0x30, 0x0D, 0x06, 0x09, 0x2A, (byte) 0x86, 0x48, (byte) 0x86,
                (byte) 0xF7, 0x0D, 0x01, 0x01, 0x01, 0x05, 0x00};
        return derSequence(alg, derBitString(rsaKey));
    }

    // ------------------------------------------------------------------ DER helpers

    private static byte[] derSequence(byte[]... parts) {
        int total = 0;
        for (byte[] p : parts) total += p.length;
        byte[] all = concat(parts);
        if (all.length < 128) {
            return concat(new byte[]{0x30, (byte) all.length}, all);
        }
        return concat(new byte[]{0x30}, derLength(all.length), all);
    }

    private static byte[] derSet(byte[] content) {
        if (content.length < 128) return concat(new byte[]{0x31, (byte) content.length}, content);
        return concat(new byte[]{0x31}, derLength(content.length), content);
    }

    private static byte[] derTaggedExplicit(int number, byte[] content) {
        byte tag = (byte) (0xA0 | number);
        if (content.length < 128) return concat(new byte[]{tag, (byte) content.length}, content);
        return concat(new byte[]{tag}, derLength(content.length), content);
    }

    private static byte[] derInteger(BigInteger v) {
        return derUnsignedInteger(trimLeadingZero(v.toByteArray()));
    }

    private static byte[] derUnsignedInteger(byte[] magnitude) {
        if (magnitude.length == 0 || (magnitude[0] & 0x80) != 0) {
            byte[] padded = new byte[magnitude.length + 1];
            padded[0] = 0;
            System.arraycopy(magnitude, 0, padded, 1, magnitude.length);
            magnitude = padded;
        }
        if (magnitude.length < 128) return concat(new byte[]{0x02, (byte) magnitude.length}, magnitude);
        return concat(new byte[]{0x02}, derLength(magnitude.length), magnitude);
    }

    private static byte[] derBitString(byte[] data) {
        int len = data.length + 1;
        if (len < 128) return concat(new byte[]{0x03, (byte) len, 0x00}, data);
        return concat(new byte[]{0x03}, derLength(len), new byte[]{0x00}, data);
    }

    private static byte[] derOctetString(byte[] data) {
        if (data.length < 128) return concat(new byte[]{0x04, (byte) data.length}, data);
        return concat(new byte[]{0x04}, derLength(data.length), data);
    }

    private static byte[] derUtf8(String s) {
        byte[] data = s.getBytes(StandardCharsets.UTF_8);
        if (data.length < 128) return concat(new byte[]{0x0C, (byte) data.length}, data);
        return concat(new byte[]{0x0C}, derLength(data.length), data);
    }

    private static byte[] derUtcTime(long epochSeconds) {
        java.time.Instant instant = java.time.Instant.ofEpochSecond(epochSeconds);
        java.time.ZonedDateTime z = instant.atZone(java.time.ZoneOffset.UTC);
        String text = String.format("%02d%02d%02d%02d%02d%02dZ",
                z.getYear() % 100, z.getMonthValue(), z.getDayOfMonth(),
                z.getHour(), z.getMinute(), z.getSecond());
        byte[] data = text.getBytes(StandardCharsets.US_ASCII);
        return concat(new byte[]{0x17, (byte) data.length}, data);
    }

    private static byte[] derLength(int length) {
        if (length < 128) return new byte[]{(byte) length};
        int n = (32 - Integer.numberOfLeadingZeros(length) + 7) / 8;
        byte[] out = new byte[1 + n];
        out[0] = (byte) (0x80 | n);
        for (int i = 0; i < n; i++) out[1 + i] = (byte) (length >> (8 * (n - 1 - i)));
        return out;
    }

    // ------------------------------------------------------------------ misc

    private static byte[] pemBody(String pem, String label) {
        String b64 = pem.replace("-----BEGIN " + label + "-----", "")
                .replace("-----END " + label + "-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(b64);
    }

    private static String wrap64(String b64) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b64.length(); i += 64) {
            if (i > 0) sb.append('\n');
            sb.append(b64, i, Math.min(b64.length(), i + 64));
        }
        return sb.toString();
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] p : parts) total += p.length;
        byte[] out = new byte[total];
        int pos = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
        }
        return out;
    }

    private static byte[] trimLeadingZero(byte[] v) {
        int i = 0;
        while (i < v.length - 1 && v[i] == 0) i++;
        return i == 0 ? v : Arrays.copyOfRange(v, i, v.length);
    }
}
