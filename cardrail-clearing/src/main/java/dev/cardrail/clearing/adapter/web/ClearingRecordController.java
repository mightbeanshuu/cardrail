package dev.cardrail.clearing.adapter.web;

import dev.cardrail.clearing.port.ClearingRecords;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
class ClearingRecordController {

    private final ClearingRecords records;

    ClearingRecordController(ClearingRecords records) {
        this.records = records;
    }

    @GetMapping("/v1/clearing-records")
    PageView<ClearingRecordView> list(
            @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100_000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return new PageView<>(
                records.findByBusinessDate(businessDate, page, size).stream().map(ClearingRecordView::of).toList(),
                page, size, records.countByBusinessDate(businessDate));
    }
}
