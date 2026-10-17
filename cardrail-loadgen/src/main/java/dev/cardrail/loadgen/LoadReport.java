package dev.cardrail.loadgen;

import java.time.Duration;
import java.util.Locale;
import java.util.SortedMap;
import java.util.StringJoiner;
import java.util.TreeMap;

/**
 * Results of the measured period of one run. Every count covers only requests whose intended send
 * time fell inside that period.
 *
 * @param sent              requests sent
 * @param completed         requests answered within the timeout
 * @param timeouts          requests not answered within the timeout; not in {@code latency}
 * @param errors            requests that failed otherwise (connection lost, every STAN outstanding)
 * @param unmatched         responses that matched no outstanding request, over the whole run
 * @param latency           nanoseconds from intended send time to response, for completed requests
 * @param responseCodes     field 39 of completed requests
 * @param maxSendLagNanos   furthest a request was sent behind its intended time; large values mean the
 *                          generator itself could not keep up and the latencies include that delay
 */
public record LoadReport(
        double targetTps,
        int connections,
        Duration warmup,
        Duration duration,
        long seed,
        long sent,
        long completed,
        long timeouts,
        long errors,
        long unmatched,
        LatencyHistogram latency,
        SortedMap<String, Long> responseCodes,
        long maxSendLagNanos) {

    public LoadReport {
        responseCodes = new TreeMap<>(responseCodes);
    }

    public double achievedTps() {
        return completed / (duration.toNanos() / 1e9);
    }

    public String format() {
        StringBuilder sb = new StringBuilder();
        line(sb, "target", String.format(Locale.ROOT, "%.1f tps, %d connections, %s measured after %s warmup",
                targetTps, connections, human(duration), human(warmup)));
        line(sb, "sent", Long.toString(sent));
        line(sb, "completed", String.format(Locale.ROOT, "%d (%.1f tps)", completed, achievedTps()));
        line(sb, "timeouts", Long.toString(timeouts));
        line(sb, "errors", Long.toString(errors));
        line(sb, "unmatched", Long.toString(unmatched));
        line(sb, "latency ms", String.format(Locale.ROOT, "p50 %s  p90 %s  p99 %s  p99.9 %s  max %s",
                ms(percentile(50)), ms(percentile(90)), ms(percentile(99)), ms(percentile(99.9)), ms(latency.max())));
        StringJoiner codes = new StringJoiner("  ");
        responseCodes.forEach((code, n) -> codes.add(code + "=" + n));
        line(sb, "response codes", codes.length() == 0 ? "-" : codes.toString());
        line(sb, "max send lag", ms(maxSendLagNanos) + " ms");
        line(sb, "seed", Long.toString(seed));
        return sb.toString();
    }

    public static String csvHeader() {
        return "target_tps,connections,duration_s,warmup_s,sent,completed,achieved_tps,timeouts,errors,unmatched,"
                + "p50_ms,p90_ms,p99_ms,p999_ms,max_ms,max_send_lag_ms,response_codes,seed";
    }

    public String csvRow() {
        StringJoiner codes = new StringJoiner(";");
        responseCodes.forEach((code, n) -> codes.add(code + "=" + n));
        return String.join(",",
                String.format(Locale.ROOT, "%.1f", targetTps),
                Integer.toString(connections),
                String.format(Locale.ROOT, "%.3f", duration.toNanos() / 1e9),
                String.format(Locale.ROOT, "%.3f", warmup.toNanos() / 1e9),
                Long.toString(sent),
                Long.toString(completed),
                String.format(Locale.ROOT, "%.1f", achievedTps()),
                Long.toString(timeouts),
                Long.toString(errors),
                Long.toString(unmatched),
                ms(percentile(50)), ms(percentile(90)), ms(percentile(99)), ms(percentile(99.9)), ms(latency.max()),
                ms(maxSendLagNanos),
                codes.toString(),
                Long.toString(seed));
    }

    private long percentile(double p) {
        return latency.valueAtPercentile(p);
    }

    private static String ms(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1e6);
    }

    private static String human(Duration d) {
        return String.format(Locale.ROOT, "%.1fs", d.toNanos() / 1e9);
    }

    private static void line(StringBuilder sb, String label, String value) {
        sb.append(String.format(Locale.ROOT, "%-15s %s%n", label, value));
    }
}
