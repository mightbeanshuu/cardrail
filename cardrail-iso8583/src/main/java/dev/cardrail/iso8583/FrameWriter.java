package dev.cardrail.iso8583;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Writes length-prefixed frames (2-byte big-endian unsigned length, then payload).
 * Safe for concurrent use: each frame is written atomically with respect to other writers.
 */
public final class FrameWriter {

    private final OutputStream out;
    private final int maxFrameLength;
    // A lock rather than synchronized: on Java 21 a virtual thread blocking on socket I/O inside a
    // synchronized block pins its carrier thread.
    private final ReentrantLock lock = new ReentrantLock();

    public FrameWriter(OutputStream out) {
        this(out, FrameReader.MAX_HEADER_LENGTH);
    }

    public FrameWriter(OutputStream out, int maxFrameLength) {
        this.out = Objects.requireNonNull(out, "out");
        if (maxFrameLength < 1 || maxFrameLength > FrameReader.MAX_HEADER_LENGTH) {
            throw new IllegalArgumentException("maxFrameLength must be 1-" + FrameReader.MAX_HEADER_LENGTH);
        }
        this.maxFrameLength = maxFrameLength;
    }

    /**
     * Writes one frame and flushes.
     *
     * @throws IllegalArgumentException if the payload is empty or longer than the configured maximum
     */
    public void write(byte[] payload) throws IOException {
        if (payload.length == 0 || payload.length > maxFrameLength) {
            throw new IllegalArgumentException(
                    "payload length " + payload.length + " outside 1-" + maxFrameLength);
        }
        // One write for header and payload, so the 2-byte header is never sent as its own tiny segment
        // and stalled behind Nagle's algorithm waiting for a delayed ACK.
        byte[] frame = new byte[payload.length + 2];
        frame[0] = (byte) (payload.length >>> 8);
        frame[1] = (byte) payload.length;
        System.arraycopy(payload, 0, frame, 2, payload.length);
        lock.lock();
        try {
            out.write(frame);
            out.flush();
        } finally {
            lock.unlock();
        }
    }
}
