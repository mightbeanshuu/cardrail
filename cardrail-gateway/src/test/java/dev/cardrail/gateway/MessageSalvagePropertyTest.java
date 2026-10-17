package dev.cardrail.gateway;

import dev.cardrail.iso8583.IsoCodec;
import dev.cardrail.iso8583.IsoMessage;
import dev.cardrail.iso8583.MessageSpec;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MessageSalvagePropertyTest {

    private static final IsoCodec CODEC = new IsoCodec();
    private static final MessageSpec SPEC = MessageSpec.defaultSpec();

    @Property
    void arbitraryBytesNeverThrow(@ForAll @Size(max = 300) byte[] data) {
        MessageSalvage.salvage(data, SPEC);
    }

    @Property
    void validMessagesAreRecoveredCompletely(@ForAll("authorizations") IsoMessage message) {
        assertThat(MessageSalvage.salvage(CODEC.encode(message), SPEC)).contains(message);
    }

    @Property
    void damageAfterTheStanStillRecoversIt(@ForAll("authorizations") IsoMessage message,
                                           @ForAll @IntRange(min = 0, max = 200) int position,
                                           @ForAll byte replacement) {
        byte[] bytes = CODEC.encode(message);
        // MTI, bitmap, fields 2 (LL + 16), 3, 4 and 7 precede the STAN. Fields 12-49 follow it, so damage
        // anywhere after it must leave field 11 readable.
        int stanEnd = 4 + 16 + 18 + 6 + 12 + 10 + 6;
        int at = stanEnd + position % (bytes.length - stanEnd);
        byte[] damaged = Arrays.copyOf(bytes, bytes.length);
        damaged[at] = replacement;

        Optional<IsoMessage> salvaged = MessageSalvage.salvage(damaged, SPEC);

        assertThat(salvaged).isPresent();
        assertThat(salvaged.get().stan()).isEqualTo(message.stan());
        assertThat(salvaged.get().mti()).isEqualTo(message.mti());
    }

    @Provide
    Arbitrary<IsoMessage> authorizations() {
        return Combinators.combine(
                Arbitraries.integers().between(1, 999_999),
                Arbitraries.longs().between(1, 999_999_999L),
                Arbitraries.strings().withCharRange('0', '9').ofLength(12)
        ).as((stan, amount, rrn) -> Messages.authorization(stan, amount).withField(37, rrn));
    }
}
