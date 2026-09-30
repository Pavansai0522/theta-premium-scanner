package com.premiumscanner.web;

import com.premiumscanner.domain.OptionChain;
import com.premiumscanner.domain.StrangleScanResult;
import com.premiumscanner.domain.StrategyParameterRequest;
import com.premiumscanner.domain.StrategyParameters;
import com.premiumscanner.service.OptionChainService;
import com.premiumscanner.service.ParameterResolver;
import com.premiumscanner.service.StrangleService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only REST API. There is intentionally no endpoint that creates orders. */
@RestController
@RequestMapping("/api/options")
public class OptionsApiController {

    private final OptionChainService chainService;
    private final StrangleService strangleService;
    private final ParameterResolver parameterResolver;

    public OptionsApiController(OptionChainService chainService, StrangleService strangleService,
                                ParameterResolver parameterResolver) {
        this.chainService = chainService;
        this.strangleService = strangleService;
        this.parameterResolver = parameterResolver;
    }

    /** GET /api/options/chain/SPY?minDte=0&maxDte=45&strikeWindowPct=0.15 */
    @GetMapping("/chain/{symbol}")
    public OptionChain chain(@PathVariable("symbol") String symbol,
                             @RequestParam(name = "minDte", required = false) Integer minDte,
                             @RequestParam(name = "maxDte", required = false) Integer maxDte,
                             @RequestParam(name = "strikeWindowPct", required = false) Double strikeWindowPct) {
        return chainService.chain(symbol, minDte, maxDte, strikeWindowPct);
    }

    /** GET /api/options/strangle/SPY?minDelta=0.05&maxDelta=0.15&minTheta=0.015&minDte=7&maxDte=45&... */
    @GetMapping("/strangle/{symbol}")
    public StrangleScanResult strangle(@PathVariable("symbol") String symbol,
                                       @ModelAttribute StrategyParameterRequest request) {
        return strangleService.scan(symbol, request);
    }

    /** GET /api/options/parameters/defaults - the configured defaults, for API clients building forms. */
    @GetMapping("/parameters/defaults")
    public StrategyParameters defaults() {
        return parameterResolver.defaults();
    }
}
