package com.premiumscanner.domain;

import java.math.BigDecimal;

/** One line of the two-sided chain: call and put sharing a strike (either side may be null). */
public record StrikeRow(BigDecimal strike, OptionSnapshot call, OptionSnapshot put,
                        boolean callInTheMoney, boolean putInTheMoney) {
}
