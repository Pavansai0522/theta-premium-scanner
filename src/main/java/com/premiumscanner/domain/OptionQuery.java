package com.premiumscanner.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Server-side filters pushed down to Alpaca so we only page through the contracts we need. All nullable. */
public record OptionQuery(LocalDate expirationFrom, LocalDate expirationTo,
                          BigDecimal strikeFrom, BigDecimal strikeTo, OptionType type) {

    public String cacheKey() {
        return expirationFrom + "|" + expirationTo + "|" + plain(strikeFrom) + "|" + plain(strikeTo) + "|" + type;
    }

    private static String plain(BigDecimal v) {
        return v == null ? "null" : v.toPlainString();
    }
}
