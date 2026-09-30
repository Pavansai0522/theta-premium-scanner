package com.premiumscanner.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record BarDto(@JsonProperty("o") Double open,
                     @JsonProperty("h") Double high,
                     @JsonProperty("l") Double low,
                     @JsonProperty("c") Double close,
                     @JsonProperty("v") Long volume,
                     @JsonProperty("t") String timestamp) {
}
