package dev.cardrail.sw.domain.audit;

import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/** What a caller asks to be audited. Details are flat strings so their canonical form is unambiguous. */
public record AuditEntry(String actor, String action, String entity, String entityId, SortedMap<String, String> details) {

    public AuditEntry {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(entityId, "entityId");
        details = new TreeMap<>(details);
        details.forEach((k, v) -> Objects.requireNonNull(v, () -> "detail " + k + " is null"));
    }

    public static AuditEntry of(String actor, String action, String entity, String entityId, Map<String, String> details) {
        return new AuditEntry(actor, action, entity, entityId, new TreeMap<>(details));
    }
}
