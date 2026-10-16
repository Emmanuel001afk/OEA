package com.oea.launcher.privilege.adb;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * The wireless-debugging pairing protocol ({@code adb pair}), client side —
 * OEA plays the role the desktop's adb plays ({@code pairing_client.cpp}).
 *
 * Wire summary (all on top of a mutual-certificate TLS 1.3 connection):
 *
 *  1. both sides export 64 bytes of TLS keying material ("adb-label" NUL
 *     label) and append it to the ASCII pairing code: the SPAKE2 password;
 *  2. SPAKE2 message exchange in 6-byte packets
 *     {version=1, type, u32 BE payload}: type 0 = SPAKE2 message (32 bytes);
 *  3. each side AES-128-GCM-encrypts its PeerInfo (type + 8191 data bytes,
 *     packed) under the SPAKE2 key and they exchange them (type 1).
 *
 * Pairing succeeds only if both sides decrypt each other's PeerInfo — the
 * phone then stores OEA's ADB public key for the TLS adb connection.
 */
public final class PairingConnection {

    /** Result of a successful pairing: the phone's identity and its public key line. */
    public interface Listener {
        void onPairingResult(boolean success, String deviceGuid, Exception error);
    }

    private static final int PACKET_VERSION = 1;
    private static final int TYPE_SPAKE2_MSG = 0;
    private static final int TYPE_PEER_INFO = 1;
    private static final int MAX_PAYLOAD = 16384;
    private static final int PEER_INFO_SIZE = 8192;
    private static final int EXPORTED_KEY_SIZE = 64;

    private final Socket socket;
    private final AdbKeys keys;
    private final String pairingCode;

    public PairingConnection(Socket socket, AdbKeys keys, String pairingCode) {
        this.socket = socket;
        this.keys = keys;
        this.pairingCode = pairingCode;
    }

    /** Runs the blocking pairing exchange. Returns the device's public-key line. */
    public String pair() throws IOException {
        Tls13Client tls = new Tls13Client(socket.getInputStream(), socket.getOutputStream(),
                keys.identity());
        tls.handshake();

        // The SPAKE2 password: ASCII pairing code ++ 64 exported TLS bytes,
        // so the connection cannot be stolen while the PAKE runs.
        byte[] exported = tls.exportKeyingMaterial(new byte[]{'a', 'd', 'b', '-', 'l', 'a', 'b', 'e', 'l', 0},
                EXPORTED_KEY_SIZE);
        byte[] password = concat(pairingCode.getBytes(StandardCharsets.US_ASCII), exported);

        Spake2 spake2 = new Spake2(Spake2.Role.CLIENT, password);
        byte[] myMsg = spake2.message();

        writePacket(tls, TYPE_SPAKE2_MSG, myMsg);
        byte[] theirMsg = readPacket(tls, TYPE_SPAKE2_MSG);
        byte[] keyMaterial = spake2.processMessage(theirMsg);
        if (keyMaterial == null) {
            throw new IOException("pairing: invalid SPAKE2 message from the phone");
        }
        PairingCipher cipher = new PairingCipher(keyMaterial);

        // PeerInfo: type ADB_RSA_PUB_KEY + the ".pub" line, NUL padded to 8192.
        byte[] peerInfo = peerInfoBytes((byte) 0, keys.publicKeyLine());
        writePacket(tls, TYPE_PEER_INFO, cipher.seal(peerInfo));
        byte[] theirInfo = cipher.open(readPacket(tls, TYPE_PEER_INFO));
        if (theirInfo.length != PEER_INFO_SIZE) {
            throw new IOException("pairing: bad PeerInfo size " + theirInfo.length);
        }
        if (theirInfo[0] != 1) { // ADB_DEVICE_GUID
            throw new IOException("pairing: unexpected PeerInfo type " + theirInfo[0]);
        }
        tls.close();
        return nulTerminated(theirInfo);
    }

    /** Convenience wrapper: runs {@link #pair()} and reports through {@code listener}. */
    public void start(final Listener listener) {
        new Thread(() -> {
            try {
                String guid = pair();
                listener.onPairingResult(true, guid, null);
            } catch (Exception e) {
                listener.onPairingResult(false, null, e instanceof IOException ? (IOException) e
                        : new IOException(e));
            }
        }, "oea-adb-pairing").start();
    }

    // ------------------------------------------------------------------ packets

    private static void writePacket(Tls13Client tls, int type, byte[] payload) throws IOException {
        if (payload.length == 0 || payload.length > MAX_PAYLOAD) {
            throw new IOException("pairing: payload size " + payload.length);
        }
        byte[] header = {(byte) PACKET_VERSION, (byte) type,
                (byte) (payload.length >> 24), (byte) (payload.length >> 16),
                (byte) (payload.length >> 8), (byte) payload.length};
        tls.writeApplicationData(header);
        tls.writeApplicationData(payload);
    }

    private static byte[] readPacket(Tls13Client tls, int expectedType) throws IOException {
        byte[] header = readFully(tls, 6);
        int version = header[0] & 0xFF;
        int type = header[1] & 0xFF;
        long payload = ((header[2] & 0xFFL) << 24) | ((header[3] & 0xFFL) << 16)
                | ((header[4] & 0xFFL) << 8) | (header[5] & 0xFFL);
        if (version != PACKET_VERSION) {
            throw new IOException("pairing: packet version " + version);
        }
        if (type != expectedType) {
            throw new IOException("pairing: expected packet type " + expectedType + ", got " + type);
        }
        if (payload == 0 || payload > MAX_PAYLOAD) {
            throw new IOException("pairing: payload size " + payload);
        }
        return readFully(tls, (int) payload);
    }

    private static byte[] readFully(Tls13Client tls, int n) throws IOException {
        byte[] out = new byte[n];
        int off = 0;
        while (off < n) {
            byte[] chunk = tls.readApplicationData();
            int n2 = Math.min(n - off, chunk.length);
            System.arraycopy(chunk, 0, out, off, n2);
            off += n2;
        }
        return out;
    }

    // ------------------------------------------------------------------ misc

    private static byte[] peerInfoBytes(byte type, String data) {
        byte[] out = new byte[PEER_INFO_SIZE];
        out[0] = type;
        byte[] text = data.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(text, 0, out, 1, Math.min(text.length, PEER_INFO_SIZE - 1));
        return out;
    }

    private static String nulTerminated(byte[] peerInfo) {
        int end = 1;
        while (end < peerInfo.length && peerInfo[end] != 0) end++;
        return new String(peerInfo, 1, end - 1, StandardCharsets.US_ASCII);
    }

    private static byte[] bytes(String s) {
        // adb passes sizeof(name), which includes the NUL terminator.
        byte[] text = s.getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[text.length + 1];
        System.arraycopy(text, 0, out, 0, text.length);
        return out;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
