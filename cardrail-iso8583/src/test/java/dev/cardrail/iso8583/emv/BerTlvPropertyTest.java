package dev.cardrail.iso8583.emv;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BerTlvPropertyTest {

    @Property(tries = 1000)
    void decodeInvertsEncode(@ForAll("tlvLists") List<Tlv> tlvs) throws TlvParseException {
        assertThat(BerTlv.decode(BerTlv.encode(tlvs))).isEqualTo(tlvs);
    }

    @Property(tries = 3000)
    void arbitraryBytesDecodeOrFailWithTlvParseException(@ForAll byte[] data) {
        try {
            BerTlv.decode(data);
        } catch (TlvParseException e) {
            assertThat(e.offset()).isBetween(0, data.length);
        }
    }

    @Provide
    Arbitrary<List<Tlv>> tlvLists() {
        return tlv(2).list().ofMaxSize(6);
    }

    private Arbitrary<Tlv> tlv(int depth) {
        List<Integer> primitiveTags = Arrays.stream(EmvTag.values()).map(EmvTag::tag).toList();
        Arbitrary<Tlv> primitive = Arbitraries.of(primitiveTags).flatMap(tag ->
                Arbitraries.bytes().array(byte[].class).ofMaxSize(300).map(v -> Tlv.primitive(tag, v)));
        if (depth == 0) {
            return primitive;
        }
        Arbitrary<Tlv> constructed = Arbitraries.of(0x70, 0x77, 0xBF0C).flatMap(tag ->
                Arbitraries.lazy(() -> tlv(depth - 1)).list().ofMaxSize(3).map(children -> Tlv.constructed(tag, children)));
        return Arbitraries.frequencyOf(
                Tuple.of(4, primitive),
                Tuple.of(1, constructed));
    }
}
