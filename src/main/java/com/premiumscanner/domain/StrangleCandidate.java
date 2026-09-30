package com.premiumscanner.domain;

import java.util.List;

/** A ranked short strangle: one OTM put and one OTM call on the same expiration. Score is 0..100. */
public record StrangleCandidate(String id, int rank, double score, ScoreBreakdown scoreBreakdown,
                                StrangleMetrics metrics, OptionSnapshot shortPut, OptionSnapshot shortCall,
                                List<String> flags) {

    public StrangleCandidate {
        flags = flags == null ? List.of() : List.copyOf(flags);
    }

    public StrangleCandidate withRank(int newRank) {
        return new StrangleCandidate(id, newRank, score, scoreBreakdown, metrics, shortPut, shortCall, flags);
    }
}
