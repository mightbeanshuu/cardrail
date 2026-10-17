package dev.cardrail.clearing.adapter.persistence;

import dev.cardrail.clearing.domain.InterchangeRate;
import dev.cardrail.clearing.port.InterchangeRates;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcInterchangeRates implements InterchangeRates {

    static final String DEFAULT_CATEGORY = "DEFAULT";

    private final JdbcClient jdbc;

    JdbcInterchangeRates(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public InterchangeRate rateFor(String mcc) {
        return jdbc.sql("""
                        SELECT r.category, r.rate_bps, r.fixed_fee_minor
                          FROM interchange_rate r
                         WHERE r.category = COALESCE(
                               (SELECT m.category FROM mcc_interchange_category m WHERE m.mcc = :mcc), :defaultCategory)
                        """)
                .param("mcc", mcc)
                .param("defaultCategory", DEFAULT_CATEGORY)
                .query((rs, n) -> new InterchangeRate(rs.getString("category"), rs.getInt("rate_bps"),
                        rs.getLong("fixed_fee_minor")))
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "interchange_rate has no " + DEFAULT_CATEGORY + " row; the reference data migration did not run"));
    }
}
