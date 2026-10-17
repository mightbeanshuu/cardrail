package dev.cardrail.sw.domain.authorization;

/**
 * What to do with a request whose transmission key is already taken.
 */
public sealed interface DuplicateDecision {

    /** Same transmission sent again: answer with the original response. */
    record Replay(AuthorizationResult original) implements DuplicateDecision {
    }

    /** Same key, different content: a different message reusing an identifier. Respond 94. */
    record Mismatch() implements DuplicateDecision {
    }

    /** The original is still being decided; there is no response to replay yet. */
    record InFlight() implements DuplicateDecision {
    }

    static DuplicateDecision of(StoredAuthorization existing, String incomingPayloadHash) {
        if (!existing.payloadHash().equals(incomingPayloadHash)) {
            return new Mismatch();
        }
        if (existing.status() == AuthorizationStatus.PENDING) {
            return new InFlight();
        }
        return new Replay(existing.toResult());
    }
}
