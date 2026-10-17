package dev.cardrail.clearing.application;

import dev.cardrail.clearing.domain.BusinessCalendar;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.LocalTime;
import java.time.ZoneId;

@ConfigurationProperties("cardrail.clearing")
public record ClearingProperties(
        @DefaultValue("Asia/Kolkata") ZoneId settlementZone,
        @DefaultValue("23:00") LocalTime cutOff) {

    public BusinessCalendar calendar() {
        return new BusinessCalendar(settlementZone, cutOff);
    }
}
