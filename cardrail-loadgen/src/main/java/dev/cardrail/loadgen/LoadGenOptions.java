package dev.cardrail.loadgen;

import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Command-line options of the load generator.
 *
 * @param pans  file of cards to use; empty to generate {@code cards} test cards
 * @param seed  seeds card generation, card choice, amounts and MCCs, so a run's request mix is reproducible
 */
public record LoadGenOptions(
        String host,
        int port,
        int connections,
        double rate,
        Duration duration,
        Duration warmup,
        Optional<Path> pans,
        int cards,
        Duration timeout,
        Optional<Path> csv,
        long seed,
        String acquirerId,
        String currency,
        String country,
        ZoneId terminalZone) {

    public static final String USAGE = """
            usage: cardrail-loadgen --rate TPS [--host HOST] [--port PORT] [--connections N]
                                    [--duration DUR] [--warmup DUR] [--pans generated|FILE] [--cards N]
                                    [--timeout DUR] [--csv FILE] [--seed N]
                                    [--acquirer-id ID] [--currency ISO4217] [--country ISO3166]

              --rate         target requests per second across all connections (open loop)
              --host         default 127.0.0.1
              --port         default 8583
              --connections  default 4; requests are spread evenly over them
              --duration     measured period, default 30s (DUR: 500ms, 30s, 2m, or ISO-8601 PT30S)
              --warmup       period before measurement whose results are discarded, default 5s
              --pans         'generated' (default) or a file with one PAN or PAN,YYMM per line
              --cards        number of generated cards, default 1000
              --timeout      per-request response timeout, default 5s
              --csv          append a summary row to FILE (header written if FILE is new)
              --seed         request-mix seed, default random (printed in the report)
              --acquirer-id  field 32, default 123456
              --currency     field 49, default 356
              --country      field 19, default 356
            """;

    private static final Pattern DURATION = Pattern.compile("(\\d+)(ms|s|m|h)");

    public LoadGenOptions {
        Objects.requireNonNull(host, "host");
        if (port < 1 || port > 0xFFFF) {
            throw new IllegalArgumentException("--port must be 1-65535");
        }
        if (connections < 1) {
            throw new IllegalArgumentException("--connections must be positive");
        }
        if (!(rate > 0) || Double.isInfinite(rate)) {
            throw new IllegalArgumentException("--rate must be a positive number");
        }
        requirePositive("--duration", duration);
        Objects.requireNonNull(warmup, "warmup");
        if (warmup.isNegative()) {
            throw new IllegalArgumentException("--warmup must not be negative");
        }
        requirePositive("--timeout", timeout);
        if (cards < 1) {
            throw new IllegalArgumentException("--cards must be positive");
        }
    }

    public static LoadGenOptions parse(String... args) {
        String host = "127.0.0.1";
        int port = 8583;
        int connections = 4;
        Double rate = null;
        Duration duration = Duration.ofSeconds(30);
        Duration warmup = Duration.ofSeconds(5);
        Optional<Path> pans = Optional.empty();
        int cards = 1000;
        Duration timeout = Duration.ofSeconds(5);
        Optional<Path> csv = Optional.empty();
        long seed = System.nanoTime();
        String acquirerId = "123456";
        String currency = "356";
        String country = "356";

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            String value;
            int eq = arg.indexOf('=');
            if (arg.startsWith("--") && eq > 0) {
                value = arg.substring(eq + 1);
                arg = arg.substring(0, eq);
            } else {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException(arg + " needs a value");
                }
                value = args[++i];
            }
            switch (arg) {
                case "--host" -> host = value;
                case "--port" -> port = parseInt(arg, value);
                case "--connections" -> connections = parseInt(arg, value);
                case "--rate" -> rate = parseDouble(arg, value);
                case "--duration" -> duration = parseDuration(arg, value);
                case "--warmup" -> warmup = parseDuration(arg, value);
                case "--pans" -> pans = value.equals("generated") ? Optional.empty() : Optional.of(Path.of(value));
                case "--cards" -> cards = parseInt(arg, value);
                case "--timeout" -> timeout = parseDuration(arg, value);
                case "--csv" -> csv = Optional.of(Path.of(value));
                case "--seed" -> seed = parseLong(arg, value);
                case "--acquirer-id" -> acquirerId = value;
                case "--currency" -> currency = value;
                case "--country" -> country = value;
                default -> throw new IllegalArgumentException("unknown option " + arg);
            }
        }
        if (rate == null) {
            throw new IllegalArgumentException("--rate is required");
        }
        return new LoadGenOptions(host, port, connections, rate, duration, warmup, pans, cards, timeout, csv, seed,
                acquirerId, currency, country, ZoneId.systemDefault());
    }

    static Duration parseDuration(String option, String value) {
        if (value.toUpperCase(Locale.ROOT).startsWith("P")) {
            try {
                return Duration.parse(value);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException(option + ": cannot parse '" + value + "'");
            }
        }
        Matcher m = DURATION.matcher(value);
        if (!m.matches()) {
            throw new IllegalArgumentException(option + ": expected e.g. 500ms, 30s, 2m, 1h or PT30S, got '" + value + "'");
        }
        long n = Long.parseLong(m.group(1));
        return switch (m.group(2)) {
            case "ms" -> Duration.ofMillis(n);
            case "s" -> Duration.ofSeconds(n);
            case "m" -> Duration.ofMinutes(n);
            default -> Duration.ofHours(n);
        };
    }

    private static int parseInt(String option, String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(option + ": not an integer: '" + value + "'");
        }
    }

    private static long parseLong(String option, String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(option + ": not an integer: '" + value + "'");
        }
    }

    private static double parseDouble(String option, String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(option + ": not a number: '" + value + "'");
        }
    }

    private static void requirePositive(String option, Duration d) {
        Objects.requireNonNull(d, option);
        if (d.isNegative() || d.isZero()) {
            throw new IllegalArgumentException(option + " must be positive");
        }
    }
}
