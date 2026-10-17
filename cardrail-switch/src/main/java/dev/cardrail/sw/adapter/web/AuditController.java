package dev.cardrail.sw.adapter.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.cardrail.sw.domain.audit.ChainVerification;
import dev.cardrail.sw.port.AuditTrail;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/audit")
@Tag(name = "audit")
class AuditController {

    private final AuditTrail audit;

    AuditController(AuditTrail audit) {
        this.audit = audit;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record VerificationBody(boolean intact, long recordsVerified, Long firstBrokenSeq, String reason) {
    }

    @GetMapping("/verify")
    @Operation(summary = "Recompute the audit hash chain and report the first broken record, if any")
    VerificationBody verify() {
        return switch (audit.verify()) {
            case ChainVerification.Intact intact -> new VerificationBody(true, intact.recordsVerified(), null, null);
            case ChainVerification.Broken broken -> new VerificationBody(false, broken.recordsVerified(),
                    broken.firstBrokenSeq(), broken.reason());
        };
    }
}
