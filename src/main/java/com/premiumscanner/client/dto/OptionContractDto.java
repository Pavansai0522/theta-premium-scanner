package com.premiumscanner.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Trading API contract. Alpaca serialises numeric fields such as strike and open interest as strings. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OptionContractDto(String symbol,
                                @JsonProperty("underlying_symbol") String underlyingSymbol,
                                String type,
                                @JsonProperty("strike_price") String strikePrice,
                                @JsonProperty("expiration_date") String expirationDate,
                                @JsonProperty("open_interest") String openInterest,
                                @JsonProperty("open_interest_date") String openInterestDate,
                                String status) {
}
