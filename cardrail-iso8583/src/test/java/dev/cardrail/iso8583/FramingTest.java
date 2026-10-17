package dev.cardrail.iso8583;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.net.ProtocolException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FramingTest {

    @Test
    void backToBackFramesThenCleanEof() throws Exception {
        IsoCodec codec = new IsoCodec();
        IsoMessage auth = GoldenMessageTest.authorizationRequest();
        IsoMessage echo = IsoMessage.builder(Mti.NETWORK_MANAGEMENT_REQUEST).field(7, "1017150000").field(11, "1").field(70, "301").build();
        byte[] big = new byte[300];

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FrameWriter writer = new FrameWriter(out);
        writer.write(codec.encode(auth));
        writer.write(codec.encode(echo));
        writer.write(big);

        byte[] wire = out.toByteArray();
        assertThat(wire[0] << 8 | wire[1] & 0xFF).isEqualTo(codec.encode(auth).length);

        FrameReader reader = new FrameReader(new ByteArrayInputStream(wire));
        assertThat(codec.decode(reader.read().orElseThrow())).isEqualTo(auth);
        assertThat(codec.decode(reader.read().orElseThrow())).isEqualTo(echo);
        // 300 = 0x012C: the high byte matters, so the header must be read big-endian
        assertThat(reader.read()).hasValueSatisfying(p -> assertThat(p).hasSize(300));
        assertThat(reader.read()).isEmpty();
        assertThat(reader.read()).isEmpty();
    }

    @Test
    void emptyStreamIsCleanEof() throws Exception {
        assertThat(new FrameReader(new ByteArrayInputStream(new byte[0])).read()).isEmpty();
    }

    @Test
    void eofInsideHeaderThrows() {
        FrameReader reader = new FrameReader(new ByteArrayInputStream(new byte[] {0x00}));
        assertThatThrownBy(reader::read).isInstanceOf(EOFException.class);
    }

    @Test
    void eofInsidePayloadThrows() {
        byte[] wire = {0x00, 0x0A, '0', '8', '0', '0'};
        FrameReader reader = new FrameReader(new ByteArrayInputStream(wire));
        assertThatThrownBy(reader::read).isInstanceOf(EOFException.class).hasMessageContaining("4 of 10");
    }

    @Test
    void oversizeFrameRejectedBeforeReadingPayload() {
        byte[] wire = {0x10, 0x00};
        FrameReader reader = new FrameReader(new ByteArrayInputStream(wire), 2048);
        assertThatThrownBy(reader::read).isInstanceOf(ProtocolException.class).hasMessageContaining("4096");
    }

    @Test
    void zeroLengthFrameRejected() {
        FrameReader reader = new FrameReader(new ByteArrayInputStream(new byte[] {0, 0}));
        assertThatThrownBy(reader::read).isInstanceOf(ProtocolException.class);
    }

    @Test
    void writerEnforcesLimits() {
        FrameWriter writer = new FrameWriter(new ByteArrayOutputStream(), 16);
        assertThatIllegalArgumentException().isThrownBy(() -> writer.write(new byte[17]));
        assertThatIllegalArgumentException().isThrownBy(() -> writer.write(new byte[0]));
        assertThatIllegalArgumentException().isThrownBy(() -> new FrameWriter(new ByteArrayOutputStream(), 70_000));
    }

    @Test
    void headerIsBigEndian() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new FrameWriter(out).write("x".repeat(0x0102).getBytes(StandardCharsets.US_ASCII));
        byte[] wire = out.toByteArray();
        assertThat(wire[0]).isEqualTo((byte) 0x01);
        assertThat(wire[1]).isEqualTo((byte) 0x02);
    }
}
