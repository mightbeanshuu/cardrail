package dev.cardrail.clearing.adapter.web;

import dev.cardrail.clearing.application.ClearingProperties;
import dev.cardrail.clearing.application.SettlementReport;
import dev.cardrail.clearing.application.SettlementResult;
import dev.cardrail.clearing.application.SettlementService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

@RestController
class SettlementRunController {

    static final MediaType TEXT_CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final SettlementService settlement;
    private final ClearingProperties properties;

    SettlementRunController(SettlementService settlement, ClearingProperties properties) {
        this.settlement = settlement;
        this.properties = properties;
    }

    record BusinessDateView(LocalDate businessDate, ZoneId zone, LocalTime cutOff) {
    }

    /** So a client settles the date the server means by today, whatever the client's own time zone. */
    @GetMapping("/v1/business-date")
    BusinessDateView businessDate() {
        return new BusinessDateView(settlement.currentBusinessDate(), properties.settlementZone(), properties.cutOff());
    }

    static String runPath(LocalDate businessDate) {
        return "/v1/settlement-runs/" + businessDate;
    }

    static String reportPath(LocalDate businessDate) {
        return runPath(businessDate) + "/report.csv";
    }

    /** 201 when this call ran the job, 200 when the date was already settled and nothing ran. */
    @PostMapping("/v1/settlement-runs")
    ResponseEntity<SettlementRunView> run(@Valid @RequestBody SettlementRunRequest request) {
        SettlementResult result = settlement.settle(request.businessDate());
        SettlementRunView body = SettlementRunView.of(result.run());
        return result.launched()
                ? ResponseEntity.created(URI.create(runPath(request.businessDate()))).body(body)
                : ResponseEntity.ok(body);
    }

    @GetMapping("/v1/settlement-runs/{businessDate}")
    SettlementRunView get(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {
        return SettlementRunView.of(settlement.find(businessDate));
    }

    @GetMapping("/v1/settlement-runs/{businessDate}/report.csv")
    ResponseEntity<String> report(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {
        return ResponseEntity.ok()
                .contentType(TEXT_CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(SettlementReport.fileName(businessDate)).build().toString())
                .body(settlement.report(businessDate));
    }
}
