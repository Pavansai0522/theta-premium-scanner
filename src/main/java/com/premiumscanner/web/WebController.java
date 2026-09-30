package com.premiumscanner.web;

import com.premiumscanner.config.ScannerProperties;
import com.premiumscanner.domain.OptionChain;
import com.premiumscanner.domain.StrangleScanResult;
import com.premiumscanner.domain.StrategyParameterRequest;
import com.premiumscanner.domain.StrategyParameters;
import com.premiumscanner.exception.ScannerException;
import com.premiumscanner.service.OptionChainService;
import com.premiumscanner.service.ParameterResolver;
import com.premiumscanner.service.StrangleService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Server-rendered UI (Thymeleaf). Uses the same services as the REST API. */
@Controller
public class WebController {

    private final OptionChainService chainService;
    private final StrangleService strangleService;
    private final ParameterResolver parameterResolver;
    private final ScannerProperties scannerProperties;
    private final ViewHelper fmt;

    public WebController(OptionChainService chainService, StrangleService strangleService,
                         ParameterResolver parameterResolver, ScannerProperties scannerProperties, ViewHelper fmt) {
        this.chainService = chainService;
        this.strangleService = strangleService;
        this.parameterResolver = parameterResolver;
        this.scannerProperties = scannerProperties;
        this.fmt = fmt;
    }

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("symbol", "");
        model.addAttribute("params", safeDefaults(model));
        commonAttributes(model);
        return "index";
    }

    @GetMapping("/scan")
    public String scan(@RequestParam(name = "symbol", required = false) String symbol,
                       @ModelAttribute StrategyParameterRequest request, BindingResult binding, Model model) {
        commonAttributes(model);
        String cleanSymbol = symbol == null ? "" : symbol.trim().toUpperCase();
        model.addAttribute("symbol", cleanSymbol);

        if (binding.hasErrors()) {
            model.addAttribute("params", safeDefaults(model));
            model.addAttribute("error", "Some parameters were not numbers: " + binding.getFieldErrors().stream()
                    .map(f -> f.getField()).toList() + ". Defaults were used instead.");
            return "index";
        }
        StrategyParameters params;
        try {
            params = parameterResolver.resolve(request);
        } catch (ScannerException e) {
            model.addAttribute("params", safeDefaults(model));
            model.addAttribute("error", e.getMessage());
            return "index";
        }
        model.addAttribute("params", params);
        if (cleanSymbol.isEmpty()) {
            model.addAttribute("error", "Enter an underlying symbol such as SPY.");
            return "index";
        }

        LinkedHashSet<String> warnings = new LinkedHashSet<>();
        OptionChain chain = null;
        try {
            chain = chainService.chain(cleanSymbol, 0, params.maxDte(), null);
            model.addAttribute("chain", chain);
            warnings.addAll(chain.warnings());
            model.addAttribute("spot", chain.underlyingPrice());
            model.addAttribute("priceNote", chain.underlyingPriceSource() + " at " + fmt.time(chain.underlyingPriceTime()));
        } catch (ScannerException e) {
            model.addAttribute("chainError", e.getMessage());
            if (!"NO_OPTIONS_AVAILABLE".equals(e.getCode())) {
                model.addAttribute("error", e.getMessage());
                return "index";
            }
        }
        try {
            StrangleScanResult scan = strangleService.scan(cleanSymbol, request);
            model.addAttribute("scan", scan);
            warnings.addAll(scan.warnings());
            if (chain == null) model.addAttribute("spot", scan.underlyingPrice());
        } catch (ScannerException e) {
            model.addAttribute("scanError", e.getMessage());
        }
        model.addAttribute("warnings", new ArrayList<>(warnings));
        return "index";
    }

    private void commonAttributes(Model model) {
        model.addAttribute("strikeWindowPct", scannerProperties.chain().strikeWindowPct());
        model.addAttribute("warnings", List.of());
    }

    private StrategyParameters safeDefaults(Model model) {
        try {
            return parameterResolver.defaults();
        } catch (ScannerException e) {
            model.addAttribute("error", "Scanner defaults are misconfigured: " + e.getMessage());
            return null;
        }
    }
}
