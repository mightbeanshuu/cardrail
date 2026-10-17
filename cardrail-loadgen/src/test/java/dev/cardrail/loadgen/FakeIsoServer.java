package dev.cardrail.loadgen;

import dev.cardrail.iso8583.FrameReader;
import dev.cardrail.iso8583.FrameWriter;
import dev.cardrail.iso8583.IsoCodec;
import dev.cardrail.iso8583.IsoMessage;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Minimal ISO 8583 server for tests, built from the codec and framing only. In pipelined mode every
 * request is answered from its own virtual thread; otherwise requests are answered one at a time
 * in arrival order on the connection's reader thread.
 */
final class FakeIsoServer implements AutoCloseable {

    @FunctionalInterface
    interface Behaviour {
        /** @return the response, or empty to never answer */
        Optional<IsoMessage> respond(IsoMessage request) throws Exception;
    }

    private static final IsoCodec CODEC = new IsoCodec();

    private final ServerSocket server;
    private final Behaviour behaviour;
    private final boolean pipelined;
    private final List<Socket> sockets = new CopyOnWriteArrayList<>();
    final List<IsoMessage> received = new CopyOnWriteArrayList<>();

    FakeIsoServer(Behaviour behaviour, boolean pipelined) throws IOException {
        this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        this.behaviour = behaviour;
        this.pipelined = pipelined;
        Thread.ofVirtual().start(this::acceptLoop);
    }

    static FakeIsoServer approving() throws IOException {
        return new FakeIsoServer(req -> Optional.of(IsoCodec.responseFor(req, "00", "123456")), true);
    }

    int port() {
        return server.getLocalPort();
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket s = server.accept();
                s.setTcpNoDelay(true);
                sockets.add(s);
                Thread.ofVirtual().start(() -> serve(s));
            } catch (IOException e) {
                return;
            }
        }
    }

    private void serve(Socket socket) {
        try {
            FrameReader reader = new FrameReader(new BufferedInputStream(socket.getInputStream()));
            FrameWriter writer = new FrameWriter(socket.getOutputStream());
            while (true) {
                Optional<byte[]> frame = reader.read();
                if (frame.isEmpty()) {
                    return;
                }
                IsoMessage request = CODEC.decode(frame.get());
                received.add(request);
                if (pipelined) {
                    Thread.ofVirtual().start(() -> answer(request, writer));
                } else {
                    answer(request, writer);
                }
            }
        } catch (Exception e) {
            closeQuietly(socket);
        }
    }

    private void answer(IsoMessage request, FrameWriter writer) {
        try {
            Optional<IsoMessage> response = behaviour.respond(request);
            if (response.isPresent()) {
                writer.write(CODEC.encode(response.get()));
            }
        } catch (Exception e) {
            // The client sees no answer, which is what the tests using this server expect from a failure.
        }
    }

    /** Closes every accepted connection but keeps listening. */
    void dropConnections() {
        sockets.forEach(FakeIsoServer::closeQuietly);
    }

    @Override
    public void close() {
        closeQuietly(server);
        dropConnections();
    }

    private static void closeQuietly(AutoCloseable c) {
        try {
            c.close();
        } catch (Exception ignored) {
            // test teardown
        }
    }
}
