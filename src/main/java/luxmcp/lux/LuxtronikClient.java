package luxmcp.lux;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Minimal client for the Luxtronik 2.x "config interface" (CFI): a plain TCP socket, usually on
 * port 8889, speaking big-endian int32s.
 * <pre>
 *   &gt; 3003 0            read parameters    &lt; 3003 len  int32[len]
 *   &gt; 3004 0            read calculations  &lt; 3004 stat len int32[len]
 *   &gt; 3005 0            read visibilities  &lt; 3005 len  int8[len]
 *   &gt; 3002 index value  write parameter    &lt; 3002 value
 * </pre>
 * The controller is a slow embedded box that gets unstable under concurrent access, so every
 * operation takes a lock and uses a fresh short-lived connection, like python-luxtronik does.
 */
public final class LuxtronikClient {

    private static final Logger log = LoggerFactory.getLogger(LuxtronikClient.class);

    public static final int DEFAULT_PORT = 8889;
    static final int CMD_WRITE_PARAMETER = 3002;
    static final int CMD_READ_PARAMETERS = 3003;
    static final int CMD_READ_CALCULATIONS = 3004;
    static final int CMD_READ_VISIBILITIES = 3005;
    private static final int MAX_LENGTH = 100_000;

    /** One complete read of all three vectors. */
    public record RawData(int[] parameters, int[] calculations, int[] visibilities, Instant readAt) {
        public int[] get(RegisterKind kind) {
            switch (kind) {
                case PARAMETERS: return parameters;
                case CALCULATIONS: return calculations;
                default: return visibilities;
            }
        }
    }

    private final String host;
    private final int port;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final ReentrantLock lock = new ReentrantLock();

    public LuxtronikClient(String host, int port) {
        this(host, port, 5_000, 20_000);
    }

    public LuxtronikClient(String host, int port, int connectTimeoutMs, int readTimeoutMs) {
        this.host = host;
        this.port = port;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    /** Read parameters, calculations and visibilities in one connection. */
    public RawData readAll() throws IOException {
        lock.lock();
        try (Socket socket = connect()) {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            int[] params = readVector(in, out, CMD_READ_PARAMETERS, false);
            int[] calcs = readVector(in, out, CMD_READ_CALCULATIONS, true);
            int[] vis = readVisibilities(in, out);
            log.debug("{}:{} read {} parameters, {} calculations, {} visibilities", host, port,
                    params.length, calcs.length, vis.length);
            return new RawData(params, calcs, vis, Instant.now());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Write one parameter register. Returns the value echoed by the controller. The caller should
     * wait about a second and re-read before trusting the new state (the controller recalculates).
     */
    public int writeParameter(int index, int raw) throws IOException {
        if (index < 0) {
            throw new IllegalArgumentException("negative parameter index");
        }
        lock.lock();
        try (Socket socket = connect()) {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.writeInt(CMD_WRITE_PARAMETER);
            out.writeInt(index);
            out.writeInt(raw);
            out.flush();
            int cmd = in.readInt();
            if (cmd != CMD_WRITE_PARAMETER) {
                throw new IOException("unexpected reply command " + cmd + " to write of parameter " + index);
            }
            int echoed = in.readInt();
            log.info("{}:{} wrote parameter {} = {} (controller replied {})", host, port, index, raw, echoed);
            return echoed;
        } finally {
            lock.unlock();
        }
    }

    private Socket connect() throws IOException {
        Socket socket = new Socket();
        socket.setSoTimeout(readTimeoutMs);
        socket.setTcpNoDelay(true);
        try {
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
        } catch (IOException e) {
            socket.close();
            throw new IOException("cannot connect to Luxtronik controller at " + host + ":" + port + " (" + e.getMessage() + ")", e);
        }
        return socket;
    }

    private static int[] readVector(DataInputStream in, DataOutputStream out, int command, boolean hasStat) throws IOException {
        out.writeInt(command);
        out.writeInt(0);
        out.flush();
        int cmd = in.readInt();
        if (cmd != command) {
            throw new IOException("unexpected reply command " + cmd + " for " + command);
        }
        if (hasStat) {
            in.readInt(); // status word, unused
        }
        int length = in.readInt();
        checkLength(length);
        int[] data = new int[length];
        for (int i = 0; i < length; i++) {
            data[i] = in.readInt();
        }
        return data;
    }

    private static int[] readVisibilities(DataInputStream in, DataOutputStream out) throws IOException {
        out.writeInt(CMD_READ_VISIBILITIES);
        out.writeInt(0);
        out.flush();
        int cmd = in.readInt();
        if (cmd != CMD_READ_VISIBILITIES) {
            throw new IOException("unexpected reply command " + cmd + " for " + CMD_READ_VISIBILITIES);
        }
        int length = in.readInt();
        checkLength(length);
        int[] data = new int[length];
        for (int i = 0; i < length; i++) {
            data[i] = in.readByte(); // signed int8, like python's ">b"
        }
        return data;
    }

    private static void checkLength(int length) throws IOException {
        if (length < 0 || length > MAX_LENGTH) {
            throw new IOException("implausible vector length " + length + " - not a Luxtronik controller?");
        }
    }
}
