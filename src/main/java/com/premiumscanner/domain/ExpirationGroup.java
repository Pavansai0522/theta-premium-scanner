package com.premiumscanner.domain;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * All strikes of one expiration.
 *
 * @param spotRowIndex index of the first row whose strike is above spot; the UI draws the
 *                     underlying-price divider before it (== rows.size() when all strikes are below spot)
 */
public record ExpirationGroup(LocalDate expiration, int dte, boolean monthly, int multiplier,
                              List<StrikeRow> rows, int spotRowIndex,
                              int callCount, int putCount, int missingGreeks) {

    private static final DateTimeFormatter LABEL = DateTimeFormatter.ofPattern("dd MMM yy", Locale.US);

    public ExpirationGroup {
        rows = List.copyOf(rows);
    }

    /** e.g. "21 Aug 26" for a monthly, "14 Aug 26 (W)" for a weekly/daily expiration. */
    public String label() {
        return expiration.format(LABEL) + (monthly ? "" : " (W)");
    }
}
