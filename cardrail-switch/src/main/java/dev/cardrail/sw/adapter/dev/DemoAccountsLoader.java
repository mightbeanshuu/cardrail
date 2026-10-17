package dev.cardrail.sw.adapter.dev;

import dev.cardrail.sw.port.AccountStatus;
import dev.cardrail.sw.port.CardTokenizer;
import dev.cardrail.sw.port.IssuerLedger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

/** Dev profile only: opens issuer accounts for well-known test card numbers so the curl examples work. */
@Profile("dev")
@Component
class DemoAccountsLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoAccountsLoader.class);
    private static final int INR = 356;

    private record DemoCard(String pan, long balanceMinor, AccountStatus status) {
    }

    private static final List<DemoCard> CARDS = List.of(
            new DemoCard("5555555555554444", 5_000_000, AccountStatus.ACTIVE),   // INR 50,000.00
            new DemoCard("5105105105105100", 50_000, AccountStatus.ACTIVE),      // INR 500.00
            new DemoCard("2223003122003222", 1_000_000, AccountStatus.BLOCKED)); // INR 10,000.00, blocked

    private final CardTokenizer tokenizer;
    private final IssuerLedger ledger;

    DemoAccountsLoader(CardTokenizer tokenizer, IssuerLedger ledger) {
        this.tokenizer = tokenizer;
        this.ledger = ledger;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (DemoCard card : CARDS) {
            String token = tokenizer.tokenize(card.pan()).token();
            if (ledger.balance(token).isPresent()) {
                continue;
            }
            ledger.openAccount(token, INR, card.balanceMinor());
            if (card.status() != AccountStatus.ACTIVE) {
                ledger.setStatus(token, card.status());
            }
            log.info("opened demo account {} with {} minor units ({})", token, card.balanceMinor(), card.status());
        }
    }
}
