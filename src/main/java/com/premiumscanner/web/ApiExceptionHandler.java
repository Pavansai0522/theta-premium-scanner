package com.premiumscanner.web;

import com.premiumscanner.exception.AlpacaApiException;
import com.premiumscanner.exception.ScannerException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.util.List;

/** Maps every failure on the REST API to a consistent JSON body: {status, code, message, details}. */
@RestControllerAdvice(assignableTypes = OptionsApiController.class)
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ScannerException.class)
    public ResponseEntity<ApiError> handleScanner(ScannerException e, HttpServletRequest request) {
        Long retryAfter = null;
        HttpHeaders headers = new HttpHeaders();
        if (e instanceof AlpacaApiException alpaca && alpaca.getRetryAfter() != null) {
            retryAfter = alpaca.getRetryAfter().toSeconds();
            headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter));
        }
        if (e.getStatus() >= 500) {
            log.warn("{} {} -> {} {}", request.getMethod(), request.getRequestURI(), e.getCode(), e.getMessage());
        }
        ApiError body = new ApiError(Instant.now(), e.getStatus(), e.getCode(), e.getMessage(),
                e.getDetails().isEmpty() ? null : e.getDetails(), request.getRequestURI(), retryAfter);
        return ResponseEntity.status(e.getStatus()).headers(headers).body(body);
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiError> handleBind(BindException e, HttpServletRequest request) {
        List<String> details = e.getFieldErrors().stream()
                .map(f -> f.getField() + ": invalid value '" + f.getRejectedValue() + "'")
                .toList();
        return badRequest("Invalid query parameters", details, request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException e, HttpServletRequest request) {
        return badRequest("Invalid query parameters",
                List.of(e.getName() + ": invalid value '" + e.getValue() + "'"), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception e, HttpServletRequest request) {
        log.error("Unhandled error on {}", request.getRequestURI(), e);
        return ResponseEntity.status(500).body(new ApiError(Instant.now(), 500, "INTERNAL_ERROR",
                "Unexpected error; see server logs", null, request.getRequestURI(), null));
    }

    private static ResponseEntity<ApiError> badRequest(String message, List<String> details, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(new ApiError(Instant.now(), 400, "INVALID_PARAMETERS", message,
                details, request.getRequestURI(), null));
    }
}
