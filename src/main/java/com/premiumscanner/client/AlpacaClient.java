package com.premiumscanner.client;

import com.premiumscanner.client.dto.OptionContractDto;
import com.premiumscanner.client.dto.OptionContractsResponse;
import com.premiumscanner.client.dto.OptionSnapshotDto;
import com.premiumscanner.client.dto.OptionSnapshotsResponse;
import com.premiumscanner.client.dto.StockSnapshotDto;
import com.premiumscanner.config.AlpacaProperties;
import com.premiumscanner.domain.OptionQuery;
import com.premiumscanner.exception.AlpacaApiException;
import com.premiumscanner.exception.ConfigurationException;
import com.premiumscanner.exception.PaginationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Thin, stateless HTTP client for the three Alpaca endpoints the scanner needs.
 * <ul>
 *   <li>Market data: {@code GET /v1beta1/options/snapshots/{underlying}} - quotes, trades, Greeks, IV</li>
 *   <li>Market data: {@code GET /v2/stocks/{symbol}/snapshot} - underlying price</li>
 *   <li>Trading API: {@code GET /v2/options/contracts} - open interest (not present in snapshots)</li>
 * </ul>
 * Every paginated call follows {@code next_page_token} until exhausted, with a max-page guard and
 * repeated-token detection. 429/5xx/network errors are retried with exponential backoff honouring Retry-After.
 */
public class AlpacaClient {

    private static final Logger log = LoggerFactory.getLogger(AlpacaClient.class);
    static final String KEY_HEADER = "APCA-API-KEY-ID";
    static final String SECRET_HEADER = "APCA-API-SECRET-KEY";
    private static final Pattern MESSAGE = Pattern.compile("\"message\"\\s*:\\s*\"([^\"]*)\"");

    private final RestClient restClient;
    private final AlpacaProperties props;
    private final Sleeper sleeper;

    public AlpacaClient(RestClient restClient, AlpacaProperties props, Sleeper sleeper) {
        this.restClient = restClient;
        this.props = props;
        this.sleeper = sleeper;
    }

    public String optionsFeed() {
        return props.optionsFeed();
    }

    /** All option snapshots for the underlying that match the query, across every page. */
    public Map<String, OptionSnapshotDto> getOptionSnapshots(String underlying, OptionQuery query) {
        String operation = "option snapshots for " + underlying;
        Map<String, OptionSnapshotDto> all = new LinkedHashMap<>();
        paginate(operation, token -> {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("feed", props.optionsFeed());
            params.put("limit", String.valueOf(props.pageLimit()));
            putQuery(params, query);
            if (token != null) params.put("page_token", token);
            URI uri = uri(props.dataBaseUrl(), "/v1beta1/options/snapshots/" + encodeSegment(underlying), params);
            OptionSnapshotsResponse page = execute(operation, uri, OptionSnapshotsResponse.class);
            if (page == null) return null;
            if (page.snapshots() != null) {
                page.snapshots().forEach((symbol, snapshot) -> {
                    if (symbol != null && snapshot != null) all.put(symbol, snapshot);
                });
            }
            return page.nextPageToken();
        });
        return all;
    }

    /** Active contracts (used for open interest), across every page. */
    public List<OptionContractDto> getOptionContracts(String underlying, OptionQuery query) {
        String operation = "option contracts for " + underlying;
        List<OptionContractDto> all = new ArrayList<>();
        paginate(operation, token -> {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("underlying_symbols", underlying);
            params.put("status", "active");
            params.put("limit", String.valueOf(props.contractsPageLimit()));
            putQuery(params, query);
            if (token != null) params.put("page_token", token);
            URI uri = uri(props.tradingBaseUrl(), "/v2/options/contracts", params);
            OptionContractsResponse page = execute(operation, uri, OptionContractsResponse.class);
            if (page == null) return null;
            if (page.optionContracts() != null) all.addAll(page.optionContracts());
            return page.nextPageToken();
        });
        return all;
    }

    public StockSnapshotDto getStockSnapshot(String symbol) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("feed", props.stockFeed());
        URI uri = uri(props.dataBaseUrl(), "/v2/stocks/" + encodeSegment(symbol) + "/snapshot", params);
        return execute("stock snapshot for " + symbol, uri, StockSnapshotDto.class);
    }

    // ------------------------------------------------------------------ internals

    /** Calls fetchPage(token) until it returns no token. Guards against loops and runaway paging. */
    void paginate(String operation, Function<String, String> fetchPage) {
        String token = null;
        Set<String> seen = new HashSet<>();
        int pages = 0;
        do {
            if (++pages > props.maxPages()) {
                throw new PaginationException("Stopped after " + props.maxPages() + " pages of " + operation
                        + "; narrow the DTE or strike range");
            }
            String next = fetchPage.apply(token);
            token = next == null || next.isBlank() ? null : next;
            if (token != null && !seen.add(token)) {
                throw new PaginationException("Alpaca returned the same page token twice for " + operation);
            }
        } while (token != null);
        log.debug("{}: fetched {} page(s)", operation, pages);
    }

    private <T> T execute(String operation, URI uri, Class<T> type) {
        requireCredentials();
        int attempt = 0;
        while (true) {
            try {
                return restClient.get()
                        .uri(uri)
                        .header(KEY_HEADER, props.apiKey())
                        .header(SECRET_HEADER, props.apiSecret())
                        .accept(MediaType.APPLICATION_JSON)
                        .exchange((request, response) -> readResponse(operation, response, type));
            } catch (AlpacaApiException e) {
                if (!e.isRetryable() || attempt >= props.maxRetries()) throw e;
                Duration wait = backoff(attempt, e.getRetryAfter());
                log.warn("{} failed with {} (attempt {}/{}); retrying in {} ms",
                        operation, e.getUpstreamStatus(), attempt + 1, props.maxRetries() + 1, wait.toMillis());
                sleeper.sleep(wait);
                attempt++;
            } catch (ResourceAccessException e) {
                if (attempt >= props.maxRetries()) throw AlpacaApiException.network(operation, e);
                Duration wait = backoff(attempt, null);
                log.warn("{} network error (attempt {}/{}): {}; retrying in {} ms",
                        operation, attempt + 1, props.maxRetries() + 1, e.getMessage(), wait.toMillis());
                sleeper.sleep(wait);
                attempt++;
            }
        }
    }

    private <T> T readResponse(String operation, RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response,
                               Class<T> type) throws IOException {
        HttpStatusCode status = response.getStatusCode();
        if (status.is2xxSuccessful()) {
            return response.bodyTo(type);
        }
        String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
        Duration retryAfter = parseRetryAfter(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
        throw AlpacaApiException.fromStatus(status.value(), operation, extractMessage(body), retryAfter);
    }

    Duration backoff(int attempt, Duration retryAfter) {
        Duration max = props.maxBackoff();
        if (retryAfter != null && !retryAfter.isNegative()) {
            return retryAfter.compareTo(max.multipliedBy(3)) > 0 ? max.multipliedBy(3) : retryAfter;
        }
        long millis = props.initialBackoff().toMillis() * (1L << Math.min(attempt, 10));
        return Duration.ofMillis(Math.min(millis, max.toMillis()));
    }

    private void requireCredentials() {
        if (!props.hasCredentials()) {
            throw new ConfigurationException(
                    "Alpaca credentials are not configured. Set the ALPACA_API_KEY and ALPACA_API_SECRET environment variables.");
        }
    }

    private static void putQuery(Map<String, String> params, OptionQuery q) {
        if (q == null) return;
        if (q.type() != null) params.put("type", q.type().alpacaValue());
        if (q.expirationFrom() != null) params.put("expiration_date_gte", q.expirationFrom().toString());
        if (q.expirationTo() != null) params.put("expiration_date_lte", q.expirationTo().toString());
        if (q.strikeFrom() != null) params.put("strike_price_gte", q.strikeFrom().toPlainString());
        if (q.strikeTo() != null) params.put("strike_price_lte", q.strikeTo().toPlainString());
    }

    /** Values are strictly encoded (page tokens are base64 and may contain '+', '/' or '='). */
    static URI uri(String baseUrl, String path, Map<String, String> params) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(baseUrl + path);
        params.forEach((k, v) -> builder.queryParam(k, UriUtils.encode(v, StandardCharsets.UTF_8)));
        return builder.build(true).toUri();
    }

    private static String encodeSegment(String value) {
        return UriUtils.encodePathSegment(value, StandardCharsets.UTF_8);
    }

    private static Duration parseRetryAfter(String header) {
        if (header == null || header.isBlank()) return null;
        try {
            return Duration.ofSeconds(Long.parseLong(header.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String extractMessage(String body) {
        if (body == null || body.isBlank()) return "";
        Matcher m = MESSAGE.matcher(body);
        if (m.find()) return m.group(1);
        return body.length() > 200 ? body.substring(0, 200) + "..." : body;
    }
}
