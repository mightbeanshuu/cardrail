package dev.cardrail.clearing.support;

import dev.cardrail.clearing.application.CapturedAuthorization;
import dev.cardrail.clearing.application.ClearingService;
import dev.cardrail.clearing.application.EventOutcome;
import dev.cardrail.clearing.application.ReversedAuthorization;
import dev.cardrail.clearing.application.SettlementProperties;
import org.springframework.batch.test.JobRepositoryTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;

public class TestFixtures {

    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Autowired JdbcClient jdbc;
    @Autowired ClearingService clearing;
    @Autowired JobRepositoryTestUtils jobRepositoryTestUtils;
    @Autowired SettlementProperties settlementProperties;
    @Autowired SettlementStoreFaults faults;

    public void reset() {
        faults.clear();
        for (String table : new String[] {"settlement_position", "settlement_partial", "processed_event", "clearing_record"}) {
            jdbc.sql("DELETE FROM " + table).update();
        }
        jobRepositoryTestUtils.removeJobExecutions();
        try {
            FileSystemUtils.deleteRecursively(settlementProperties.reportDirectory());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public JdbcClient jdbc() {
        return jdbc;
    }

    /** Mid-morning in Pune on the given business date, well clear of the cut-off. */
    public static Instant morningOf(LocalDate businessDate) {
        return businessDate.atTime(LocalTime.of(10, 30)).atZone(IST).toInstant();
    }

    public UUID capture(String acquirerId, String maskedPan, String mcc, int currency, long amountMinor, Instant at) {
        UUID id = UUID.randomUUID();
        EventOutcome outcome = clearing.onCaptured(new CapturedAuthorization(id, "tok-" + id, maskedPan, acquirerId,
                "MERCHANT000001", mcc, currency, amountMinor, at), "test");
        if (outcome != EventOutcome.RECORDED) {
            throw new IllegalStateException("capture not recorded: " + outcome);
        }
        return id;
    }

    public EventOutcome reverse(UUID authorizationId, Instant at) {
        return clearing.onReversed(new ReversedAuthorization(authorizationId, at), "test");
    }

    public long count(String sql, Object... params) {
        JdbcClient.StatementSpec spec = jdbc.sql(sql);
        for (Object p : params) {
            spec = spec.param(p);
        }
        return spec.query(Long.class).single();
    }
}
