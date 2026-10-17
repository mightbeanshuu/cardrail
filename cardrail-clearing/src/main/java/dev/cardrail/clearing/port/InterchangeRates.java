package dev.cardrail.clearing.port;

import dev.cardrail.clearing.domain.InterchangeRate;

public interface InterchangeRates {

    /** The rate of the MCC's category, or of the DEFAULT category when the MCC has none. */
    InterchangeRate rateFor(String mcc);
}
