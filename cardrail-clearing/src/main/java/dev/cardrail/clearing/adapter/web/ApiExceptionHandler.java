package dev.cardrail.clearing.adapter.web;

import dev.cardrail.clearing.application.BusinessDateNotStartedException;
import dev.cardrail.clearing.application.SettlementReportNotReadyException;
import dev.cardrail.clearing.application.SettlementRunFailedException;
import dev.cardrail.clearing.application.SettlementRunInProgressException;
import dev.cardrail.clearing.application.SettlementRunNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.List;

/** RFC 7807 problem details. */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(SettlementRunNotFoundException.class)
    ProblemDetail notFound(SettlementRunNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Settlement run not found", e.getMessage());
    }

    @ExceptionHandler(SettlementRunInProgressException.class)
    ProblemDetail inProgress(SettlementRunInProgressException e) {
        return problem(HttpStatus.CONFLICT, "Settlement run in progress", e.getMessage());
    }

    @ExceptionHandler(SettlementReportNotReadyException.class)
    ProblemDetail reportNotReady(SettlementReportNotReadyException e) {
        return problem(HttpStatus.CONFLICT, "Settlement report not ready", e.getMessage());
    }

    @ExceptionHandler(BusinessDateNotStartedException.class)
    ProblemDetail notStarted(BusinessDateNotStartedException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Business date not started", e.getMessage());
    }

    @ExceptionHandler(SettlementRunFailedException.class)
    ProblemDetail failed(SettlementRunFailedException e) {
        log.error("settlement run failed: {}", e.getMessage());
        ProblemDetail problem = problem(HttpStatus.INTERNAL_SERVER_ERROR, "Settlement run failed",
                e.run().failure() + ". Fix the cause and POST the same business date again to resume the run.");
        problem.setProperty("businessDate", e.run().businessDate().toString());
        problem.setProperty("status", e.run().status().name());
        return problem;
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        log.error("unhandled error", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error", "The request could not be processed.");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Invalid request", "One or more fields are invalid.");
        List<String> errors = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .sorted()
                .toList();
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
