package dev.cardrail.iso8583.emv;

import dev.cardrail.iso8583.IsoCodec;
import dev.cardrail.iso8583.IsoMessage;
import dev.cardrail.iso8583.Mti;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class BerTlvTest {

    private static final HexFormat HEX = HexFormat.of().withUpperCase();

    /** A contact-chip ARQC as an acquirer would put it in field 55. */
    static final String FIELD_55 =
            "9F2608" + "8E19ED4A7C6F3B21"                 // Application Cryptogram (ARQC)
            + "9F2701" + "80"                             // CID: ARQC
            + "9F1012" + "0110A00003220000000000000000000000FF" // Issuer Application Data (18 bytes)
            + "9F3704" + "5D2A7E19"                       // Unpredictable Number
            + "9F3602" + "0042"                           // ATC
            + "9505" + "0000048000"                       // TVR
            + "9A03" + "261017"                           // Transaction date YYMMDD
            + "9C01" + "00"                               // Transaction type: purchase
            + "9F0206" + "000000012550"                   // Amount authorised, n12 BCD
            + "5F2A02" + "0356"                           // Currency INR
            + "8202" + "1980"                             // AIP
            + "9F1A02" + "0356";                          // Terminal country India

    @Test
    void realisticField55RoundTrips() throws Exception {
        List<Tlv> tlvs = BerTlv.decodeHex(FIELD_55);

        assertThat(tlvs).extracting(Tlv::tag).containsExactly(
                0x9F26, 0x9F27, 0x9F10, 0x9F37, 0x9F36, 0x95, 0x9A, 0x9C, 0x9F02, 0x5F2A, 0x82, 0x9F1A);
        assertThat(primitive(tlvs, EmvTag.APPLICATION_CRYPTOGRAM).valueHex()).isEqualTo("8E19ED4A7C6F3B21");
        assertThat(primitive(tlvs, EmvTag.ISSUER_APPLICATION_DATA).value()).hasSize(18);
        assertThat(primitive(tlvs, EmvTag.TRANSACTION_CURRENCY_CODE).valueHex()).isEqualTo("0356");
        assertThat(BerTlv.encodeHex(tlvs)).isEqualTo(FIELD_55);
    }

    @Test
    void field55TravelsInsideIsoMessage() throws Exception {
        IsoCodec codec = new IsoCodec();
        List<Tlv> tlvs = BerTlv.decodeHex(FIELD_55);
        IsoMessage m = IsoMessage.builder(Mti.AUTHORIZATION_REQUEST).field(55, BerTlv.encodeHex(tlvs)).build();
        IsoMessage decoded = codec.decode(codec.encode(m));
        assertThat(BerTlv.decodeHex(decoded.iccData().orElseThrow())).isEqualTo(tlvs);
    }

    @Test
    void longFormLengths() throws Exception {
        byte[] v200 = filled(200);
        byte[] v300 = filled(300);
        byte[] enc = BerTlv.encode(List.of(Tlv.primitive(0x9F10, v200), Tlv.primitive(0xDF01, v300)));

        assertThat(HEX.formatHex(enc, 0, 4)).isEqualTo("9F1081C8");               // 0x81 + one length byte
        assertThat(HEX.formatHex(enc, 4 + 200, 4 + 200 + 5)).isEqualTo("DF0182012C"); // 0x82 + two length bytes
        assertThat(BerTlv.decode(enc)).containsExactly(Tlv.primitive(0x9F10, v200), Tlv.primitive(0xDF01, v300));

        // 127 is the largest short-form length
        assertThat(HEX.formatHex(BerTlv.encode(List.of(Tlv.primitive(0x84, filled(127)))), 0, 2)).isEqualTo("847F");
        assertThat(HEX.formatHex(BerTlv.encode(List.of(Tlv.primitive(0x84, filled(128)))), 0, 3)).isEqualTo("848180");
    }

    @Test
    void constructedTagsParseRecursively() throws Exception {
        // 77 (constructed: bit 6 of 0x77 set) holding 9F27 and a nested 70 template with 5A and 95.
        // Inner: 5A08+8 bytes (10) + 950100 (3) = 0x0D. Outer: 9F270180 (4) + 700D (2) + 13 = 0x13.
        String hex = "7713" + "9F270180" + "700D" + "5A08" + "5413330089010434" + "9501" + "00";
        assertThat(hex.length() / 2).isEqualTo(2 + 0x13);

        List<Tlv> tlvs = BerTlv.decodeHex(hex);
        assertThat(tlvs).hasSize(1);
        Tlv.Constructed outer = (Tlv.Constructed) tlvs.getFirst();
        assertThat(outer.tag()).isEqualTo(0x77);
        assertThat(outer.children()).extracting(Tlv::tag).containsExactly(0x9F27, 0x70);
        assertThat(BerTlv.find(tlvs, 0x5A)).hasValueSatisfying(
                t -> assertThat(((Tlv.Primitive) t).valueHex()).isEqualTo("5413330089010434"));
        assertThat(BerTlv.encodeHex(tlvs)).isEqualTo(hex);
    }

    @Test
    void sensitiveTagValuesAreNotPrinted() {
        Tlv pan = Tlv.primitive(0x5A, HexFormat.of().parseHex("5413330089010434"));
        assertThat(pan.toString()).isEqualTo("5A=<8 bytes>");
        assertThat(Tlv.primitive(0x9F27, new byte[] {(byte) 0x80}).toString()).isEqualTo("9F27=80");
    }

    @Test
    void lengthBeyondBufferIsRejected() {
        TlvParseException e = parseError("9F2608" + "1122334455");
        assertThat(e.offset()).isZero();
        assertThat(e.getMessage()).contains("exceeds remaining 5");
    }

    @Test
    void childLengthBeyondParentIsRejected() {
        // Parent 77 claims 4 bytes; child 9F27 claims 2 bytes of value but only 1 remains inside the parent.
        TlvParseException e = parseError("7704" + "9F2702" + "80" + "AABB");
        assertThat(e.offset()).isEqualTo(2);
    }

    @Test
    void otherMalformedInputsAreRejected() {
        assertThat(parseError("9F").getMessage()).contains("tag truncated");
        assertThat(parseError("9F26").getMessage()).contains("missing length");
        assertThat(parseError("9F2681").getMessage()).contains("long-form length truncated");
        assertThat(parseError("9F2680").getMessage()).contains("indefinite");
        assertThat(parseError("9F2683000001").getMessage()).contains("unsupported length form");
        assertThat(parseError("DF8181818101").getMessage()).contains("tag longer");
    }

    @Test
    void deepNestingIsRejected() {
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            hex.append("70").append(String.format("%02X", 2 * (19 - i)));
        }
        assertThat(parseError(hex.toString()).getMessage()).contains("nesting");
    }

    @Test
    void malformedTagsAreRejectedOnConstruction() {
        assertThatIllegalArgumentException().isThrownBy(() -> Tlv.primitive(0x9F, new byte[0]));
        assertThatIllegalArgumentException().isThrownBy(() -> Tlv.primitive(0x9A26, new byte[0]));
        assertThatIllegalArgumentException().isThrownBy(() -> Tlv.primitive(0x9F81, new byte[0]));
        assertThatIllegalArgumentException().isThrownBy(() -> Tlv.primitive(0x70, new byte[0]));
        assertThatIllegalArgumentException().isThrownBy(() -> Tlv.constructed(0x9F26, List.of()));
    }

    @Test
    void registryNamesCommonTags() {
        assertThat(EmvTag.of(0x9F26)).hasValue(EmvTag.APPLICATION_CRYPTOGRAM);
        assertThat(EmvTag.of(0x9F37).orElseThrow().description()).isEqualTo("Unpredictable Number");
        assertThat(EmvTag.of(0xDF01)).isEmpty();
    }

    private static Tlv.Primitive primitive(List<Tlv> tlvs, EmvTag tag) {
        return (Tlv.Primitive) BerTlv.find(tlvs, tag.tag()).orElseThrow();
    }

    private static TlvParseException parseError(String hex) {
        TlvParseException e = catchThrowableOfType(TlvParseException.class, () -> BerTlv.decodeHex(hex));
        assertThat(e).as("expected TlvParseException for %s", hex).isNotNull();
        return e;
    }

    private static byte[] filled(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) i;
        }
        return b;
    }
}
