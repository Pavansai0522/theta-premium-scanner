package com.premiumscanner.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record StrangleScanResult(String symbol, BigDecimal underlyingPrice, Instant underlyingPriceTime,
                                 String feed, StrategyParameters parameters,
                                 int callsEvaluated, int putsEvaluated,
                                 int eligibleCalls, int eligiblePuts, int pairsEvaluated,
                                 Map<RejectionReason, Integer> callRejections,
                                 Map<RejectionReason, Integer> putRejections,
                                 List<StrangleCandidate> candidates,
                                 List<OptionSnapshot> eligibleCallLegs, List<OptionSnapshot> eligiblePutLegs,
                                 List<String> warnings, Instant generatedAt) {
}
