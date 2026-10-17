package dev.cardrail.vault;

/**
 * Receives every detokenize attempt, successful or not. Called synchronously before the PAN is
 * returned; if the listener throws, the PAN is not released (fail closed).
 */
@FunctionalInterface
public interface VaultAuditListener {

    void onDetokenize(DetokenizeEvent event);
}
