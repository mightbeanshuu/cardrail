package dev.cardrail.sw.support;

import dev.cardrail.sw.domain.authorization.AuthorizationRequest;

import java.util.concurrent.atomic.AtomicInteger;

/** Valid INR grocery purchase by default; each builder gets a fresh STAN and RRN. */
public final class RequestBuilder {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private String pan;
    private String expiry = "3012";
    private long amountMinor;
    private int currency = 356;
    private String mcc = "5411";
    private String merchantId = "MERCHANT0000001";
    private String merchantName = "Fresh Mart Pune";
    private String merchantCountry = "356";
    private String terminalId = "TERM0001";
    private String acquirerId = "40001";
    private String stan;
    private String rrn;
    private String transmissionDateTime = "1017103000";
    private String posEntryMode = "051";

    private RequestBuilder(String pan, long amountMinor) {
        int n = SEQUENCE.incrementAndGet();
        this.pan = pan;
        this.amountMinor = amountMinor;
        this.stan = "%06d".formatted(n % 1_000_000);
        this.rrn = "%012d".formatted(n);
    }

    public static RequestBuilder purchase(String pan, long amountMinor) {
        return new RequestBuilder(pan, amountMinor);
    }

    public RequestBuilder pan(String value) { this.pan = value; return this; }
    public RequestBuilder expiry(String value) { this.expiry = value; return this; }
    public RequestBuilder amountMinor(long value) { this.amountMinor = value; return this; }
    public RequestBuilder currency(int value) { this.currency = value; return this; }
    public RequestBuilder mcc(String value) { this.mcc = value; return this; }
    public RequestBuilder merchantId(String value) { this.merchantId = value; return this; }
    public RequestBuilder terminalId(String value) { this.terminalId = value; return this; }
    public RequestBuilder acquirerId(String value) { this.acquirerId = value; return this; }
    public RequestBuilder stan(String value) { this.stan = value; return this; }
    public RequestBuilder rrn(String value) { this.rrn = value; return this; }
    public RequestBuilder transmissionDateTime(String value) { this.transmissionDateTime = value; return this; }

    public AuthorizationRequest build() {
        return new AuthorizationRequest(pan, expiry, amountMinor, currency, mcc, merchantId, merchantName,
                merchantCountry, terminalId, acquirerId, stan, rrn, transmissionDateTime, posEntryMode);
    }
}
