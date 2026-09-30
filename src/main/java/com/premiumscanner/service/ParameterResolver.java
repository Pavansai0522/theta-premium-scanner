package com.premiumscanner.service;

import com.premiumscanner.config.ScannerProperties;
import com.premiumscanner.domain.StrategyParameterRequest;
import com.premiumscanner.domain.StrategyParameters;
import com.premiumscanner.exception.InvalidParameterException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Merges user input over configured defaults, then validates. Nothing is hard-coded here. */
@Component
public class ParameterResolver {

    private final ScannerProperties props;

    public ParameterResolver(ScannerProperties props) {
        this.props = props;
    }

    public StrategyParameters defaults() {
        return resolve(StrategyParameterRequest.empty());
    }

    public StrategyParameters resolve(StrategyParameterRequest request) {
        StrategyParameterRequest r = request == null ? StrategyParameterRequest.empty() : request;
        ScannerProperties.Defaults d = props.defaults();
        List<String> missing = new ArrayList<>();
        var minDelta = pick(r.minDelta(), d.minDelta(), "minDelta", missing);
        var maxDelta = pick(r.maxDelta(), d.maxDelta(), "maxDelta", missing);
        var minTheta = pick(r.minTheta(), d.minTheta(), "minTheta", missing);
        var minDte = pick(r.minDte(), d.minDte(), "minDte", missing);
        var maxDte = pick(r.maxDte(), d.maxDte(), "maxDte", missing);
        var minPremium = pick(r.minPremium(), d.minPremium(), "minPremium", missing);
        var maxSpread = pick(r.maxSpread(), d.maxSpread(), "maxSpread", missing);
        var minOi = pick(r.minOpenInterest(), d.minOpenInterest(), "minOpenInterest", missing);
        var minVolume = pick(r.minVolume(), d.minVolume(), "minVolume", missing);
        var minTotal = pick(r.minTotalPremium(), d.minTotalPremium(), "minTotalPremium", missing);
        var topN = pick(r.topN(), d.topN(), "topN", missing);
        if (!missing.isEmpty()) {
            throw new InvalidParameterException(missing.stream()
                    .map(name -> name + " is required (not supplied and no default configured under scanner.defaults)")
                    .toList());
        }
        return new StrategyParameters(minDelta, maxDelta, minTheta, minDte, maxDte, minPremium, maxSpread,
                minOi, minVolume, minTotal, topN).validate();
    }

    private static <T> T pick(T requested, T fallback, String name, List<String> missing) {
        T value = requested != null ? requested : fallback;
        if (value == null) missing.add(name);
        return value;
    }
}
