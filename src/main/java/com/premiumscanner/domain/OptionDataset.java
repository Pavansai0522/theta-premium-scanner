package com.premiumscanner.domain;

import java.util.List;

/** Result of fetching and normalising a set of option snapshots. */
public record OptionDataset(List<OptionSnapshot> options, List<String> warnings, int expiredDropped, int unparseable) {

    public OptionDataset {
        options = List.copyOf(options);
        warnings = List.copyOf(warnings);
    }

    public boolean isEmpty() {
        return options.isEmpty();
    }
}
