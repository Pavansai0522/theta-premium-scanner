package com.premiumscanner.domain;

import java.util.List;
import java.util.Map;

public record LegFilterResult(List<OptionSnapshot> eligibleCalls, List<OptionSnapshot> eligiblePuts,
                              Map<RejectionReason, Integer> callRejections,
                              Map<RejectionReason, Integer> putRejections,
                              int callsEvaluated, int putsEvaluated) {
}
