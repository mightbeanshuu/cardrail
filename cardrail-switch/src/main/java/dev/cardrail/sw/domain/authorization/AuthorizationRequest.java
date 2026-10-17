package dev.cardrail.sw.domain.authorization;

import dev.cardrail.sw.domain.card.PanMasking;

import java.util.Objects;

/**
 * An authorization request (MTI 0100) as the switch sees it, independent of wire format.
 *
 * @param pan                  F2, held in memory only until tokenization
 * @param expiry               F14, YYMM
 * @param amountMinor          F4, in the currency's minor unit
 * @param currency             F49, ISO 4217 numeric (356 = INR)
 * @param mcc                  F18
 * @param merchantId           F42
 * @param merchantName         F43
 * @param merchantCountry      F19, ISO 3166 numeric
 * @param terminalId           F41
 * @param acquirerId           F32
 * @param stan                 F11
 * @param rrn                  F37
 * @param transmissionDateTime F7, MMDDhhmmss (GMT) exactly as transmitted
 * @param posEntryMode         F22
 */
public record AuthorizationRequest(
        String pan,
        String expiry,
        long amountMinor,
        int currency,
        String mcc,
        String merchantId,
        String merchantName,
        String merchantCountry,
        String terminalId,
        String acquirerId,
        String stan,
        String rrn,
        String transmissionDateTime,
        String posEntryMode) {

    public AuthorizationRequest {
        Objects.requireNonNull(pan, "pan");
        Objects.requireNonNull(expiry, "expiry");
        Objects.requireNonNull(mcc, "mcc");
        Objects.requireNonNull(merchantId, "merchantId");
        Objects.requireNonNull(merchantName, "merchantName");
        Objects.requireNonNull(merchantCountry, "merchantCountry");
        Objects.requireNonNull(terminalId, "terminalId");
        Objects.requireNonNull(acquirerId, "acquirerId");
        Objects.requireNonNull(stan, "stan");
        Objects.requireNonNull(rrn, "rrn");
        Objects.requireNonNull(transmissionDateTime, "transmissionDateTime");
        Objects.requireNonNull(posEntryMode, "posEntryMode");
    }

    public TransmissionKey transmissionKey() {
        return new TransmissionKey(acquirerId, stan, transmissionDateTime, terminalId);
    }

    /** The generated record toString would print the PAN; this one never does. */
    @Override
    public String toString() {
        return "AuthorizationRequest[pan=" + PanMasking.mask(pan)
                + ", amountMinor=" + amountMinor
                + ", currency=" + currency
                + ", mcc=" + mcc
                + ", merchantId=" + merchantId
                + ", terminalId=" + terminalId
                + ", acquirerId=" + acquirerId
                + ", stan=" + stan
                + ", rrn=" + rrn
                + ", transmissionDateTime=" + transmissionDateTime + "]";
    }
}
