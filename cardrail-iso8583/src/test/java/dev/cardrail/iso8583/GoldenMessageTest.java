package dev.cardrail.iso8583;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wire layouts built by hand, field by field, and compared byte for byte with the encoder.
 * Bitmaps are derived in comments: each hex char covers four bits, MSB first, bit 1 leftmost.
 */
class GoldenMessageTest {

    private final IsoCodec codec = new IsoCodec();

    static final String PAN = "5413330089010434";
    static final String ICC = "9F260811223344556677889F270180";

    static IsoMessage authorizationRequest() {
        return IsoMessage.builder(Mti.AUTHORIZATION_REQUEST)
                .field(2, PAN)
                .field(3, "000000")
                .field(4, "12550")
                .field(7, "1017143005")
                .field(11, "123")
                .field(12, "143005")
                .field(13, "1017")
                .field(14, "2812")
                .field(18, "5411")
                .field(19, "356")
                .field(22, "051")
                .field(32, "123456")
                .field(37, "629014300512")
                .field(41, "TERM0001")
                .field(42, "MERCHANT0000001")
                .field(43, "CARDRAIL STORE BENGALURU IN")
                .field(49, "356")
                .field(55, ICC.toLowerCase())
                .build();
    }

    @Test
    void authorizationRequest0100() throws Exception {
        // Bits 2,3,4,7 | 11,12,13,14 | 18,19,22 | 32 | 37 | 41,42,43 | 49,55
        // byte1 0111 0010 = 72, byte2 0011 1100 = 3C, byte3 0110 0100 = 64, byte4 0000 0001 = 01,
        // byte5 0000 1000 = 08, byte6 1110 0000 = E0, byte7 1000 0010 = 82, byte8 = 00
        String bitmap = "723C640108E08200";
        assertThat(setBits(bitmap)).containsExactly(2, 3, 4, 7, 11, 12, 13, 14, 18, 19, 22, 32, 37, 41, 42, 43, 49, 55);

        String expected = "0100"
                + bitmap
                + "16" + PAN                                  // 2   n..19 LLVAR
                + "000000"                                    // 3   n6
                + "000000012550"                              // 4   n12, left-zero-padded from 12550
                + "1017143005"                                // 7   n10
                + "000123"                                    // 11  n6, left-zero-padded from 123
                + "143005"                                    // 12  n6
                + "1017"                                      // 13  n4
                + "2812"                                      // 14  n4
                + "5411"                                      // 18  n4
                + "356"                                       // 19  n3
                + "051"                                       // 22  n3
                + "06" + "123456"                             // 32  n..11 LLVAR
                + "629014300512"                              // 37  an12
                + "TERM0001"                                  // 41  ans8
                + "MERCHANT0000001"                           // 42  ans15
                + "CARDRAIL STORE BENGALURU IN" + " ".repeat(13) // 43 ans40, right-space-padded
                + "356"                                       // 49  n3
                + "030" + ICC;                                // 55  LLLVAR, length in hex chars, uppercased

        IsoMessage m = authorizationRequest();
        assertThat(ascii(codec.encode(m))).isEqualTo(expected);
        assertThat(codec.decode(expected.getBytes(StandardCharsets.US_ASCII))).isEqualTo(m);
        assertThat(m.amountMinor()).hasValue(12550L);
        assertThat(m.stan()).hasValue("000123");
    }

    @Test
    void authorizationResponse0110EchoesRequestFields() throws Exception {
        IsoMessage response = IsoCodec.responseFor(authorizationRequest(), "00", "A1B2C3");

        // Bits 2,3,4,7 | 11,12,13 | 32 | 37,38,39 | 41,42 | 49. 14,18,19,22,43,55 are not echoed.
        // byte1 72, byte2 0011 1000 = 38, byte3 00, byte4 01, byte5 0000 1110 = 0E, byte6 1100 0000 = C0, byte7 80
        String bitmap = "723800010EC08000";
        assertThat(setBits(bitmap)).containsExactly(2, 3, 4, 7, 11, 12, 13, 32, 37, 38, 39, 41, 42, 49);

        String expected = "0110" + bitmap
                + "16" + PAN + "000000" + "000000012550" + "1017143005" + "000123" + "143005" + "1017"
                + "06123456" + "629014300512"
                + "A1B2C3"                                    // 38 an6
                + "00"                                        // 39 an2
                + "TERM0001" + "MERCHANT0000001" + "356";

        assertThat(response.mti()).isEqualTo(Mti.AUTHORIZATION_RESPONSE);
        assertThat(ascii(codec.encode(response))).isEqualTo(expected);
        assertThat(codec.decode(codec.encode(response))).isEqualTo(response);
    }

    @Test
    void reversal0400WithOriginalDataElements() throws Exception {
        IsoMessage original = authorizationRequest();
        OriginalDataElements ode = OriginalDataElements.of(original);
        IsoMessage reversal = IsoMessage.builder(Mti.REVERSAL_REQUEST)
                .field(2, PAN).field(3, "000000").field(4, "12550").field(7, "1017143105").field(11, "124")
                .field(32, "123456").field(37, "629014300512").field(41, "TERM0001").field(42, "MERCHANT0000001")
                .field(49, "356").field(90, ode.toField())
                .build();

        // Primary: bit 1 (secondary present) + 2,3,4,7 = 1111 0010 = F2; 11 = 20; 32 = 01; 37 = 08; 41,42 = C0; 49 = 80
        String primary = "F220000108C08000";
        // Secondary covers 65-128; field 90 is secondary bit 26: byte 4, second bit = 0100 0000 = 40
        String secondary = "0000004000000000";
        assertThat(setBits(primary)).containsExactly(1, 2, 3, 4, 7, 11, 32, 37, 41, 42, 49);
        assertThat(setBits(secondary)).containsExactly(26);

        String field90 = "0100" + "000123" + "1017143005" + "00000123456" + "00000000000";
        String expected = "0400" + primary + secondary
                + "16" + PAN + "000000" + "000000012550" + "1017143105" + "000124" + "06123456"
                + "629014300512" + "TERM0001" + "MERCHANT0000001" + "356"
                + field90;                                    // 90 n42

        assertThat(ascii(codec.encode(reversal))).isEqualTo(expected);
        IsoMessage decoded = codec.decode(expected.getBytes(StandardCharsets.US_ASCII));
        assertThat(decoded).isEqualTo(reversal);
        assertThat(decoded.originalDataElements()).hasValue(ode);

        IsoMessage response = IsoCodec.responseFor(decoded, "00", null);
        assertThat(response.mti()).isEqualTo(Mti.REVERSAL_RESPONSE);
        assertThat(response.field(90)).hasValue(field90);
        assertThat(response.has(38)).isFalse();
    }

    @Test
    void networkManagement0800AndEcho0810() throws Exception {
        IsoMessage echo = IsoMessage.builder(Mti.NETWORK_MANAGEMENT_REQUEST)
                .field(7, "1017150000").field(11, "000777").field(70, "301").build();

        // Primary: bit 1 + bit 7 = 1000 0010 = 82; bit 11 = 0010 = 2 in the third hex char
        // Secondary: field 70 is secondary bit 6 = 0000 0100 = 04
        String expected = "0800" + "8220000000000000" + "0400000000000000" + "1017150000" + "000777" + "301";
        assertThat(setBits("8220000000000000")).containsExactly(1, 7, 11);
        assertThat(setBits("0400000000000000")).containsExactly(6);
        assertThat(ascii(codec.encode(echo))).isEqualTo(expected);
        assertThat(codec.decode(expected.getBytes(StandardCharsets.US_ASCII))).isEqualTo(echo);

        IsoMessage reply = IsoCodec.responseFor(echo, "00", null);
        // 0810 adds bit 39: byte5 0000 0010 = 02
        String expectedReply = "0810" + "8220000002000000" + "0400000000000000" + "1017150000" + "000777" + "00" + "301";
        assertThat(ascii(codec.encode(reply))).isEqualTo(expectedReply);
    }

    @Test
    void noSecondaryBitmapWhenOnlyPrimaryFieldsPresent() {
        byte[] wire = codec.encode(authorizationRequest());
        assertThat((char) wire[4]).as("first bitmap char, bit 1 clear").isEqualTo('7');
        assertThat(ascii(wire).substring(20, 22)).as("PAN prefix right after a single bitmap").isEqualTo("16");
    }

    static List<Integer> setBits(String hexBitmap) {
        List<Integer> bits = new ArrayList<>();
        for (int i = 0; i < hexBitmap.length(); i++) {
            int nibble = Character.digit(hexBitmap.charAt(i), 16);
            for (int b = 0; b < 4; b++) {
                if ((nibble & (8 >> b)) != 0) {
                    bits.add(i * 4 + b + 1);
                }
            }
        }
        return bits;
    }

    static String ascii(byte[] b) {
        return new String(b, StandardCharsets.US_ASCII);
    }
}
