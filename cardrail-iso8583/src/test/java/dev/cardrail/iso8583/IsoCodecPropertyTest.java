package dev.cardrail.iso8583;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.statistics.Statistics;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IsoCodecPropertyTest {

    private final IsoCodec codec = new IsoCodec();

    @Property(tries = 2000)
    void decodeInvertsEncode(@ForAll("messages") IsoMessage m) throws IsoParseException {
        byte[] wire = codec.encode(m);
        IsoMessage decoded = codec.decode(wire);
        assertThat(decoded).isEqualTo(m);
        assertThat(codec.encode(decoded)).isEqualTo(wire);
    }

    @Property(tries = 3000)
    void arbitraryBytesEitherDecodeOrFailWithParseException(@ForAll byte[] data) {
        assertDecodesOrFailsCleanly(data);
    }

    @Property(tries = 3000)
    void plausibleHeadersWithGarbageBodies(@ForAll("plausible") byte[] data) {
        assertDecodesOrFailsCleanly(data);
    }

    @Property(tries = 3000)
    void mutatedValidMessagesEitherDecodeOrFailWithParseException(
            @ForAll("messages") IsoMessage m, @ForAll("mutations") List<int[]> mutations) {
        byte[] data = codec.encode(m);
        for (int[] mut : mutations) {
            data = apply(data, mut);
        }
        assertDecodesOrFailsCleanly(data);
    }

    @Property(tries = 1000)
    void toStringNeverContainsFullPan(@ForAll("messages") IsoMessage base, @ForAll("pans") String pan) {
        IsoMessage m = base.withField(2, pan);
        Assume.that(!m.mti().value().contains(pan));
        Assume.that(m.fields().entrySet().stream().filter(e -> e.getKey() != 2).noneMatch(e -> e.getValue().contains(pan)));

        String s = m.toString();
        assertThat(s).doesNotContain(pan);
        if (pan.length() < 13) {
            assertThat(s).contains("2=" + "*".repeat(pan.length()));
        } else {
            assertThat(s).contains("2=" + pan.substring(0, 6) + "*".repeat(pan.length() - 10) + pan.substring(pan.length() - 4));
        }
    }

    private void assertDecodesOrFailsCleanly(byte[] data) {
        try {
            codec.decode(data);
            Statistics.collect("decoded");
        } catch (IsoParseException e) {
            Statistics.collect(e.field() == IsoParseException.NO_FIELD ? "rejected: header/trailer" : "rejected: in field");
            assertThat(e.offset()).isBetween(0, data.length);
            assertThat(e.field()).satisfiesAnyOf(
                    f -> assertThat(f).isEqualTo(IsoParseException.NO_FIELD),
                    f -> assertThat(f).isBetween(2, 128));
        }
    }

    @Provide
    Arbitrary<IsoMessage> messages() {
        return Generators.messages(MessageSpec.defaultSpec());
    }

    /** ISO/IEC 7812 PAN lengths, 8-19 digits. Shorter strings would collide with field labels like "2=". */
    @Provide
    Arbitrary<String> pans() {
        Arbitrary<String> prefix = Arbitraries.of("2", "3", "4", "5", "6");
        Arbitrary<String> rest = Arbitraries.strings().numeric().ofMinLength(7).ofMaxLength(18);
        return Combinators.combine(prefix, rest).as(String::concat);
    }

    /** A valid MTI and a hex bitmap, so the fuzzer gets past the header and into field parsing. */
    @Provide
    Arbitrary<byte[]> plausible() {
        Arbitrary<String> mti = Arbitraries.of("0100", "0110", "0400", "0800");
        Arbitrary<String> bitmaps = Arbitraries.strings().withChars("0123456789ABCDEF").ofLength(16)
                .list().ofMinSize(1).ofMaxSize(2).map(l -> String.join("", l));
        Arbitrary<String> body = Arbitraries.strings().withCharRange(' ', '~').ofMaxLength(400);
        return Combinators.combine(mti, bitmaps, body).as((a, b, c) -> (a + b + c).getBytes(StandardCharsets.US_ASCII));
    }

    /** Each mutation: {kind, position, byte}. Kinds: 0 overwrite, 1 insert, 2 delete, 3 truncate. */
    @Provide
    Arbitrary<List<int[]>> mutations() {
        Arbitrary<int[]> one = Combinators.combine(
                Arbitraries.integers().between(0, 3),
                Arbitraries.integers().between(0, Integer.MAX_VALUE),
                Arbitraries.integers().between(0, 255)).as((k, p, b) -> new int[] {k, p, b});
        return one.list().ofMinSize(1).ofMaxSize(4);
    }

    private static byte[] apply(byte[] data, int[] mut) {
        if (data.length == 0) {
            return mut[0] == 1 ? new byte[] {(byte) mut[2]} : data;
        }
        int pos = mut[1] % data.length;
        return switch (mut[0]) {
            case 0 -> {
                byte[] d = data.clone();
                d[pos] = (byte) mut[2];
                yield d;
            }
            case 1 -> {
                byte[] d = new byte[data.length + 1];
                System.arraycopy(data, 0, d, 0, pos);
                d[pos] = (byte) mut[2];
                System.arraycopy(data, pos, d, pos + 1, data.length - pos);
                yield d;
            }
            case 2 -> {
                byte[] d = new byte[data.length - 1];
                System.arraycopy(data, 0, d, 0, pos);
                System.arraycopy(data, pos + 1, d, pos, data.length - pos - 1);
                yield d;
            }
            default -> Arrays.copyOf(data, pos);
        };
    }
}
