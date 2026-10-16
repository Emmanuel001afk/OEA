package com.oea.launcher.privilege.adb;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * A minimal, dependency-free TLS 1.3 client (RFC 8446) for the two TLS
 * connections of wireless debugging:
 *
 *  - the pairing connection: adb's PairingConnection runs over TLS 1.3 and
 *    derives its SPAKE2 password partly from the TLS exporter, and
 *  - the ADB connection itself: adbd on the wireless-debugging port upgrades
 *    the socket to TLS 1.3 after an STLS packet and speaks ADB inside it.
 *
 * Both are mutual-certificate handshakes where neither certificate is checked
 * against a trust store: adbd matches the *client* certificate's public key
 * against the key saved by pairing (see {@code adbd_tls_verify_cert}), and
 * adb's own client accepts any server certificate ({@code tls_connection.cpp}
 * installs a pass-through verify callback). This client therefore:
 *
 *  - always sends its certificate (RSA-2048 with a self-signed X.509, from
 *    {@link AdbKeys}),
 *  - signs CertificateVerify with RSA-PSS/SHA-256 as TLS 1.3 requires,
 *  - verifies the server's Finished MAC so key-schedule mistakes surface
 *    immediately, and accepts the server certificate itself.
 *
 * Only TLS_AES_128_GCM_SHA256 (0x1301) is offered: BoringSSL (adbd) always
 * supports it and both OEA pairing endpoints use P-256 key shares.
 */
public final class Tls13Client {

    /** A certificate and its signing key, as sent in the handshake. */
    public interface Identity {
        /** DER-encoded X.509 certificate. */
        byte[] certificateDer();

        /** The private key matching the certificate. */
        PrivateKey privateKey();
    }

    private static final int CT_CHANGE_CIPHER_SPEC = 20;
    private static final int CT_ALERT = 21;
    private static final int CT_HANDSHAKE = 22;
    private static final int CT_APPLICATION = 23;

    private static final int HT_CLIENT_HELLO = 1;
    private static final int HT_SERVER_HELLO = 2;
    private static final int HT_NEW_SESSION_TICKET = 4;
    private static final int HT_ENCRYPTED_EXTENSIONS = 8;
    private static final int HT_CERTIFICATE = 11;
    private static final int HT_CERTIFICATE_REQUEST = 13;
    private static final int HT_CERTIFICATE_VERIFY = 15;
    private static final int HT_FINISHED = 20;
    private static final int HT_KEY_UPDATE = 24;

    private static final byte[] HELLO_RETRY_RANDOM = hex(
            "CF21AD74E59A6111BE1D8C021E65B891C2A211167ABB8C5E079E09E2C8A8339C");
    private static final int TAG_LEN = 16;

    private final InputStream in;
    private final OutputStream out;
    private final Identity identity;
    private final SecureRandom random = new SecureRandom();
    private final Hkdf.Transcript transcript = new Hkdf.Transcript();

    private byte[] clientRandom = new byte[32];
    private PrivateKey ecdhPrivate;
    private byte[] ecdhPublicEncoded;
    private ECParameterSpec ecParams;
    private byte[] serverKeyShare;

    private byte[] handshakeSecret;
    private byte[] clientHandshakeSecret, serverHandshakeSecret;   // traffic secrets
    private byte[] clientHandshakeKey, serverHandshakeKey;         // AES-128 keys
    private byte[] clientHandshakeIv, serverHandshakeIv;
    private byte[] clientApplicationSecret, serverApplicationSecret;
    private byte[] clientApplicationKey, serverApplicationKey, clientApplicationIv, serverApplicationIv;
    private byte[] exporterSecret;

    // Current record-layer keys (switched at the phase transitions).
    private byte[] writeKey, writeIv;
    private byte[] readKey, readIv;
    private long writeSeq, readSeq;

    private byte[] pending = new byte[0]; // decrypted bytes not yet consumed
    private int pendingType;              // content type of the pending record
    private boolean hasPendingRecord;
    private boolean encryptedReads;
    private boolean handshakeDone;
    private boolean closed;

    public Tls13Client(InputStream in, OutputStream out, Identity identity) {
        this.in = in;
        this.out = out;
        this.identity = identity;
    }

    /** Runs the full 1-RTT handshake; throws on any failure. */
    public void handshake() throws IOException {
        sendClientHello();
        deriveHandshakeSecrets();
        encryptedReads = true;
        readKey = serverHandshakeKey;
        readIv = serverHandshakeIv;
        readSeq = 0;
        writeKey = clientHandshakeKey;
        writeIv = clientHandshakeIv;
        writeSeq = 0;

        byte[] serverRequestContext = new byte[0];
        boolean gotEncryptedExtensions = false;
        boolean gotCertificate = false;
        boolean serverFinishedSeen = false;
        byte[] serverFinishedMessage = null;
        while (!serverFinishedSeen) {
            byte[] message = readHandshakeMessage();
            int type = message[0] & 0xFF;
            byte[] body = Arrays.copyOfRange(message, 4, message.length);
            switch (type) {
                case HT_ENCRYPTED_EXTENSIONS:
                    gotEncryptedExtensions = true;
                    break;
                case HT_CERTIFICATE_REQUEST: {
                    int ctxLen = body.length > 0 ? (body[0] & 0xFF) : 0;
                    if (ctxLen > 0) serverRequestContext = Arrays.copyOfRange(body, 1, 1 + ctxLen);
                    break;
                }
                case HT_CERTIFICATE:
                    gotCertificate = true;
                    break;
                case HT_CERTIFICATE_VERIFY:
                    break;
                case HT_FINISHED:
                    // MAC covers the transcript up to but excluding this message.
                    byte[] serverFinishedKey = Hkdf.expandLabel(serverHandshakeSecret, "finished",
                            new byte[0], Hkdf.HASH_LEN);
                    byte[] expected = Hkdf.hmacSha256(serverFinishedKey, transcript.currentHash());
                    byte[] verifyData = Arrays.copyOfRange(message, 4, message.length);
                    if (!java.security.MessageDigest.isEqual(expected, verifyData)) {
                        throw new IOException("TLS: server Finished did not verify");
                    }
                    serverFinishedSeen = true;
                    break;
                case HT_NEW_SESSION_TICKET:
                    continue; // tickets are ignored; not part of the transcript here
                default:
                    throw new IOException("TLS: unexpected handshake message type " + type);
            }
            transcript.update(message);
        }
        if (!gotEncryptedExtensions || !gotCertificate) {
            throw new IOException("TLS: server flight is missing required messages");
        }
        deriveApplicationSecrets();
        sendClientFlight(serverRequestContext);
        handshakeDone = true;
    }

    // ------------------------------------------------------------------ flights

    private void sendClientHello() throws IOException {
        random.nextBytes(clientRandom);
        generateEcdhKeyPair();

        byte[] keyShare = new byte[2 + 2 + ecdhPublicEncoded.length];
        keyShare[0] = 0x00; keyShare[1] = 0x17; // group secp256r1
        keyShare[2] = (byte) (ecdhPublicEncoded.length >> 8);
        keyShare[3] = (byte) ecdhPublicEncoded.length;
        System.arraycopy(ecdhPublicEncoded, 0, keyShare, 4, ecdhPublicEncoded.length);

        byte[] extensions = concat(
                ext(10, u16Bytes(new byte[]{0x00, 0x17})), // supported_groups: [len=2] secp256r1
                ext(13, u16v(new int[]{0x0403, 0x0804})),  // sig algs: ecdsa_secp256r1, rsa_pss_rsae_sha256
                ext(43, new byte[]{0x02, 0x03, 0x04}),     // supported_versions: [len=2] TLS 1.3
                ext(51, u16Bytes(keyShare)));               // key_share client_shares

        byte[] body = concat(
                u16(0x0303),
                clientRandom,
                new byte[]{0},              // legacy_session_id: empty
                u16Bytes(new byte[]{0x13, 0x01}), // one offered suite: TLS_AES_128_GCM_SHA256
                new byte[]{1, 0},           // legacy compression: null
                u16Bytes(extensions));
        byte[] message = handshakeMessage(HT_CLIENT_HELLO, body);
        transcript.update(message);
        out.write(recordHeader(CT_HANDSHAKE, message.length));
        out.write(message);
        out.flush();

        byte[] sh = readHandshakeMessage();
        if ((sh[0] & 0xFF) != HT_SERVER_HELLO) {
            throw new IOException("TLS: expected ServerHello, got type " + (sh[0] & 0xFF));
        }
        transcript.update(sh);
        byte[] shBody = Arrays.copyOfRange(sh, 4, sh.length);
        if (shBody.length < 2 + 32 + 1) throw new IOException("TLS: short ServerHello");
        byte[] serverRandom = Arrays.copyOfRange(shBody, 2, 34);
        if (Arrays.equals(serverRandom, HELLO_RETRY_RANDOM)) {
            throw new IOException("TLS: server sent HelloRetryRequest (P-256 key share rejected)");
        }
        serverKeyShare = parseServerKeyShare(shBody);
    }

    /**
     * Extracts the secp256r1 point from the ServerHello key_share extension.
     * Returns null when the server picked another structure (which this client
     * cannot continue with).
     */
    private static byte[] parseServerKeyShare(byte[] shBody) throws IOException {
        int pos = 2 + 32;
        int sessionIdLen = shBody[pos] & 0xFF;
        pos += 1 + sessionIdLen;
        if (pos + 2 + 1 > shBody.length) throw new IOException("TLS: truncated ServerHello");
        pos += 2;                    // cipher suite
        pos += 1;                    // compression method
        if (pos + 2 > shBody.length) return null;
        int extensionsLen = ((shBody[pos] & 0xFF) << 8) | (shBody[pos + 1] & 0xFF);
        pos += 2;
        int end = Math.min(shBody.length, pos + extensionsLen);
        while (pos + 4 <= end) {
            int extType = ((shBody[pos] & 0xFF) << 8) | (shBody[pos + 1] & 0xFF);
            int extLen = ((shBody[pos + 2] & 0xFF) << 8) | (shBody[pos + 3] & 0xFF);
            pos += 4;
            if (extType == 51 && pos + extLen <= end && extLen >= 4) {
                int group = ((shBody[pos] & 0xFF) << 8) | (shBody[pos + 1] & 0xFF);
                int pointLen = ((shBody[pos + 2] & 0xFF) << 8) | (shBody[pos + 3] & 0xFF);
                if (group == 23 && pos + 4 + pointLen <= end) {
                    return Arrays.copyOfRange(shBody, pos + 4, pos + 4 + pointLen);
                }
                return null;
            }
            pos += extLen;
        }
        return null;
    }

    private void deriveHandshakeSecrets() throws IOException {
        if (serverKeyShare == null) {
            throw new IOException("TLS: server did not accept the P-256 key share");
        }
        byte[] shared = sharedWithServer(serverKeyShare);
        byte[] earlySecret = Hkdf.extract(null, new byte[Hkdf.HASH_LEN]); // salt=0, IKM=0 per RFC 8446
        byte[] derivedEarly = Hkdf.deriveSecret(earlySecret, "derived", null);
        if (DEBUG) { debug("early", earlySecret); debug("derived", derivedEarly); }
        if (DEBUG) debug("shared", shared);
        handshakeSecret = Hkdf.extract(derivedEarly, shared);
        if (DEBUG) debug("handshakeSecret", handshakeSecret);
        clientHandshakeSecret = Hkdf.deriveSecret(handshakeSecret, "c hs traffic", transcript);
        serverHandshakeSecret = Hkdf.deriveSecret(handshakeSecret, "s hs traffic", transcript);
        clientHandshakeKey = Hkdf.expandLabel(clientHandshakeSecret, "key", new byte[0], 16);
        serverHandshakeKey = Hkdf.expandLabel(serverHandshakeSecret, "key", new byte[0], 16);
        clientHandshakeIv = Hkdf.expandLabel(clientHandshakeSecret, "iv", new byte[0], 12);
        serverHandshakeIv = Hkdf.expandLabel(serverHandshakeSecret, "iv", new byte[0], 12);
        if (DEBUG) {
            debug("transcriptAfterSH", transcript.currentHash());
            debug("c_hs", clientHandshakeSecret);
            debug("s_hs", serverHandshakeSecret);
            debug("s_hs_key", serverHandshakeKey);
            debug("s_hs_iv", serverHandshakeIv);
            debug("c_hs_key", clientHandshakeKey);
            debug("c_hs_iv", clientHandshakeIv);
        }
    }

    private static final boolean DEBUG = Boolean.getBoolean("oea.tls.debug");

    private static void debug(String name, byte[] value) {
        StringBuilder sb = new StringBuilder(name).append('=');
        for (byte b : value) sb.append(String.format("%02x", b));
        System.out.println(sb);
    }

    private void deriveApplicationSecrets() {
        byte[] derivedHandshake = Hkdf.deriveSecret(handshakeSecret, "derived", null);
        byte[] masterSecret = Hkdf.extract(derivedHandshake, new byte[Hkdf.HASH_LEN]);
        clientApplicationSecret = Hkdf.deriveSecret(masterSecret, "c ap traffic", transcript);
        serverApplicationSecret = Hkdf.deriveSecret(masterSecret, "s ap traffic", transcript);
        clientApplicationKey = Hkdf.expandLabel(clientApplicationSecret, "key", new byte[0], 16);
        serverApplicationKey = Hkdf.expandLabel(serverApplicationSecret, "key", new byte[0], 16);
        clientApplicationIv = Hkdf.expandLabel(clientApplicationSecret, "iv", new byte[0], 12);
        serverApplicationIv = Hkdf.expandLabel(serverApplicationSecret, "iv", new byte[0], 12);
        exporterSecret = Hkdf.deriveSecret(masterSecret, "exp master", transcript);
    }

    private void sendClientFlight(byte[] certificateRequestContext) throws IOException {
        // Certificate
        byte[] cert = identity.certificateDer();
        byte[] certEntry = concat(u24(cert.length), cert, u16(0));
        byte[] certList = concat(u24(certEntry.length), certEntry);
        byte[] certBody = concat(new byte[]{(byte) certificateRequestContext.length},
                certificateRequestContext, certList);
        byte[] certMessage = handshakeMessage(HT_CERTIFICATE, certBody);
        transcript.update(certMessage);

        // CertificateVerify: RSA-PSS/SHA-256 over the transcript hash.
        byte[] signature = pssSign(identity.privateKey(), transcript.currentHash());
        byte[] cvMessage = handshakeMessage(HT_CERTIFICATE_VERIFY, concat(u16(0x0804), u16Bytes(signature)));
        transcript.update(cvMessage);

        // Finished
        byte[] finishedKey = Hkdf.expandLabel(clientHandshakeSecret, "finished", new byte[0], Hkdf.HASH_LEN);
        byte[] verifyData = Hkdf.hmacSha256(finishedKey, transcript.currentHash());
        if (DEBUG) {
            debug("clientFinKey", finishedKey);
            debug("clientFinHash", transcript.currentHash());
            debug("clientFin", verifyData);
            byte[] raw = transcript.rawBytes();
            System.out.println("clientTranscriptLen=" + raw.length);
            try {
                java.io.FileOutputStream fo = new java.io.FileOutputStream("/tmp/client_transcript.bin");
                fo.write(raw);
                fo.close();
            } catch (Exception ignored) {}
        }
        byte[] finishedMessage = handshakeMessage(HT_FINISHED, verifyData);
        transcript.update(finishedMessage);

        out.write(recordHeader(CT_CHANGE_CIPHER_SPEC, 1));
        out.write(new byte[]{1});
        writeEncrypted(certMessage, CT_HANDSHAKE);
        writeEncrypted(cvMessage, CT_HANDSHAKE);
        writeEncrypted(finishedMessage, CT_HANDSHAKE);
        out.flush();

        // From now on both directions use application traffic keys.
        writeKey = clientApplicationKey;
        writeIv = clientApplicationIv;
        writeSeq = 0;
        readKey = serverApplicationKey;
        readIv = serverApplicationIv;
        readSeq = 0;
    }

    // ------------------------------------------------------------------ exporter + data

    /**
     * RFC 8446 section 7.5 exporter, matching BoringSSL's
     * {@code SSL_export_keying_material}: the label bytes are used exactly as
     * given (adb passes "adb-label" including its NUL terminator), and the
     * context is empty for the pairing protocol.
     */
    public byte[] exportKeyingMaterial(byte[] label, int length) throws IOException {
        if (!handshakeDone || exporterSecret == null) throw new IOException("TLS: not connected");
        byte[] emptyHash = Hkdf.sha256Of(new byte[0]);
        byte[] derived = Hkdf.expandLabel(exporterSecret, toLatinString(label), emptyHash, Hkdf.HASH_LEN);
        return Hkdf.expandLabel(derived, "exporter", emptyHash, length);
    }

    /** Reads one application-data message. */
    public byte[] readApplicationData() throws IOException {
        while (true) {
            int type = nextRecord();
            if (type == CT_APPLICATION) return consume();
            if (type == CT_HANDSHAKE) {
                byte[] message = consume();
                int t = message.length > 0 ? (message[0] & 0xFF) : -1;
                if (t == HT_NEW_SESSION_TICKET || t == HT_KEY_UPDATE) continue;
                throw new IOException("TLS: unexpected post-handshake message " + t);
            }
            if (type == CT_ALERT) {
                byte[] alert = consume();
                if (alert.length >= 1 && (alert[0] & 0xFF) == 1) {
                    throw new EOFException("TLS: peer closed the connection");
                }
                throw new IOException("TLS: alert " + (alert.length >= 2 ? (alert[1] & 0xFF) : -1));
            }
            throw new IOException("TLS: unexpected record type " + type);
        }
    }

    public void writeApplicationData(byte[] data) throws IOException {
        if (!handshakeDone) throw new IOException("TLS: not connected");
        writeEncrypted(data);
        out.flush();
    }

    /** Sends close_notify (best effort) and marks the session closed. */
    public void close() {
        if (closed) return;
        closed = true;
        try {
            if (handshakeDone) {
                writeEncrypted(new byte[]{1, 0}, CT_ALERT);
                out.flush();
            }
        } catch (IOException ignored) {
        }
    }

    // ------------------------------------------------------------------ records

    private static byte[] recordHeader(int contentType, int length) {
        return new byte[]{(byte) contentType, 3, 3, (byte) (length >> 8), (byte) length};
    }

    private void writeEncrypted(byte[] payload) throws IOException {
        writeEncrypted(payload, CT_APPLICATION);
    }

    private void writeEncrypted(byte[] payload, int innerContentType) throws IOException {
        byte[] inner = Arrays.copyOf(payload, payload.length + 1);
        inner[payload.length] = (byte) innerContentType;
        long seq = writeSeq++;
        byte[] nonce = sequenceNonce(writeIv, seq);
        int wireLen = inner.length + TAG_LEN;
        byte[] aad = recordHeader(CT_APPLICATION, wireLen);
        out.write(aad);
        out.write(aeadSeal(writeKey, nonce, inner, aad));
    }

    /**
     * Makes the next record available; returns its content type. Decrypted
     * bytes stay in {@link #pending} until {@link #consume()}.
     */
    private int nextRecord() throws IOException {
        if (hasPendingRecord) return pendingType;
        byte[] header = readExactly(5);
        int type = header[0] & 0xFF;
        int length = ((header[3] & 0xFF) << 8) | (header[4] & 0xFF);
        if (length < 0 || length > 16640 + 256) throw new IOException("TLS: oversized record");
        byte[] payload = readExactly(length);
        if (type == CT_CHANGE_CIPHER_SPEC) {
            if (length == 1 && payload[0] == 1) return nextRecord(); // stray compat record
            throw new IOException("TLS: malformed change_cipher_spec");
        }
        byte[] plaintext = aeadOpen(readKey, sequenceNonce(readIv, readSeq++), payload, header);
        if (plaintext.length < 1) throw new IOException("TLS: empty record");
        pending = Arrays.copyOfRange(plaintext, 0, plaintext.length - 1);
        pendingType = plaintext[plaintext.length - 1] & 0xFF;
        hasPendingRecord = true;
        return pendingType;
    }

    private byte[] consume() {
        byte[] out0 = pending;
        pending = new byte[0];
        hasPendingRecord = false;
        return out0;
    }

    /**
     * One handshake message. Works on top of the record layer in both the
     * plaintext (before the ServerHello) and encrypted phases; records may
     * hold more than one message, and a message may span records.
     */
    private byte[] readHandshakeMessage() throws IOException {
        byte[] header = new byte[4];
        int filled = 0;
        long declared = -1;
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        while (filled < 4 || buffer.size() < declared) {
            int type = encryptedReads ? nextRecord() : nextPlaintextRecord();
            if (type == CT_ALERT) {
                byte[] alert = consume();
                throw new IOException("TLS: alert " + (alert.length >= 2 ? (alert[1] & 0xFF) : -1)
                        + " during handshake");
            }
            if (type != CT_HANDSHAKE) throw new IOException("TLS: expected handshake record, got " + type);
            byte[] data = consume();
            int pos = 0;
            while (pos < data.length) {
                if (filled < 4) {
                    int need = Math.min(4 - filled, data.length - pos);
                    System.arraycopy(data, pos, header, filled, need);
                    filled += need;
                    pos += need;
                    if (filled == 4) {
                        declared = ((long) (header[1] & 0xFF) << 16) | ((header[2] & 0xFF) << 8)
                                | (header[3] & 0xFF);
                        if (declared > 262144) throw new IOException("TLS: oversized handshake message");
                    }
                } else if (buffer.size() < declared) {
                    int need = (int) Math.min(declared - buffer.size(), data.length - pos);
                    buffer.write(data, pos, need);
                    pos += need;
                } else {
                    break; // message complete; any extra bytes end this record
                }
            }
        }
        byte[] body = buffer.toByteArray();
        byte[] message = new byte[4 + body.length];
        System.arraycopy(header, 0, message, 0, 4);
        System.arraycopy(body, 0, message, 4, body.length);
        return message;
    }

    /** Plaintext record read, used before the ServerHello. */
    private int nextPlaintextRecord() throws IOException {
        if (hasPendingRecord) return pendingType;
        byte[] header = readExactly(5);
        int type = header[0] & 0xFF;
        int length = ((header[3] & 0xFF) << 8) | (header[4] & 0xFF);
        byte[] payload = readExactly(length);
        if (type == CT_CHANGE_CIPHER_SPEC && length == 1 && payload[0] == 1) return nextPlaintextRecord();
        if (type != CT_HANDSHAKE) throw new IOException("TLS: expected plaintext handshake record, got " + type);
        pending = payload;
        pendingType = type;
        hasPendingRecord = true;
        return pendingType;
    }

    private byte[] readExactly(int n) throws IOException {
        byte[] out0 = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(out0, off, n - off);
            if (r < 0) throw new EOFException("TLS: connection closed by peer");
            off += r;
        }
        return out0;
    }

    // ------------------------------------------------------------------ crypto

    private static byte[] sequenceNonce(byte[] iv, long seq) {
        byte[] nonce = iv.clone();
        for (int i = 0; i < 8; i++) {
            nonce[4 + i] ^= (byte) (seq >> (56 - 8 * i));
        }
        return nonce;
    }

    private static byte[] aeadSeal(byte[] key, byte[] nonce, byte[] plaintext, byte[] aad) throws IOException {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            if (aad != null && aad.length > 0) c.updateAAD(aad);
            return c.doFinal(plaintext);
        } catch (Exception e) {
            throw new IOException("TLS: AES-GCM seal failed", e);
        }
    }

    private static byte[] aeadOpen(byte[] key, byte[] nonce, byte[] ciphertext, byte[] aad) throws IOException {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            if (aad != null && aad.length > 0) c.updateAAD(aad);
            return c.doFinal(ciphertext);
        } catch (Exception e) {
            throw new IOException("TLS: record authentication failed", e);
        }
    }

    private void generateEcdhKeyPair() throws IOException {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
            kpg.initialize(new ECGenParameterSpec("secp256r1"), random);
            KeyPair kp = kpg.generateKeyPair();
            ecdhPrivate = kp.getPrivate();
            ECPublicKey pub = (ECPublicKey) kp.getPublic();
            ecParams = pub.getParams();
            ECPoint w = pub.getW();
            byte[] x = leftPad(w.getAffineX().toByteArray(), 32);
            byte[] y = leftPad(w.getAffineY().toByteArray(), 32);
            ecdhPublicEncoded = new byte[65];
            ecdhPublicEncoded[0] = 4;
            System.arraycopy(x, 0, ecdhPublicEncoded, 1, 32);
            System.arraycopy(y, 0, ecdhPublicEncoded, 33, 32);
        } catch (Exception e) {
            throw new IOException("TLS: P-256 key generation failed", e);
        }
    }

    private byte[] sharedWithServer(byte[] serverKeyShare) throws IOException {
        try {
            if (serverKeyShare.length != 65 || serverKeyShare[0] != 4) {
                throw new IOException("TLS: server key_share is not an uncompressed P-256 point");
            }
            KeyFactory kf = KeyFactory.getInstance("EC");
            ECPoint peerW = new ECPoint(
                    new BigInteger(1, Arrays.copyOfRange(serverKeyShare, 1, 33)),
                    new BigInteger(1, Arrays.copyOfRange(serverKeyShare, 33, 65)));
            PublicKey peer = kf.generatePublic(new ECPublicKeySpec(peerW, ecParams));
            KeyAgreement ka = KeyAgreement.getInstance("ECDH");
            ka.init(ecdhPrivate);
            ka.doPhase(peer, true);
            return leftPad(ka.generateSecret(), 32);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("TLS: ECDH failed", e);
        }
    }

    private static byte[] leftPad(byte[] v, int length) {
        byte[] out0 = new byte[length];
        int n = Math.min(length, v.length);
        System.arraycopy(v, v.length - n, out0, length - n, n);
        return out0;
    }

    /**
     * RSA-PSS (SHA-256, 32-byte salt, MGF1-SHA-256, emBits = 2047) over a
     * 32-byte digest, built on the raw RSA operation so no provider-specific
     * PSS support is required.
     */
    static byte[] pssSign(PrivateKey key, byte[] mHash) throws IOException {
        try {
            byte[] salt = new byte[32];
            new SecureRandom().nextBytes(salt);
            int emLen = 256; // 2048-bit modulus, emBits = 2047
            byte[] mPrime = new byte[8 + 32 + salt.length];
            System.arraycopy(mHash, 0, mPrime, 8, 32);
            System.arraycopy(salt, 0, mPrime, 40, salt.length);
            byte[] h = Hkdf.sha256Of(mPrime);

            byte[] db = new byte[emLen - 32 - 1]; // 223
            db[db.length - 32 - 1] = 0x01;        // PS || 0x01 || salt
            System.arraycopy(salt, 0, db, db.length - 32, 32);
            byte[] dbMask = mgf1(h, db.length);
            for (int i = 0; i < db.length; i++) db[i] ^= dbMask[i];
            db[0] &= 0x7F; // the leftmost bit of EM must be zero (emBits = 8*emLen - 1)

            byte[] em = new byte[emLen];
            System.arraycopy(db, 0, em, 0, db.length);
            System.arraycopy(h, 0, em, db.length, 32);
            em[emLen - 1] = (byte) 0xBC;
            return rawRsaSign(key, em);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("TLS: PSS signing failed", e);
        }
    }

    private static byte[] mgf1(byte[] seed, int length) {
        byte[] out0 = new byte[length];
        byte[] c = new byte[seed.length + 4];
        System.arraycopy(seed, 0, c, 0, seed.length);
        int pos = 0;
        int counter = 0;
        while (pos < length) {
            c[seed.length] = (byte) (counter >> 24);
            c[seed.length + 1] = (byte) (counter >> 16);
            c[seed.length + 2] = (byte) (counter >> 8);
            c[seed.length + 3] = (byte) counter;
            byte[] hash = Hkdf.sha256Of(c);
            int n = Math.min(32, length - pos);
            System.arraycopy(hash, 0, out0, pos, n);
            pos += n;
            counter++;
        }
        return out0;
    }

    /** Raw RSA private-key operation (no padding), result zero-padded to 256 bytes. */
    private static byte[] rawRsaSign(PrivateKey key, byte[] em) throws Exception {
        Cipher cipher = Cipher.getInstance("RSA/ECB/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key); // raw private-key op with no padding
        byte[] result = cipher.doFinal(em);
        byte[] out0 = new byte[256];
        int n = Math.min(256, result.length);
        System.arraycopy(result, result.length - n, out0, 256 - n, n);
        return out0;
    }

    private static String toLatinString(byte[] b) {
        return new String(b, java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    // ------------------------------------------------------------------ small builders

    private static byte[] handshakeMessage(int type, byte[] body) {
        byte[] m = new byte[4 + body.length];
        m[0] = (byte) type;
        m[1] = (byte) (body.length >> 16);
        m[2] = (byte) (body.length >> 8);
        m[3] = (byte) body.length;
        System.arraycopy(body, 0, m, 4, body.length);
        return m;
    }

    private static byte[] ext(int type, byte[] data) {
        return concat(u16(type), u16Bytes(data));
    }

    private static byte[] u16(int v) {
        return new byte[]{(byte) (v >> 8), (byte) v};
    }

    private static byte[] u24(int v) {
        return new byte[]{(byte) (v >> 16), (byte) (v >> 8), (byte) v};
    }

    private static byte[] u16Bytes(byte[] data) {
        return concat(u16(data.length), data);
    }

    private static byte[] u16v(int[] values) {
        byte[] payload = new byte[values.length * 2];
        for (int i = 0; i < values.length; i++) {
            payload[i * 2] = (byte) (values[i] >> 8);
            payload[i * 2 + 1] = (byte) values[i];
        }
        return u16Bytes(payload);
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] p : parts) total += p.length;
        byte[] out0 = new byte[total];
        int pos = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out0, pos, p.length);
            pos += p.length;
        }
        return out0;
    }

    private static byte[] hex(String s) {
        int n = s.length();
        byte[] out0 = new byte[n / 2];
        for (int i = 0; i < n; i += 2) out0[i / 2] = (byte) Integer.parseInt(s.substring(i, i + 2), 16);
        return out0;
    }
}
