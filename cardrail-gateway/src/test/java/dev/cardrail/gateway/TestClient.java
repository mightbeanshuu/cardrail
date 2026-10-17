package dev.cardrail.gateway;

import dev.cardrail.iso8583.FrameReader;
import dev.cardrail.iso8583.FrameWriter;
import dev.cardrail.iso8583.IsoCodec;
import dev.cardrail.iso8583.IsoMessage;
import dev.cardrail.iso8583.IsoParseException;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Optional;

/** Blocking acquirer-side test connection: raw frames, no correlation. */
final class TestClient implements AutoCloseable {

    private static final IsoCodec CODEC = new IsoCodec();

    private final Socket socket;
    private final FrameReader reader;
    private final FrameWriter writer;

    TestClient(int port) throws IOException {
        socket = new Socket(InetAddress.getLoopbackAddress(), port);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(10_000);
        reader = new FrameReader(new BufferedInputStream(socket.getInputStream()));
        writer = new FrameWriter(socket.getOutputStream());
    }

    void send(IsoMessage message) throws IOException {
        writer.write(CODEC.encode(message));
    }

    void sendRaw(byte[] payload) throws IOException {
        writer.write(payload);
    }

    void sendRawFrame(byte[] frameIncludingHeader) throws IOException {
        socket.getOutputStream().write(frameIncludingHeader);
        socket.getOutputStream().flush();
    }

    IsoMessage receive() throws IOException {
        byte[] frame = reader.read().orElseThrow(() -> new IOException("connection closed by gateway"));
        try {
            return CODEC.decode(frame);
        } catch (IsoParseException e) {
            throw new UncheckedIOException(new IOException("gateway sent an undecodable frame", e));
        }
    }

    /** True if the gateway closed the connection within {@code within}; false if it is still open. */
    boolean closedByPeer(Duration within) throws IOException {
        socket.setSoTimeout((int) within.toMillis());
        try {
            Optional<byte[]> frame = reader.read();
            if (frame.isPresent()) {
                throw new AssertionError("expected the connection to close, got a " + frame.get().length + "-byte frame");
            }
            return true;
        } catch (SocketTimeoutException e) {
            return false;
        } catch (SocketException e) {
            return true;
        }
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
