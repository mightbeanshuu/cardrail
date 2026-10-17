package dev.cardrail.iso8583;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.ProtocolException;
import java.util.Objects;
import java.util.Optional;

/**
 * Reads length-prefixed frames: a 2-byte big-endian unsigned length followed by that many bytes.
 * Not thread-safe; a connection has one reader.
 */
public final class FrameReader {

    /** Largest length the 2-byte header can carry. */
    public static final int MAX_HEADER_LENGTH = 0xFFFF;

    private final InputStream in;
    private final int maxFrameLength;

    public FrameReader(InputStream in) {
        this(in, MAX_HEADER_LENGTH);
    }

    /**
     * @param maxFrameLength frames declaring a larger length are rejected before any payload is read,
     *                       so a hostile peer cannot make the reader allocate more than this
     */
    public FrameReader(InputStream in, int maxFrameLength) {
        this.in = Objects.requireNonNull(in, "in");
        if (maxFrameLength < 1 || maxFrameLength > MAX_HEADER_LENGTH) {
            throw new IllegalArgumentException("maxFrameLength must be 1-" + MAX_HEADER_LENGTH);
        }
        this.maxFrameLength = maxFrameLength;
    }

    /**
     * Reads the next frame's payload.
     *
     * @return the payload, or empty if the stream ended cleanly between frames
     * @throws EOFException      if the stream ended inside a header or payload
     * @throws ProtocolException if the frame is empty or longer than the configured maximum; the
     *                           stream position is then undefined and the connection should be closed
     */
    public Optional<byte[]> read() throws IOException {
        int hi = in.read();
        if (hi < 0) {
            return Optional.empty();
        }
        int lo = in.read();
        if (lo < 0) {
            throw new EOFException("stream ended inside frame header");
        }
        int length = hi << 8 | lo;
        if (length == 0) {
            throw new ProtocolException("zero-length frame");
        }
        if (length > maxFrameLength) {
            throw new ProtocolException("frame length " + length + " exceeds max " + maxFrameLength);
        }
        byte[] payload = in.readNBytes(length);
        if (payload.length < length) {
            throw new EOFException("stream ended after " + payload.length + " of " + length + " payload bytes");
        }
        return Optional.of(payload);
    }
}
