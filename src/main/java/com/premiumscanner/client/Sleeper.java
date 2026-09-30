package com.premiumscanner.client;

import com.premiumscanner.exception.AlpacaApiException;

import java.time.Duration;

/** Abstraction over Thread.sleep so retry/backoff behaviour is testable without real waiting. */
@FunctionalInterface
public interface Sleeper {

    void sleep(Duration duration);

    Sleeper THREAD = duration -> {
        try {
            Thread.sleep(Math.max(0, duration.toMillis()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw AlpacaApiException.interrupted("Alpaca request");
        }
    };
}
