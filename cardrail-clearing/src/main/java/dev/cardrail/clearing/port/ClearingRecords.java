package dev.cardrail.clearing.port;

import dev.cardrail.clearing.domain.ClearingRecord;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ClearingRecords {

    /** @throws org.springframework.dao.DuplicateKeyException if the authorization already has a record of this type */
    void insert(ClearingRecord record);

    Optional<ClearingRecord> findCapture(UUID authorizationId);

    List<ClearingRecord> findByBusinessDate(LocalDate businessDate, int page, int size);

    long countByBusinessDate(LocalDate businessDate);
}
