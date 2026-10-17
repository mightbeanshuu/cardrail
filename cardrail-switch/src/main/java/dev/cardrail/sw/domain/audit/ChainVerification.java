package dev.cardrail.sw.domain.audit;

public sealed interface ChainVerification {

    record Intact(long recordsVerified) implements ChainVerification {
    }

    record Broken(long firstBrokenSeq, String reason, long recordsVerified) implements ChainVerification {
    }
}
