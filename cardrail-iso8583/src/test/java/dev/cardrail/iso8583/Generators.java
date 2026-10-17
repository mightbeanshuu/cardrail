package dev.cardrail.iso8583;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;

import java.util.HexFormat;
import java.util.List;

final class Generators {

    private Generators() {
    }

    /** Any value the spec accepts, including short fixed values that the builder must pad. */
    static Arbitrary<String> valueFor(FieldSpec fs) {
        int min = 0;
        int max = fs.length();
        return switch (fs.type()) {
            case N -> Arbitraries.strings().numeric().ofMinLength(min).ofMaxLength(max);
            case AN -> Arbitraries.strings().alpha().numeric().withChars(' ').ofMinLength(min).ofMaxLength(max);
            case ANS -> Arbitraries.strings().withCharRange(' ', '~').ofMinLength(min).ofMaxLength(max);
            case B -> fs.isFixed()
                    ? Arbitraries.bytes().array(byte[].class).ofSize(max / 2).map(HexFormat.of()::formatHex)
                    : Arbitraries.bytes().array(byte[].class).ofMaxSize(max / 2).map(HexFormat.of()::formatHex);
        };
    }

    static Arbitrary<Mti> mtis() {
        return Arbitraries.strings().numeric().ofLength(4).map(Mti::new);
    }

    /** Messages with an arbitrary MTI and an arbitrary subset of the spec's fields. */
    static Arbitrary<IsoMessage> messages(MessageSpec spec) {
        List<FieldSpec> specs = List.copyOf(spec.fields());
        List<Arbitrary<String>> perField = specs.stream().map(fs -> valueFor(fs).injectNull(0.5)).toList();
        Arbitrary<List<String>> values = Combinators.combine(perField).as(vs -> vs);
        return Combinators.combine(mtis(), values).as((mti, vs) -> {
            IsoMessage.Builder b = IsoMessage.builder(spec, mti);
            for (int i = 0; i < specs.size(); i++) {
                if (vs.get(i) != null) {
                    b.field(specs.get(i).number(), vs.get(i));
                }
            }
            return b.build();
        });
    }
}
