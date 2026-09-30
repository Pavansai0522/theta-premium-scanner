package com.premiumscanner.domain;

/** Each component is normalised to 0..1 before weighting; see README "Ranking methodology". */
public record ScoreBreakdown(double premium, double theta, double delta, double cushion,
                             double liquidity, double dteFit, double balance) {
}
