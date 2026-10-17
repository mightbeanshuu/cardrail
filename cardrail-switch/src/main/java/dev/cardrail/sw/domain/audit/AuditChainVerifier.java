package dev.cardrail.sw.domain.audit;

import java.util.Optional;

/**
 * Walks audit records in sequence order and reports the first one that breaks the chain: either its
 * content no longer matches its hash, or it does not link to the record before it (a row was deleted,
 * reordered or rewritten together with its hash).
 */
public final class AuditChainVerifier {

    private String expectedPrevHash = AuditHasher.GENESIS_HASH;
    private long verified;

    public Optional<ChainVerification.Broken> accept(AuditRecord record) {
        if (!record.prevHash().equals(expectedPrevHash)) {
            return Optional.of(new ChainVerification.Broken(record.seq(), "prev_hash does not match the preceding record", verified));
        }
        if (!record.recomputeHash().equals(record.hash())) {
            return Optional.of(new ChainVerification.Broken(record.seq(), "record content does not match its hash", verified));
        }
        expectedPrevHash = record.hash();
        verified++;
        return Optional.empty();
    }

    public ChainVerification.Intact intact() {
        return new ChainVerification.Intact(verified);
    }
}
