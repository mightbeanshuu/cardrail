package dev.cardrail.sw.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Card numbers the switch processes at all. Empty, the default, allows every number. The public demo
 * lists the published test card numbers, so a real card number typed into it is answered 14 before it
 * is tokenized, and nothing about it is stored.
 *
 * @param allowedPrefixes leading digits a PAN must start with; a full card number is the narrowest prefix
 */
@ConfigurationProperties("cardrail.cards")
public record CardAllowlist(List<String> allowedPrefixes) {

    private static final Pattern PREFIX = Pattern.compile("\\d{1,19}");

    public CardAllowlist {
        allowedPrefixes = allowedPrefixes == null ? List.of() : List.copyOf(allowedPrefixes);
        for (String prefix : allowedPrefixes) {
            if (!PREFIX.matcher(prefix).matches()) {
                throw new IllegalArgumentException("cardrail.cards.allowed-prefixes must be 1 to 19 digits each");
            }
        }
    }

    public boolean permits(String pan) {
        return allowedPrefixes.isEmpty() || allowedPrefixes.stream().anyMatch(pan::startsWith);
    }
}
