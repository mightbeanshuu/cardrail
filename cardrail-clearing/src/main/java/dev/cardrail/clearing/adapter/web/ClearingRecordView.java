package dev.cardrail.clearing.adapter.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.cardrail.clearing.domain.ClearingRecord;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
record ClearingRecordView(long id, UUID authorizationId, String recordType, String token, String maskedPan,
                          String issuerBin, String acquirerId, String merchantId, String mcc, int currency,
                          long amountMinor, String interchangeCategory, long interchangeFeeMinor,
                          LocalDate businessDate, Instant occurredAt, String status, LocalDate settlementDate,
                          Instant settledAt, Instant createdAt) {

    static ClearingRecordView of(ClearingRecord r) {
        return new ClearingRecordView(r.id(), r.authorizationId(), r.type().name(), r.token(), r.maskedPan(),
                r.issuerBin(), r.acquirerId(), r.merchantId(), r.mcc(), r.currency(), r.amountMinor(),
                r.interchangeCategory(), r.interchangeFeeMinor(), r.businessDate(), r.occurredAt(),
                r.status().name(), r.settlementDate(), r.settledAt(), r.createdAt());
    }
}
