package com.premiumscanner.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Deterministic math for one call/put pair. Percent fields are fractions (0.046 = 4.6%).
 *
 * @param naturalCredit      bid + bid: what an immediate market sell would collect
 * @param expectedMove       1-sigma move to expiration: S * IV * sqrt(DTE/365); null without IV
 * @param putCushion         (S - lower breakeven) / expected move
 * @param callCushion        (upper breakeven - S) / expected move
 * @param probabilityOfProfit rough estimate 1 - |call delta| - |put delta| (delta as ITM probability proxy)
 * @param thetaPerDay        dollars of daily decay per strangle (|theta call| + |theta put|) * 100
 * @param positionDelta      delta of the SHORT position: -(call delta + put delta)
 */
public record StrangleMetrics(String underlying, BigDecimal underlyingPrice, LocalDate expiration, int dte,
                              BigDecimal putMid, BigDecimal callMid, BigDecimal totalPremium,
                              BigDecimal naturalCredit, BigDecimal premiumPerContract,
                              BigDecimal lowerBreakeven, BigDecimal upperBreakeven,
                              double putDistancePct, double callDistancePct,
                              double lowerBreakevenDistancePct, double upperBreakevenDistancePct,
                              Double impliedVolatility, Double expectedMove,
                              Double putCushion, Double callCushion,
                              double probabilityOfProfit, double thetaPerDay,
                              double positionDelta, Double positionGamma, Double positionVega) {
}
