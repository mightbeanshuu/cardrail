package dev.cardrail.clearing.adapter.batch;

import dev.cardrail.clearing.application.SettlementReport;
import dev.cardrail.clearing.port.SettlementStore;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;

/** Rewrites the whole file from the stored positions, so re-running the step is harmless. */
class WriteReportTasklet implements Tasklet {

    private final SettlementStore store;
    private final Path directory;
    private final LocalDate settlementDate;

    WriteReportTasklet(SettlementStore store, Path directory, LocalDate settlementDate) {
        this.store = store;
        this.directory = directory;
        this.settlementDate = settlementDate;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws IOException {
        String csv = SettlementReport.render(settlementDate, store.positions(settlementDate));
        Files.createDirectories(directory);
        Path target = directory.resolve(SettlementReport.fileName(settlementDate));
        Path temp = Files.createTempFile(directory, target.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temp, csv, StandardCharsets.UTF_8);
            // Readers of the directory see either no report or a complete one, never a partial file.
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
        return RepeatStatus.FINISHED;
    }
}
