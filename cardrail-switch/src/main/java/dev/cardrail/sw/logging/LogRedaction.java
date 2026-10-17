package dev.cardrail.sw.logging;

import dev.cardrail.vault.PanRedactor;
import dev.cardrail.vault.TokenFormat;

/**
 * The one redactor every log path uses: masks each Luhn-valid 13-19 digit run (also when grouped by
 * spaces or dashes) except vault tokens, which stay readable so log lines can be correlated by card.
 *
 * <p>Logback builds the converters before Spring starts, so this uses the default token BIN. With a
 * different {@code cardrail.vault.token-bin} tokens are masked in logs too, which fails safe.
 */
final class LogRedaction {

    private static final PanRedactor REDACTOR = new PanRedactor(new TokenFormat());

    private LogRedaction() {
    }

    static String redact(String text) {
        return text == null ? null : REDACTOR.redact(text);
    }
}
