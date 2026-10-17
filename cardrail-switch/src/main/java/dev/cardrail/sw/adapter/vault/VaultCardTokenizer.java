package dev.cardrail.sw.adapter.vault;

import dev.cardrail.sw.port.CardTokenizer;
import dev.cardrail.sw.port.InvalidCardNumberException;
import dev.cardrail.sw.port.TokenizedCard;
import dev.cardrail.vault.Pan;
import dev.cardrail.vault.TokenVault;

/**
 * {@link CardTokenizer} backed by {@link TokenVault}: the PAN is encrypted into the vault and the
 * switch keeps only the vault's 16-digit token and the masked PAN. The blind index makes the token
 * the same every time the PAN is seen, which the issuer account, velocity counters and Kafka keys
 * rely on.
 */
public final class VaultCardTokenizer implements CardTokenizer {

    private final TokenVault vault;

    public VaultCardTokenizer(TokenVault vault) {
        this.vault = vault;
    }

    @Override
    public TokenizedCard tokenize(String pan) {
        Pan parsed;
        try {
            parsed = Pan.of(pan);
        } catch (dev.cardrail.vault.InvalidCardNumberException e) {
            throw new InvalidCardNumberException(e.getMessage());
        }
        try {
            dev.cardrail.vault.TokenizedCard card = vault.tokenize(parsed);
            return new TokenizedCard(card.token(), card.maskedPan(), card.bin(), card.last4());
        } catch (dev.cardrail.vault.InvalidCardNumberException e) {
            // A number in the token BIN is a token presented as a card.
            throw new InvalidCardNumberException(e.getMessage());
        } finally {
            parsed.destroy();
        }
    }
}
