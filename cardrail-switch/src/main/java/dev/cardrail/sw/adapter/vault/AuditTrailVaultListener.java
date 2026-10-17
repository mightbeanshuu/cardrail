package dev.cardrail.sw.adapter.vault;

import dev.cardrail.sw.domain.audit.AuditEntry;
import dev.cardrail.sw.port.AuditTrail;
import dev.cardrail.vault.DetokenizeEvent;
import dev.cardrail.vault.VaultAuditListener;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * Writes every detokenize attempt to the switch's hash-chained audit log before the vault releases
 * the PAN. The vault calls this synchronously and fails closed, so if the audit write fails the
 * caller gets the exception instead of the card number.
 *
 * <p>The entry commits in its own transaction: a PAN that was handed out stays on record even if
 * the caller's transaction later rolls back. The consequence: a caller must not detokenize after
 * appending to the audit trail in its own transaction, because this entry would then wait forever
 * for the chain lock that the caller's still-open transaction holds.
 */
final class AuditTrailVaultListener implements VaultAuditListener {

    static final String ACTOR = "vault";
    static final String ACTION = "PAN_DETOKENIZE";
    static final String ENTITY = "card_token";
    /** Entity id when the input was not a well-formed token: it may have been a PAN, so it is not recorded. */
    static final String MALFORMED = "malformed";

    private final AuditTrail audit;
    private final TransactionTemplate transaction;

    AuditTrailVaultListener(AuditTrail audit, PlatformTransactionManager transactions) {
        this.audit = audit;
        this.transaction = new TransactionTemplate(transactions);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public void onDetokenize(DetokenizeEvent event) {
        Map<String, String> details = new HashMap<>();
        details.put("reason", event.reason().name());
        details.put("outcome", event.outcome().name());
        if (event.kekId() != null) {
            details.put("kekId", event.kekId());
        }
        AuditEntry entry = AuditEntry.of(ACTOR, ACTION, ENTITY, event.token() == null ? MALFORMED : event.token(),
                details);
        transaction.executeWithoutResult(status -> audit.append(entry));
    }
}
