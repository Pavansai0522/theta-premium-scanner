package com.premiumscanner.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Alpaca connectivity settings. Credentials come from ALPACA_API_KEY / ALPACA_API_SECRET
 * (see application.yml) and are never logged: {@link #toString()} masks them.
 */
@ConfigurationProperties(prefix = "alpaca")
public record AlpacaProperties(
        String apiKey,
        String apiSecret,
        String dataBaseUrl,
        String tradingBaseUrl,
        String optionsFeed,
        String stockFeed,
        Integer pageLimit,
        Integer contractsPageLimit,
        Integer maxPages,
        Integer maxRetries,
        Duration initialBackoff,
        Duration maxBackoff,
        Duration connectTimeout,
        Duration readTimeout,
        Duration cacheTtl) {

    public AlpacaProperties {
        dataBaseUrl = stripTrailingSlash(defaultIfBlank(dataBaseUrl, "https://data.alpaca.markets"));
        tradingBaseUrl = stripTrailingSlash(defaultIfBlank(tradingBaseUrl, "https://paper-api.alpaca.markets"));
        optionsFeed = defaultIfBlank(optionsFeed, "indicative");
        stockFeed = defaultIfBlank(stockFeed, "iex");
        pageLimit = clamp(pageLimit, 1000, 1, 1000);
        contractsPageLimit = clamp(contractsPageLimit, 10000, 1, 10000);
        maxPages = clamp(maxPages, 200, 1, 10_000);
        maxRetries = clamp(maxRetries, 3, 0, 10);
        initialBackoff = initialBackoff == null ? Duration.ofMillis(500) : initialBackoff;
        maxBackoff = maxBackoff == null ? Duration.ofSeconds(10) : maxBackoff;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(30) : readTimeout;
        cacheTtl = cacheTtl == null ? Duration.ofSeconds(20) : cacheTtl;
    }

    public boolean hasCredentials() {
        return apiKey != null && !apiKey.isBlank() && apiSecret != null && !apiSecret.isBlank();
    }

    public boolean isIndicativeFeed() {
        return "indicative".equalsIgnoreCase(optionsFeed);
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static Integer clamp(Integer value, int fallback, int min, int max) {
        int v = value == null ? fallback : value;
        return Math.max(min, Math.min(max, v));
    }

    @Override
    public String toString() {
        return "AlpacaProperties[apiKey=" + mask(apiKey) + ", apiSecret=" + (apiSecret == null || apiSecret.isBlank() ? "<unset>" : "****")
                + ", dataBaseUrl=" + dataBaseUrl + ", tradingBaseUrl=" + tradingBaseUrl
                + ", optionsFeed=" + optionsFeed + ", stockFeed=" + stockFeed + "]";
    }

    private static String mask(String secret) {
        if (secret == null || secret.isBlank()) return "<unset>";
        return secret.length() <= 4 ? "****" : secret.substring(0, 4) + "****";
    }
}
