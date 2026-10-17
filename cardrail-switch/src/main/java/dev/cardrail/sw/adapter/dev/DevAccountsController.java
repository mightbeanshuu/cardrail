package dev.cardrail.sw.adapter.dev;

import dev.cardrail.sw.port.CardTokenizer;
import dev.cardrail.sw.port.InvalidCardNumberException;
import dev.cardrail.sw.port.IssuerLedger;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Dev profile only: opens funded issuer accounts for a list of test card numbers, so a load test can
 * run against cards the switch knows (see {@code scripts/bench.sh}). Cards that already have an
 * account are left as they are.
 */
@Profile("dev")
@RestController
@RequestMapping("/dev/accounts")
@Tag(name = "dev: issuer accounts")
class DevAccountsController {

    private static final Logger log = LoggerFactory.getLogger(DevAccountsController.class);

    private final CardTokenizer tokenizer;
    private final IssuerLedger ledger;

    DevAccountsController(CardTokenizer tokenizer, IssuerLedger ledger) {
        this.tokenizer = tokenizer;
        this.ledger = ledger;
    }

    record OpenAccounts(@NotEmpty @Size(max = 100_000) List<String> pans,
                        @NotNull @Min(0) Long balanceMinor,
                        @NotNull @Min(1) @Max(999) Integer currency) {

        @Override
        public String toString() {
            return "OpenAccounts[" + pans.size() + " pans, balanceMinor=" + balanceMinor + ", currency=" + currency + "]";
        }
    }

    record Opened(int opened, int existing, int invalid) {
    }

    @PostMapping
    @Operation(summary = "Open funded accounts for test cards (idempotent per card)")
    Opened open(@Valid @RequestBody OpenAccounts body) {
        int opened = 0;
        int existing = 0;
        int invalid = 0;
        for (String pan : body.pans()) {
            String token;
            try {
                token = tokenizer.tokenize(pan).token();
            } catch (InvalidCardNumberException e) {
                invalid++;
                continue;
            }
            if (ledger.balance(token).isPresent()) {
                existing++;
            } else {
                ledger.openAccount(token, body.currency(), body.balanceMinor());
                opened++;
            }
        }
        log.info("dev accounts: {} opened, {} already existed, {} invalid card numbers", opened, existing, invalid);
        return new Opened(opened, existing, invalid);
    }
}
