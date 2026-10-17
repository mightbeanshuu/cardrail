package dev.cardrail.sw.logging;

import ch.qos.logback.classic.spi.IThrowableProxy;
import org.springframework.boot.logging.logback.ExtendedWhitespaceThrowableProxyConverter;

/** Exception messages can carry input values (a NumberFormatException quoting a PAN, say). */
public class PanMaskingThrowableConverter extends ExtendedWhitespaceThrowableProxyConverter {

    @Override
    protected String throwableProxyToString(IThrowableProxy tp) {
        return LogRedaction.redact(super.throwableProxyToString(tp));
    }
}
