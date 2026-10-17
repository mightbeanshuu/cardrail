package dev.cardrail.loadgen;

import dev.cardrail.iso8583.IsoCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(value = 60, unit = TimeUnit.SECONDS)
class LoadGeneratorTest {

    private static LoadGenOptions options(int port, String... extra) {
        String[] base = {"--host", "127.0.0.1", "--port", Integer.toString(port), "--seed", "11"};
        String[] all = new String[base.length + extra.length];
        System.arraycopy(base, 0, all, 0, base.length);
        System.arraycopy(extra, 0, all, base.length, extra.length);
        return LoadGenOptions.parse(all);
    }

    @Test
    void sendsOnScheduleAndReportsEveryAnswer() throws Exception {
        try (FakeIsoServer server = new FakeIsoServer(req -> {
            long amount = req.amountMinor().orElseThrow();
            return Optional.of(IsoCodec.responseFor(req, amount % 10 == 0 ? "51" : "00", null));
        }, true)) {
            LoadGenOptions opts = options(server.port(),
                    "--connections", "3", "--rate", "400", "--warmup", "250ms", "--duration", "1s");
            LoadReport report = new LoadGenerator(opts, CardPool.generated(50, 11, YearMonth.now())).run();

            assertThat(report.sent()).isBetween(399L, 401L);
            assertThat(report.completed()).isEqualTo(report.sent());
            assertThat(report.timeouts()).isZero();
            assertThat(report.errors()).isZero();
            assertThat(report.unmatched()).isZero();
            assertThat(report.latency().count()).isEqualTo(report.completed());
            assertThat(report.responseCodes().keySet()).isSubsetOf("00", "51");
            assertThat(report.responseCodes().values().stream().mapToLong(Long::longValue).sum())
                    .isEqualTo(report.completed());

            // Warmup requests are sent but not counted: 0.25 s + 1 s at 400 tps.
            assertThat(server.received).hasSizeBetween(499, 501);
            Set<String> terminals = server.received.stream()
                    .map(m -> m.terminalId().orElseThrow()).collect(Collectors.toSet());
            assertThat(terminals).containsExactlyInAnyOrder("LG000000", "LG000001", "LG000002");
            Set<String> duplicateKeys = server.received.stream()
                    .map(m -> m.terminalId().orElseThrow() + m.stan().orElseThrow() + m.transmissionDateTime().orElseThrow())
                    .collect(Collectors.toSet());
            assertThat(duplicateKeys).hasSameSizeAs(server.received);

            String text = report.format();
            assertThat(text).contains("p99.9").contains("response codes");
        }
    }

    @Test
    void aServerStallDoesNotSlowTheScheduleAndShowsUpInLatency() throws Exception {
        AtomicBoolean stalled = new AtomicBoolean();
        // Answers in arrival order on one thread, pausing once for 500 ms, like a server stuck in a GC pause.
        try (FakeIsoServer server = new FakeIsoServer(req -> {
            if (Integer.parseInt(req.stan().orElseThrow()) == 50 && stalled.compareAndSet(false, true)) {
                Thread.sleep(500);
            }
            return Optional.of(IsoCodec.responseFor(req, "00", null));
        }, false)) {
            LoadGenOptions opts = options(server.port(),
                    "--connections", "1", "--rate", "200", "--warmup", "0s", "--duration", "1500ms");
            LoadReport report = new LoadGenerator(opts, CardPool.generated(10, 11, YearMonth.now())).run();

            // A closed-loop client would have sent nothing during the pause; this one kept to schedule.
            assertThat(report.sent()).isBetween(299L, 301L);
            assertThat(report.completed()).isEqualTo(report.sent());
            assertThat(report.latency().max()).isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(450));
            // About 100 requests were due during the pause, so a third of the sample waited on it.
            assertThat(report.latency().valueAtPercentile(90)).isGreaterThan(TimeUnit.MILLISECONDS.toNanos(100));
        }
    }

    @Test
    void unansweredRequestsAreCountedAsTimeoutsNotLatency() throws Exception {
        try (FakeIsoServer server = new FakeIsoServer(req ->
                req.amountMinor().orElseThrow() % 2 == 0 ? Optional.empty()
                        : Optional.of(IsoCodec.responseFor(req, "00", null)), true)) {
            LoadGenOptions opts = options(server.port(),
                    "--connections", "2", "--rate", "200", "--warmup", "0s", "--duration", "500ms", "--timeout", "200ms");
            LoadReport report = new LoadGenerator(opts, CardPool.generated(10, 11, YearMonth.now())).run();

            assertThat(report.timeouts()).isPositive();
            assertThat(report.completed() + report.timeouts()).isEqualTo(report.sent());
            assertThat(report.latency().count()).isEqualTo(report.completed());
        }
    }

    @Test
    void csvGetsOneHeaderAndARowPerRun(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("runs.csv");
        try (FakeIsoServer server = FakeIsoServer.approving()) {
            LoadGenOptions opts = options(server.port(), "--rate", "100", "--warmup", "0s", "--duration", "200ms");
            for (int i = 0; i < 2; i++) {
                Main.appendCsv(csv, new LoadGenerator(opts, CardPool.generated(10, 11, YearMonth.now())).run());
            }
        }
        List<String> lines = Files.readAllLines(csv);
        assertThat(lines).hasSize(3);
        assertThat(lines.get(0)).isEqualTo(LoadReport.csvHeader());
        int columns = LoadReport.csvHeader().split(",").length;
        assertThat(lines.get(1).split(",")).hasSize(columns);
        assertThat(lines.get(2)).contains("00=");
    }

    @Test
    void parsesDurationsAndRejectsBadOptions() {
        assertThat(LoadGenOptions.parseDuration("--d", "500ms").toMillis()).isEqualTo(500);
        assertThat(LoadGenOptions.parseDuration("--d", "2m").toSeconds()).isEqualTo(120);
        assertThat(LoadGenOptions.parseDuration("--d", "PT1.5S").toMillis()).isEqualTo(1500);
        LoadGenOptions o = LoadGenOptions.parse("--rate=250", "--connections", "8", "--pans", "cards.txt");
        assertThat(o.rate()).isEqualTo(250.0);
        assertThat(o.connections()).isEqualTo(8);
        assertThat(o.pans()).contains(Path.of("cards.txt"));
        assertThatThrownBy(() -> LoadGenOptions.parse("--connections", "2"))
                .hasMessageContaining("--rate is required");
        assertThatThrownBy(() -> LoadGenOptions.parse("--rate", "1", "--bogus", "x"))
                .hasMessageContaining("unknown option --bogus");
        assertThatThrownBy(() -> LoadGenOptions.parse("--rate", "1", "--duration", "10"))
                .hasMessageContaining("--duration");
    }
}
