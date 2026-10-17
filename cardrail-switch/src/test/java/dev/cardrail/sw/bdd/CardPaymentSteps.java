package dev.cardrail.sw.bdd;

import dev.cardrail.sw.adapter.issuer.SimulatedIssuer;
import dev.cardrail.sw.adapter.issuer.SimulatedIssuerFaults;
import dev.cardrail.sw.application.AuthorizeService;
import dev.cardrail.sw.application.CaptureService;
import dev.cardrail.sw.application.ReversalResult;
import dev.cardrail.sw.application.ReverseService;
import dev.cardrail.sw.domain.authorization.AuthorizationResult;
import dev.cardrail.sw.domain.authorization.ResponseCode;
import dev.cardrail.sw.support.RequestBuilder;
import dev.cardrail.sw.support.TestFixtures;
import io.cucumber.java.Before;
import io.cucumber.java.ParameterType;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The vocabulary of the feature files. Amounts are written in rupees as a cardholder would read
 * them and converted to paise here.
 */
public class CardPaymentSteps {

    private static final String PAN = "5555555555554444";

    @Autowired TestFixtures fixtures;
    @Autowired AuthorizeService authorize;
    @Autowired ReverseService reverse;
    @Autowired CaptureService capture;
    @Autowired SimulatedIssuer issuer;

    private RequestBuilder lastRequest;
    private AuthorizationResult lastResult;
    private AuthorizationResult firstResult;
    private final List<ReversalResult> reversals = new ArrayList<>();
    private long issuerCallsBefore;

    @ParameterType("₹[0-9,]+\\.[0-9]{2}")
    public long rupees(String text) {
        return new BigDecimal(text.substring(1).replace(",", "")).movePointRight(2).longValueExact();
    }

    @Before
    public void cleanSlate() {
        fixtures.reset();
    }

    @Given("a card with a balance of {rupees}")
    public void aCardWithABalanceOf(long balance) {
        fixtures.openAccount(PAN, balance);
    }

    @Given("the stand-in limit for cards starting {word} is {rupees}")
    public void theStandInLimitIs(String binPrefix, long limit) {
        fixtures.jdbc().sql("""
                        INSERT INTO stand_in_limit (bin_prefix, currency, max_amount_minor) VALUES (:bin, 356, :limit)
                        ON CONFLICT (bin_prefix, currency) DO UPDATE SET max_amount_minor = EXCLUDED.max_amount_minor
                        """)
                .param("bin", binPrefix)
                .param("limit", limit)
                .update();
    }

    @Given("the issuer is not responding")
    public void theIssuerIsNotResponding() {
        fixtures.faults().set(new SimulatedIssuerFaults.Settings(Duration.ofMillis(2_200), 0));
    }

    @Given("the issuer is failing every request")
    public void theIssuerIsFailingEveryRequest() {
        fixtures.faults().set(new SimulatedIssuerFaults.Settings(Duration.ZERO, 1.0));
    }

    @Given("the terminal requested {rupees} at MCC {word} and it was approved")
    public void anApprovedAuthorization(long amount, String mcc) {
        theTerminalRequests(amount, mcc);
        assertThat(lastResult.responseCode()).isEqualTo(ResponseCode.APPROVED);
        firstResult = lastResult;
    }

    @Given("the terminal requested {rupees} at MCC {word} and it was declined with {word}")
    public void aDeclinedAuthorization(long amount, String mcc, String code) {
        theTerminalRequests(amount, mcc);
        assertThat(lastResult.responseCode().code()).isEqualTo(code);
        firstResult = lastResult;
    }

    @When("the terminal requests {rupees} at MCC {word}")
    public void theTerminalRequests(long amount, String mcc) {
        send(RequestBuilder.purchase(PAN, amount).mcc(mcc));
    }

    @When("the terminal requests {rupees} at MCC {word} with a card expiring {word}")
    public void theTerminalRequestsWithExpiry(long amount, String mcc, String yymm) {
        send(RequestBuilder.purchase(PAN, amount).mcc(mcc).expiry(yymm));
    }

    @When("the terminal sends {int} requests of {rupees} at MCC {word}")
    public void theTerminalSendsRequests(int count, long amount, String mcc) {
        for (int i = 0; i < count; i++) {
            theTerminalRequests(amount, mcc);
        }
        issuerCallsBefore = issuer.calls();
    }

    @When("the terminal sends the same request again")
    public void theTerminalSendsTheSameRequestAgain() {
        lastResult = authorize.authorize(lastRequest.build());
    }

    @When("a different request for {rupees} arrives with the same STAN, time and terminal")
    public void aDifferentRequestReusesTheKey(long amount) {
        send(lastRequest.amountMinor(amount));
    }

    @When("the merchant captures {rupees}")
    public void theMerchantCaptures(long amount) {
        capture.capture(firstResult.id(), amount, "bdd");
    }

    @When("the acquirer reverses the authorization")
    @And("the acquirer reverses the authorization again")
    public void theAcquirerReverses() {
        reversals.add(reverse.reverse(firstResult.id(), "bdd"));
    }

    @Then("the response code is {word}")
    public void theResponseCodeIs(String code) {
        assertThat(lastResult.responseCode().code()).isEqualTo(code);
    }

    @Then("{rupees} is held on the card")
    public void isHeld(long amount) {
        assertThat(fixtures.balance(PAN).heldMinor()).isEqualTo(amount);
        assertThat(fixtures.heldLedgerBalance(PAN)).isEqualTo(amount);
        assertThat(fixtures.unbalancedJournals()).isEmpty();
    }

    @Then("nothing is held on the card")
    public void nothingIsHeld() {
        isHeld(0);
    }

    @Then("{rupees} is available on the card")
    public void isAvailable(long amount) {
        assertThat(fixtures.balance(PAN).availableMinor()).isEqualTo(amount);
    }

    @Then("{rupees} is owed to the merchant")
    public void isOwedToTheMerchant(long amount) {
        long payable = fixtures.jdbc().sql("""
                        SELECT COALESCE(sum(e.amount_minor), 0) FROM journal_entry e
                          JOIN ledger_account a ON a.id = e.ledger_account_id WHERE a.kind = 'SETTLEMENT_PAYABLE'
                        """)
                .query(Long.class)
                .single();
        assertThat(payable).isEqualTo(amount);
    }

    @Then("the decision was made in stand-in")
    public void theDecisionWasMadeInStandIn() {
        assertThat(lastResult.standIn()).isTrue();
    }

    @Then("a stand-in advice is queued for the issuer")
    public void aStandInAdviceIsQueued() {
        assertThat(fixtures.outboxEventTypes(lastResult.id())).contains("stip.advice");
    }

    @Then("the switch stops calling the issuer")
    public void theSwitchStopsCallingTheIssuer() {
        assertThat(fixtures.issuerCircuitBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
        theTerminalRequests(10_000, "5411");
        assertThat(issuer.calls()).isEqualTo(issuerCallsBefore);
        assertThat(lastResult.standIn()).isTrue();
    }

    @Then("the response is the same as the first, with the same auth code")
    public void theResponseIsTheSameAsTheFirst() {
        assertThat(lastResult).isEqualTo(firstResult);
        assertThat(lastResult.authCode()).isNotNull().isEqualTo(firstResult.authCode());
    }

    @Then("both reversals report {rupees} released")
    public void bothReversalsReport(long amount) {
        assertThat(reversals).hasSize(2).allSatisfy(r -> assertThat(r.releasedMinor()).isEqualTo(amount));
        assertThat(reversals.get(1)).isEqualTo(reversals.get(0));
    }

    @Then("the reversal reports nothing to reverse")
    public void theReversalReportsNothingToReverse() {
        assertThat(reversals.getLast().outcome()).isEqualTo(ReversalResult.Outcome.NOTHING_TO_REVERSE);
    }

    private void send(RequestBuilder request) {
        lastRequest = request;
        lastResult = authorize.authorize(request.build());
    }
}
