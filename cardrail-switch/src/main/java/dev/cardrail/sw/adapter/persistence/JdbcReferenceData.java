package dev.cardrail.sw.adapter.persistence;

import dev.cardrail.sw.port.MccRules;
import dev.cardrail.sw.port.StandInLimits;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.OptionalLong;

@Repository
class JdbcReferenceData implements StandInLimits, MccRules {

    private final JdbcClient jdbc;

    JdbcReferenceData(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public OptionalLong limitFor(String bin, int currency) {
        return jdbc.sql("""
                        SELECT max_amount_minor FROM stand_in_limit
                         WHERE currency = :currency AND starts_with(:bin, bin_prefix)
                         ORDER BY length(bin_prefix) DESC
                         LIMIT 1
                        """)
                .param("currency", currency)
                .param("bin", bin)
                .query(Long.class)
                .optional()
                .map(OptionalLong::of)
                .orElse(OptionalLong.empty());
    }

    @Override
    public boolean isBlocked(String mcc) {
        return jdbc.sql("SELECT blocked FROM mcc_rule WHERE mcc = :mcc")
                .param("mcc", mcc)
                .query(Boolean.class)
                .optional()
                .orElse(false);
    }
}
