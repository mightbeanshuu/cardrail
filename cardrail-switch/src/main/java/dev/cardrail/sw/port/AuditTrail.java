package dev.cardrail.sw.port;

import dev.cardrail.sw.domain.audit.AuditEntry;
import dev.cardrail.sw.domain.audit.ChainVerification;

public interface AuditTrail {

    /**
     * Appends to the hash chain in the caller's transaction. Appends are serialized until commit, so
     * call this last in a transaction to keep that window short.
     */
    void append(AuditEntry entry);

    ChainVerification verify();
}
