package com.oea.launcher.privilege.adb;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The ADB wire protocol ({@code adb/adb.h}) over wireless debugging: plain
 * CNXN handshake, A_STLS upgrade to TLS 1.3 (OEA authenticates with the key it
 * paired with), second CNXN inside TLS, then ordinary A_OPEN/A_OKAY/A_WRTE
 * streams ("shell:v2", "sync:", "tcp:", ...).
 *
 * From protocol version 0x01000001 the data checksum is zero, matching adbd's
 * {@code A_VERSION_SKIP_CHECKSUM}.
 */
public final class AdbConnection {
    public static final int A_CNXN = 0x4E584E43; // "CNXN"
    public static final int A_AUTH = 0x53545541; // "AUTH"
    public static final int A_OPEN = 0x4E45504F; // "OPEN"
    public static final int A_OKAY = 0x59414B4F; // "OKAY"
    public static final int A_CLSE = 0x45534C43; // "CLSE"
    public static final int A_WRTE = 0x45545257; // "WRTE"
    public static final int A_STLS = 0x534C5453; // "STLS"

    private static final int VERSION = 0x01000001;
    private static final int STLS_VERSION = 0x01000000;
    private static final int MAX_PAYLOAD = 256 * 1024;
    private static final String FEATURES = "shell_v2,cmd,stat_v2,ls_v2,fixed_push_mkdir,apex,"
            + "abb,fixed_push_symlink_timestamp,abb_exec,remount_shell,track_app,"
            + "sendrecv_v2,openscreen_mdns";

    private final Socket socket;
    private final AdbKeys keys;
    private InputStream source;
    private OutputStream sink;
    private Tls13Client tls;
    private volatile boolean closed;
    private final LinkedBlockingQueue<Packet> deferred = new LinkedBlockingQueue<>();

    /** True once {@link #close()} has been called. */
    public boolean isClosed() {
        return closed;
    }

    public AdbConnection(Socket socket, AdbKeys keys) throws IOException {
        this.socket = socket;
        this.keys = keys;
        this.source = socket.getInputStream();
        this.sink = socket.getOutputStream();
    }

    /** Device capabilities reported in the post-TLS CNXN. */
    public static final class Banner {
        public long version;
        public int maxPayload;
        public String features = "";
        public boolean hasFeature(String feature) {
            for (String part : features.split(",")) {
                if (part.trim().equals(feature)) return true;
            }
            return false;
        }
    }

    public static final class Packet {
        public int command;
        public long arg0;
        public long arg1;
        public byte[] payload = new byte[0];

        @Override public String toString() {
            return name(command) + " arg0=" + arg0 + " arg1=" + arg1 + " len=" + payload.length;
        }
    }

    /**
     * Runs the connect sequence: CNXN, A_STLS, TLS 1.3 handshake, CNXN.
     * Returns the device banner from the encrypted CNXN.
     */
    public Banner connect() throws IOException {
        Banner plain = expectCnxn("plain");
        writePacket(A_STLS, STLS_VERSION, 0, new byte[0]);
        tls = new Tls13Client(source, sink, keys.identity());
        tls.handshake();
        TlsSource tlsIn = new TlsSource(tls);
        source = tlsIn;
        sink = new TlsSink(tls);
        expectCnxn("tls");
        return plain;
    }

    private Banner expectCnxn(String phase) throws IOException {
        writePacket(A_CNXN, VERSION, MAX_PAYLOAD,
                (FEATURES + "\u0000").getBytes(StandardCharsets.US_ASCII));
        Packet p = readPacket();
        if (p.command == A_AUTH) {
            throw new IOException("adb: device wants legacy AUTH (not paired on this key)");
        }
        if (p.command != A_CNXN) {
            throw new IOException("adb: expected CNXN (" + phase + "), got " + name(p.command));
        }
        Banner banner = new Banner();
        banner.version = p.arg0;
        banner.maxPayload = (int) p.arg1;
        if (p.payload.length > 0) {
            int n = p.payload[p.payload.length - 1] == 0 ? p.payload.length - 1 : p.payload.length;
            banner.features = new String(p.payload, 0, n, StandardCharsets.US_ASCII);
        }
        return banner;
    }

    /** Opens a stream ("shell:v2,...", "sync:...", ...); returns the local id. */
    public int open(String destination) throws IOException {
        int localId = destination.hashCode() & 0x7FFFFFFF | 1;
        writePacket(A_OPEN, localId, 0, destination.getBytes(StandardCharsets.UTF_8));
        while (true) {
            Packet p = readPacket();
            if (p.command == A_OKAY && p.arg1 == localId) return localId;
            if (p.command == A_CLSE && p.arg1 == localId) {
                throw new IOException("adb: stream refused: " + destination);
            }
            if (p.command == A_WRTE && p.arg1 == localId) {
                deferred.add(p); // data raced ahead of OKAY
            }
        }
    }

    public void writeStreamData(int localId, int remoteId, byte[] data) throws IOException {
        int pos = 0;
        while (pos < data.length) {
            int n = Math.min(MAX_PAYLOAD, data.length - pos);
            byte[] chunk = new byte[n];
            System.arraycopy(data, pos, chunk, 0, n);
            writePacket(A_WRTE, localId, remoteId, chunk);
            pos += n;
        }
    }

    public void closeStream(int localId, int remoteId) throws IOException {
        writePacket(A_CLSE, localId, remoteId, new byte[0]);
    }

    /** Next packet for a stream (acking WRTE automatically); null on timeout. */
    public Packet nextPacket(long timeoutMillis) throws IOException, InterruptedException {
        Packet p = deferred.poll(0, TimeUnit.MILLISECONDS);
        if (p != null) return p;
        long deadline = System.currentTimeMillis() + Math.max(1, timeoutMillis);
        socket.setSoTimeout((int) Math.min(Integer.MAX_VALUE, Math.max(1, timeoutMillis)));
        Packet packet = readPacket();
        switch (packet.command) {
            case A_WRTE:
                writePacket(A_OKAY, packet.arg1, packet.arg0, new byte[0]);
                return packet;
            default:
                return packet;
        }
    }

    /** Convenience: run a shell command, collect stdout until the stream closes. */
    public String shell(String command, long timeoutMillis) throws Exception {
        open("shell:v2,raw:" + command);
        ByteQueue out = new ByteQueue();
        long end = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < end) {
            Packet p = nextPacket(Math.max(1, end - System.currentTimeMillis()));
            if (p == null) break;
            if (p.command == A_CLSE) break;
            if (p.command == A_WRTE && p.payload.length > 0) parseShellV2(p.payload, out);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /** shell:v2 framing: [1B channel][u32 LE length][bytes] per frame. */
    private static void parseShellV2(byte[] payload, ByteQueue out) {
        int pos = 0;
        while (pos + 5 <= payload.length) {
            int channel = payload[pos] & 0xFF;
            long len = (payload[pos + 1] & 0xFFL) | ((payload[pos + 2] & 0xFFL) << 8)
                    | ((payload[pos + 3] & 0xFFL) << 16) | ((payload[pos + 4] & 0xFFL) << 24);
            pos += 5;
            if (pos + len > payload.length) return;
            if (channel == 1) out.write(payload, pos, (int) len); // stdout
            pos += (int) len;
        }
    }

    public void close() {
        closed = true;
        if (tls != null) tls.close();
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    // ------------------------------------------------------------------ packets

    private void writePacket(int command, long arg0, long arg1, byte[] payload) throws IOException {
        byte[] pkt = new byte[24 + payload.length];
        put32(pkt, 0, command & 0xFFFFFFFFL);
        put32(pkt, 4, arg0);
        put32(pkt, 8, arg1);
        put32(pkt, 12, payload.length);
        put32(pkt, 16, 0); // checksum: zero from version 0x01000001
        put32(pkt, 20, (command ^ 0xFFFFFFFFL) & 0xFFFFFFFFL);
        System.arraycopy(payload, 0, pkt, 24, payload.length);
        sink.write(pkt);
        sink.flush();
    }

    private Packet readPacket() throws IOException {
        byte[] header = readFully(24);
        Packet p = new Packet();
        p.command = (int) get32(header, 0);
        long magic = get32(header, 20);
        if ((p.command & 0xFFFFFFFFL) != (magic ^ 0xFFFFFFFFL)) {
            throw new IOException("adb: bad packet magic " + Long.toHexString(magic));
        }
        p.arg0 = get32(header, 4);
        p.arg1 = get32(header, 8);
        int dataLen = (int) get32(header, 12);
        if (dataLen < 0 || dataLen > MAX_PAYLOAD) {
            throw new IOException("adb: bad data length " + dataLen);
        }
        if (dataLen > 0) p.payload = readFully(dataLen);
        return p;
    }

    private byte[] readFully(int n) throws IOException {
        byte[] out = new byte[n];
        int off = 0;
        while (off < n) {
            int r = source.read(out, off, n - off);
            if (r < 0) throw new EOFException("adb: connection closed");
            off += r;
        }
        return out;
    }

    private static void put32(byte[] b, int off, long v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
        b[off + 2] = (byte) (v >> 16);
        b[off + 3] = (byte) (v >> 24);
    }

    private static long get32(byte[] b, int off) {
        return (b[off] & 0xFFL) | ((b[off + 1] & 0xFFL) << 8) | ((b[off + 2] & 0xFFL) << 16)
                | ((b[off + 3] & 0xFFL) << 24);
    }

    private static String name(int command) {
        switch (command) {
            case A_CNXN: return "CNXN";
            case A_AUTH: return "AUTH";
            case A_OPEN: return "OPEN";
            case A_OKAY: return "OKAY";
            case A_CLSE: return "CLSE";
            case A_WRTE: return "WRTE";
            case A_STLS: return "STLS";
            default: return String.format("0x%08x", command);
        }
    }

    /** Bridges TLS application data into a blocking InputStream. */
    private static final class TlsSource extends InputStream {
        private final Tls13Client tls;
        private byte[] chunk = new byte[0];
        private int pos;

        TlsSource(Tls13Client tls) {
            this.tls = tls;
        }

        @Override public int read() throws IOException {
            if (pos >= chunk.length) chunk = tls.readApplicationData();
            return chunk[pos++] & 0xFF;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (pos >= chunk.length) {
                chunk = tls.readApplicationData();
                pos = 0;
            }
            int n = Math.min(len, chunk.length - pos);
            System.arraycopy(chunk, pos, b, off, n);
            pos += n;
            return n;
        }
    }

    private static final class TlsSink extends OutputStream {
        private final Tls13Client tls;
        TlsSink(Tls13Client tls) {
            this.tls = tls;
        }
        @Override public void write(int b) throws IOException {
            tls.writeApplicationData(new byte[]{(byte) b});
        }
        @Override public void write(byte[] b, int off, int len) throws IOException {
            byte[] copy = new byte[len];
            System.arraycopy(b, off, copy, 0, len);
            tls.writeApplicationData(copy);
        }
        @Override public void flush() {
        }
    }

    private static final class ByteQueue {
        private byte[] data = new byte[1024];
        private int size;
        void write(byte[] b, int off, int len) {
            if (size + len > data.length) {
                byte[] bigger = new byte[Math.max(data.length * 2, size + len)];
                System.arraycopy(data, 0, bigger, 0, size);
                data = bigger;
            }
            System.arraycopy(b, off, data, size, len);
            size += len;
        }
        byte[] toByteArray() {
            byte[] out = new byte[size];
            System.arraycopy(data, 0, out, 0, size);
            return out;
        }
    }
}
