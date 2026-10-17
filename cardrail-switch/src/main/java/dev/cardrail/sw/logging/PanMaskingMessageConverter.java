package dev.cardrail.sw.logging;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Replaces Logback's {@code %m}/{@code %msg}/{@code %message}: the rendered message with every card
 * number masked ({@link LogRedaction}). Registered in logback-spring.xml under the built-in names,
 * so no logger anywhere (including third-party ones) can print a full PAN through the pattern layout.
 */
public class PanMaskingMessageConverter extends MessageConverter {

    @Override
    public String convert(ILoggingEvent event) {
        return LogRedaction.redact(super.convert(event));
    }
}
