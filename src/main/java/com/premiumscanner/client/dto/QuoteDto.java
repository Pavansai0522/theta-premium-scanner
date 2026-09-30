package com.premiumscanner.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Alpaca quote: bp/bs = bid price/size, ap/as = ask price/size, t = RFC-3339 timestamp. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record QuoteDto(@JsonProperty("bp") Double bidPrice,
                       @JsonProperty("bs") Long bidSize,
                       @JsonProperty("ap") Double askPrice,
                       @JsonProperty("as") Long askSize,
                       @JsonProperty("t") String timestamp) {
}
