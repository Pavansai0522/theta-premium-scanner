package com.premiumscanner.config;

import com.premiumscanner.client.AlpacaClient;
import com.premiumscanner.client.Sleeper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
public class AppConfig {

    /** US option expirations and DTE are defined in exchange time, not server time. */
    public static final ZoneId MARKET_ZONE = ZoneId.of("America/New_York");

    @Bean
    public Clock marketClock() {
        return Clock.system(MARKET_ZONE);
    }

    @Bean
    public AlpacaClient alpacaClient(RestClient.Builder builder, AlpacaProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(properties.readTimeout());
        RestClient restClient = builder.clone().requestFactory(factory).build();
        return new AlpacaClient(restClient, properties, Sleeper.THREAD);
    }
}
