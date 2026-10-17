package dev.cardrail.vault;

/** What a caller may keep about a card after tokenization. Contains no full PAN. */
public record TokenizedCard(String token, String maskedPan, String bin, String last4, CardScheme scheme) {

    static TokenizedCard from(VaultRecord record) {
        String masked = record.maskedPan();
        return new TokenizedCard(
                record.token(),
                masked,
                masked.substring(0, PanMasker.VISIBLE_PREFIX),
                masked.substring(masked.length() - PanMasker.VISIBLE_SUFFIX),
                CardScheme.detect(masked));
    }
}
