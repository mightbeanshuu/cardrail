package dev.cardrail.loadgen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.List;

/** Command-line entry point; see {@link LoadGenOptions#USAGE}. */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws InterruptedException {
        if (Arrays.asList(args).contains("--help") || Arrays.asList(args).contains("-h")) {
            System.out.print(LoadGenOptions.USAGE);
            return;
        }
        LoadGenOptions options;
        LoadGenerator generator;
        try {
            options = LoadGenOptions.parse(args);
            CardPool cards = options.pans().isPresent()
                    ? CardPool.fromFile(options.pans().get(), YearMonth.now().plusYears(3))
                    : CardPool.generated(options.cards(), options.seed(), YearMonth.now());
            generator = new LoadGenerator(options, cards);
        } catch (IllegalArgumentException | IOException e) {
            System.err.println("cardrail-loadgen: " + e.getMessage());
            System.err.print(LoadGenOptions.USAGE);
            System.exit(2);
            return;
        }

        LoadReport report;
        try {
            report = generator.run();
        } catch (IOException e) {
            System.err.println("cardrail-loadgen: cannot connect to " + options.host() + ":" + options.port() + ": " + e);
            System.exit(1);
            return;
        }
        System.out.print(report.format());

        if (options.csv().isPresent()) {
            try {
                appendCsv(options.csv().get(), report);
            } catch (IOException e) {
                System.err.println("cardrail-loadgen: cannot write " + options.csv().get() + ": " + e);
                System.exit(1);
            }
        }
    }

    static void appendCsv(Path file, LoadReport report) throws IOException {
        boolean fresh = !Files.exists(file) || Files.size(file) == 0;
        List<String> lines = fresh ? List.of(LoadReport.csvHeader(), report.csvRow()) : List.of(report.csvRow());
        Files.write(file, lines, StandardCharsets.US_ASCII, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
