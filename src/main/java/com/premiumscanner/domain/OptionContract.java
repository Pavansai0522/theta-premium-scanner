package com.premiumscanner.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Static contract terms, parsed from the OCC symbol (e.g. SPY260821C00680000). */
public record OptionContract(String symbol, String underlying, OptionType type, BigDecimal strike, LocalDate expiration) {

    @JsonIgnore
    public boolean isCall() {
        return type == OptionType.CALL;
    }

    @JsonIgnore
    public boolean isPut() {
        return type == OptionType.PUT;
    }
}