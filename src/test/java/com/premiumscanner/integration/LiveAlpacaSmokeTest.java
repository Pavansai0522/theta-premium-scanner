package com.premiumscanner.integration;

import com.premiumscanner.domain.OptionChain;
import com.premiumscanner.domain.StrangleScanResult;
import com.premiumscanner.domain.StrategyParameterRequest;
import com.premiumscanner.service.OptionChainService;
import com.premiumscanner.service.StrangleService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hits the real Alpaca paper API. Excluded from the default build; run with
 * {@code ALPACA_API_KEY=... ALPACA_API_SECRET=... mvn verify -Plive}.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "ALPACA_API_KEY", matches = ".+")
@SpringBootTest
class LiveAlpacaSmokeTest {

    @Autowired
    OptionChainService chainService;
    @Autowired
    StrangleService strangleService;

    @Test
    void spyChainAndScanWorkAgainstRealAlpaca() {
        OptionChain chain = chainService.chain("SPY", 0, 14, 0.05);
        assertThat(chain.underlyingPrice()).isPositive();
        assertThat(chain.expirations()).isNotEmpty();

        StrangleScanResult scan = strangleService.scan("SPY", StrategyParameterRequest.empty());
        assertThat(scan.callsEvaluated() + scan.putsEvaluated()).isPositive();
    }
}
