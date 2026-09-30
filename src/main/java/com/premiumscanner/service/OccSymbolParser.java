package com.premiumscanner.service;

import com.premiumscanner.domain.OptionContract;
import com.premiumscanner.domain.OptionType;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses OCC option symbols: ROOT + YYMMDD + C/P + strike*1000 (8 digits), e.g. SPY260821C00680000.
 * The suffix is fixed-width (15 chars), so the root is unambiguous even for adjusted roots like "SPY1".
 */
public final class OccSymbolParser {

    private static final Pattern OCC = Pattern.compile("^([A-Z0-9.]{1,6})(\\d{2})(\\d{2})(\\d{2})([CP])(\\d{8})$");

    private OccSymbolParser() {
    }

    public static Optional<OptionContract> parse(String symbol, String underlying) {
        if (symbol == null) return Optional.empty();
        String s = symbol.trim();
        Matcher m = OCC.matcher(s);
        if (!m.matches()) return Optional.empty();
        try {
            LocalDate expiration = LocalDate.of(2000 + Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)));
            OptionType type = OptionType.fromOccCode(m.group(5).charAt(0));
            BigDecimal strike = new BigDecimal(m.group(6)).movePointLeft(3).stripTrailingZeros();
            if (strike.scale() < 0) strike = strike.setScale(0);
            if (strike.signum() <= 0) return Optional.empty();
            String root = underlying == null || underlying.isBlank() ? m.group(1) : underlying;
            return Optional.of(new OptionContract(s, root, type, strike, expiration));
        } catch (DateTimeException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
