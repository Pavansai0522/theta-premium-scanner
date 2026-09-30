package com.premiumscanner.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TradeDto(@JsonProperty("p") Double price,
                       @JsonProperty("s") Long size,
                       @JsonProperty("t") String timestamp) {
}
