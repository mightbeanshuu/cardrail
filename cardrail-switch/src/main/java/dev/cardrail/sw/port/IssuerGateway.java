package dev.cardrail.sw.port;

/**
 * Route to the card issuer. Calls may be slow or fail; the switch bounds them with a time limiter and
 * a circuit breaker and stands in when they do. A declined authorization is a normal return value;
 * only an inability to answer is an exception.
 */
public interface IssuerGateway {

    /** @throws IssuerUnavailableException (or any runtime exception) when the issuer cannot answer */
    IssuerDecision authorize(IssuerRequest request);
}
