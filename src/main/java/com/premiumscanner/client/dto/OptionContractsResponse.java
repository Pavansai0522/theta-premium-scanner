package com.premiumscanner.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OptionContractsResponse(@JsonProperty("option_contracts") List<OptionContractDto> optionContracts,
                                      @JsonProperty("next_page_token") String nextPageToken) {
}
