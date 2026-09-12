package luxmcp.lux;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-process stand-in for a Luxtronik controller speaking the CFI protocol on a random port.
 */
final class FakeLuxtronik implements AutoCloseable {

    final int[] parameters;
    final int[] calculations;
    final byte[] visibilities;
    final AtomicInteger writes = new AtomicInteger();
    final AtomicInteger connections = new AtomicInteger();

    private final ServerSocket server;
    private final Thread thread;
    private volatile boolean running = true;

    FakeLuxtronik(int[] parameters, int[] calculations, byte[] visibilities) throws IOException {
        this.parameters = parameters;
        this.calculations = calculations;
        this.visibilities = visibilities;
        this.server = new ServerSocket(0);
        this.thread = new Thread(this::serve, "fake-luxtronik");
        thread.setDaemon(true);
        thread.start();
    }

    int port() {
        return server.getLocalPort();
    }

    private void serve() {
        while (running) {
            try (Socket s = server.accept()) {
                connections.incrementAndGet();
                DataInputStream in = new DataInputStream(s.getInputStream());
                DataOutputStream out = new DataOutputStream(s.getOutputStream());
                while (true) {
                    int cmd;
                    try {
                        cmd = in.readInt();
                    } catch (IOException eof) {
                        break;
                    }
                    switch (cmd) {
                        case LuxtronikClient.CMD_READ_PARAMETERS:
                            in.readInt();
                            out.writeInt(cmd);
                            out.writeInt(parameters.length);
                            for (int v : parameters) {
                                out.writeInt(v);
                            }
                            break;
                        case LuxtronikClient.CMD_READ_CALCULATIONS:
                            in.readInt();
                            out.writeInt(cmd);
                            out.writeInt(0); // stat
                            out.writeInt(calculations.length);
                            for (int v : calculations) {
                                out.writeInt(v);
                            }
                            break;
                        case LuxtronikClient.CMD_READ_VISIBILITIES:
                            in.readInt();
                            out.writeInt(cmd);
                            out.writeInt(visibilities.length);
                            out.write(visibilities);
                            break;
                        case LuxtronikClient.CMD_WRITE_PARAMETER: {
                            int index = in.readInt();
                            int value = in.readInt();
                            parameters[index] = value;
                            writes.incrementAndGet();
                            out.writeInt(cmd);
                            out.writeInt(value);
                            break;
                        }
                        default:
                            throw new IOException("unknown command " + cmd);
                    }
                    out.flush();
                }
            } catch (IOException e) {
                if (running) {
                    e.printStackTrace();
                }
            }
        }
    }

    @Override
    public void close() throws IOException {
        running = false;
        server.close();
    }
}
