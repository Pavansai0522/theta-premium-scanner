package com.premiumscanner.domain;

/** Why a leg was excluded from strangle selection. Checked in declaration order; the first failure is recorded. */
public enum RejectionReason {
    EXPIRED("Expired"),
    DTE_OUT_OF_RANGE("DTE outside range"),
    NOT_OUT_OF_THE_MONEY("In or at the money"),
    MISSING_QUOTE("No ask / no quote"),
    ZERO_BID("Zero bid"),
    INVALID_QUOTE("Crossed or invalid quote"),
    MISSING_GREEKS("Delta/Theta unavailable"),
    INVALID_GREEKS("Greeks inconsistent with option type"),
    DELTA_OUT_OF_RANGE("Delta outside range"),
    THETA_BELOW_MIN("Theta below minimum"),
    SPREAD_TOO_WIDE("Bid/ask spread too wide"),
    PREMIUM_TOO_LOW("Premium below minimum"),
    OPEN_INTEREST_UNAVAILABLE("Open interest unavailable"),
    OPEN_INTEREST_TOO_LOW("Open interest too low"),
    VOLUME_TOO_LOW("Volume too low");

    private final String label;

    RejectionReason(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
