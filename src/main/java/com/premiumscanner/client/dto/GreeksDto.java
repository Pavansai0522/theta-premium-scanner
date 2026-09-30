package com.premiumscanner.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GreeksDto(Double delta, Double gamma, Double theta, Double vega, Double rho) {
}
