package dev.cardrail.sw.adapter.web;

import dev.cardrail.sw.application.AuthorizationConflictException;
import dev.cardrail.sw.application.AuthorizationNotFoundException;
import dev.cardrail.sw.application.InvalidCaptureAmountException;
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

/** RFC 7807 problem details for requests the switch could not process. Declines are not errors. */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(AuthorizationNotFoundException.class)
    ProblemDetail notFound(AuthorizationNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Authorization not found", e.getMessage());
    }

    @ExceptionHandler(AuthorizationConflictException.class)
    ProblemDetail conflict(AuthorizationConflictException e) {
        return problem(HttpStatus.CONFLICT, "Not allowed in the authorization's current state", e.getMessage());
    }

    @ExceptionHandler(InvalidCaptureAmountException.class)
    ProblemDetail invalidCaptureAmount(InvalidCaptureAmountException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid capture amount", e.getMessage());
    }

    @ExceptionHandler(InvalidSearchException.class)
    ProblemDetail invalidSearch(InvalidSearchException e) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid search", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        log.error("unhandled error", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error",
                "The request could not be processed; it is safe to retry with the same transmission key.");
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
