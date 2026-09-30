package com.premiumscanner.exception;

import java.time.Duration;
import java.util.List;

/**
 * Failure calling Alpaca. {@code upstreamStatus} is Alpaca's status; {@link #getStatus()} is what we
 * return to our own caller. An Alpaca 401 is OUR server's credential problem, so it surfaces as 502
 * (bad gateway) rather than telling the browser it is unauthorised.
 */
public class AlpacaApiException extends ScannerException {

    private final int upstreamStatus;
    private final Duration retryAfter;

    private AlpacaApiException(int status, String code, int upstreamStatus, String message, Duration retryAfter, Throwable cause) {
        super(status, code, message, List.of(), cause);
        this.upstreamStatus = upstreamStatus;
        this.retryAfter = retryAfter;
    }

    public static AlpacaApiException fromStatus(int upstreamStatus, String operation, String upstreamMessage, Duration retryAfter) {
        String detail = upstreamMessage == null || upstreamMessage.isBlank() ? "" : " - " + upstreamMessage;
        return switch (upstreamStatus) {
            case 400, 422 -> new AlpacaApiException(400, "ALPACA_BAD_REQUEST", upstreamStatus,
                    "Alpaca rejected the request for " + operation + " (invalid symbol or parameter)" + detail, null, null);
            case 401 -> new AlpacaApiException(502, "ALPACA_AUTH_FAILED", upstreamStatus,
                    "Alpaca rejected the API credentials. Check ALPACA_API_KEY / ALPACA_API_SECRET" + detail, null, null);
            case 403 -> new AlpacaApiException(502, "ALPACA_FORBIDDEN", upstreamStatus,
                    "Alpaca denied access for " + operation + " (subscription or feed not permitted, e.g. OPRA without a plan)" + detail, null, null);
            case 404 -> new AlpacaApiException(404, "ALPACA_NOT_FOUND", upstreamStatus,
                    "Alpaca found nothing for " + operation + detail, null, null);
            case 429 -> new AlpacaApiException(429, "ALPACA_RATE_LIMITED", upstreamStatus,
                    "Alpaca rate limit exceeded while requesting " + operation + ". Retry shortly" + detail, retryAfter, null);
            default -> upstreamStatus >= 500
                    ? new AlpacaApiException(502, "ALPACA_UNAVAILABLE", upstreamStatus,
                    "Alpaca server error (" + upstreamStatus + ") during " + operation + detail, retryAfter, null)
                    : new AlpacaApiException(502, "ALPACA_UNEXPECTED_STATUS", upstreamStatus,
                    "Unexpected Alpaca status " + upstreamStatus + " during " + operation + detail, null, null);
        };
    }

    public static AlpacaApiException network(String operation, Throwable cause) {
        return new AlpacaApiException(503, "ALPACA_UNREACHABLE", 0,
                "Could not reach Alpaca during " + operation + ": " + cause.getMessage(), null, cause);
    }

    public static AlpacaApiException interrupted(String operation) {
        return new AlpacaApiException(503, "INTERRUPTED", 0, "Interrupted while waiting to retry " + operation, null, null);
    }

    /** 429, 5xx and network failures are transient and worth retrying with backoff. */
    public boolean isRetryable() {
        return upstreamStatus == 429 || upstreamStatus >= 500 || upstreamStatus == 0 && "ALPACA_UNREACHABLE".equals(getCode());
    }

    public int getUpstreamStatus() {
        return upstreamStatus;
    }

    public Duration getRetryAfter() {
        return retryAfter;
    }
}
