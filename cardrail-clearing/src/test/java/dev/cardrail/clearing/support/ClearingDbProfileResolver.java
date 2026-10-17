package dev.cardrail.clearing.support;

import org.springframework.test.context.ActiveProfilesResolver;

/**
 * Runs the same suite on PostgreSQL (default) or Oracle (CI sets {@code CLEARING_DB=oracle} and
 * the SPRING_DATASOURCE_* variables, which override the oracle profile's defaults).
 */
public class ClearingDbProfileResolver implements ActiveProfilesResolver {

    @Override
    public String[] resolve(Class<?> testClass) {
        return "oracle".equalsIgnoreCase(System.getenv("CLEARING_DB"))
                ? new String[] {"test", "oracle"}
                : new String[] {"test"};
    }
}
