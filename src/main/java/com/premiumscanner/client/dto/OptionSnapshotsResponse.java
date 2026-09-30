package com.premiumscanner.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/** GET /v1beta1/options/snapshots/{underlying}: snapshots keyed by OCC symbol, plus pagination token. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OptionSnapshotsResponse(Map<String, OptionSnapshotDto> snapshots,
                                      @JsonProperty("next_page_token") String nextPageToken) {
}
