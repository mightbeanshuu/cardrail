package dev.cardrail.sw.adapter.iso;

import dev.cardrail.gateway.IsoRequestHandler;
import dev.cardrail.iso8583.IsoCodec;
import dev.cardrail.iso8583.IsoMessage;
import dev.cardrail.iso8583.OriginalDataElements;
import dev.cardrail.sw.application.AuthorizationConflictException;
import dev.cardrail.sw.application.AuthorizationInFlightException;
import dev.cardrail.sw.application.AuthorizationNotFoundException;
import dev.cardrail.sw.application.AuthorizeService;
import dev.cardrail.sw.application.ReverseService;
import dev.cardrail.sw.domain.authorization.AuthorizationRequest;
import dev.cardrail.sw.domain.authorization.AuthorizationResult;
import dev.cardrail.sw.domain.authorization.ResponseCode;
import dev.cardrail.sw.domain.authorization.TransmissionKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.Optional;

/**
 * Turns decoded ISO 8583 requests into calls on the authorization and reversal services.
 *
 * <ul>
 *   <li>{@code 0100} (and its repeat {@code 0101}): fields 2, 3, 4, 7, 11, 14, 18, 19, 22, 32, 37,
 *       41, 42, 43 and 49 are required; a missing one is a format error ({@code 30}). Only purchases
 *       (processing code {@code 00xxxx}) are supported; other transaction types get {@code 12}.
 *       Fields 12 and 13 (terminal local time) are echoed but not used.</li>
 *   <li>{@code 0400} (and {@code 0401}): the original is located by field 90 (original STAN,
 *       transmission time and acquirer) plus field 41. The whole authorization is reversed; field 4
 *       is not read, so partial reversals are not supported.</li>
 *   <li>Any other MTI: {@code 12}.</li>
 * </ul>
 *
 * Space-padded {@code ans} fields (41, 42, 43) are stored without their trailing padding.
 */
public final class SwitchIsoHandler implements IsoRequestHandler {

    private static final Logger log = LoggerFactory.getLogger(SwitchIsoHandler.class);
    private static final String PURCHASE = "00";
    private static final int ACQUIRER_ID_WIDTH = 11;

    private final AuthorizeService authorize;
    private final ReverseService reverse;

    public SwitchIsoHandler(AuthorizeService authorize, ReverseService reverse) {
        this.authorize = authorize;
        this.reverse = reverse;
    }

    @Override
    public IsoMessage handle(IsoMessage request) {
        return switch (request.mti().value()) {
            case "0100", "0101" -> authorize(request);
            case "0400", "0401" -> reverse(request);
            default -> respond(request, ResponseCode.INVALID_TRANSACTION);
        };
    }

    private IsoMessage authorize(IsoMessage m) {
        Optional<AuthorizationRequest> request = toAuthorizationRequest(m);
        if (request.isEmpty()) {
            return respond(m, ResponseCode.FORMAT_ERROR);
        }
        if (!m.processingCode().orElseThrow().startsWith(PURCHASE)) {
            return respond(m, ResponseCode.INVALID_TRANSACTION);
        }
        AuthorizationResult result = authorize.authorize(request.get());
        return IsoCodec.responseFor(m, result.responseCode().code(), result.approved() ? result.authCode() : null);
    }

    private static Optional<AuthorizationRequest> toAuthorizationRequest(IsoMessage m) {
        for (int field : new int[] {2, 3, 4, 7, 11, 14, 18, 19, 22, 32, 37, 41, 42, 43, 49}) {
            if (!m.has(field)) {
                log.info("0100 without field {}", field);
                return Optional.empty();
            }
        }
        return Optional.of(new AuthorizationRequest(
                m.pan().orElseThrow(),
                m.expiry().orElseThrow(),
                m.amountMinor().orElseThrow(),
                Integer.parseInt(m.currencyCode().orElseThrow()),
                m.mcc().orElseThrow(),
                unpadded(m, 42),
                unpadded(m, 43),
                m.field(19).orElseThrow(),
                unpadded(m, 41),
                m.acquirerId().orElseThrow(),
                m.stan().orElseThrow(),
                m.retrievalReferenceNumber().orElseThrow(),
                m.transmissionDateTime().orElseThrow(),
                m.field(22).orElseThrow()));
    }

    private IsoMessage reverse(IsoMessage m) {
        Optional<OriginalDataElements> original = m.originalDataElements();
        if (original.isEmpty() || !m.has(41)) {
            return respond(m, ResponseCode.FORMAT_ERROR);
        }
        String originalMti = original.get().originalMti().value();
        if (!originalMti.equals("0100") && !originalMti.equals("0101")) {
            return respond(m, ResponseCode.INVALID_TRANSACTION);
        }
        String acquirerId = originalAcquirerId(original.get(), m.acquirerId());
        TransmissionKey key = new TransmissionKey(acquirerId, original.get().originalStan(),
                original.get().originalTransmissionDateTime(), unpadded(m, 41));
        try (MDC.MDCCloseable stan = MDC.putCloseable("stan", key.stan())) {
            reverse.reverse(key, "acquirer:" + acquirerId);
            return respond(m, ResponseCode.APPROVED);
        } catch (AuthorizationNotFoundException e) {
            log.info("0400 for an unknown original (acquirer {}, stan {}, F7 {})", acquirerId, key.stan(),
                    key.transmissionDateTime());
            return respond(m, ResponseCode.INVALID_TRANSACTION);
        } catch (AuthorizationInFlightException e) {
            // The acquirer repeats an unanswered reversal; by then the original has been decided.
            return respond(m, ResponseCode.SYSTEM_MALFUNCTION);
        } catch (AuthorizationConflictException e) {
            log.info("0400 rejected: {}", e.getMessage());
            return respond(m, ResponseCode.INVALID_TRANSACTION);
        }
    }

    /**
     * Field 90 carries the original acquirer ID zero-padded to 11 digits, while field 32 is stored as
     * sent. When this 0400's own field 32 pads to the same value it is the original's spelling;
     * otherwise the padding is stripped.
     */
    static String originalAcquirerId(OriginalDataElements original, Optional<String> field32) {
        String padded = original.originalAcquirerId();
        if (field32.isPresent() && pad(field32.get()).equals(padded)) {
            return field32.get();
        }
        String stripped = padded.replaceFirst("^0+", "");
        return stripped.isEmpty() ? "0" : stripped;
    }

    private static String pad(String acquirerId) {
        return acquirerId.length() >= ACQUIRER_ID_WIDTH
                ? acquirerId
                : "0".repeat(ACQUIRER_ID_WIDTH - acquirerId.length()) + acquirerId;
    }

    private static String unpadded(IsoMessage m, int field) {
        return m.field(field).orElseThrow().stripTrailing();
    }

    private static IsoMessage respond(IsoMessage request, ResponseCode code) {
        return IsoCodec.responseFor(request, code.code(), null);
    }
}
