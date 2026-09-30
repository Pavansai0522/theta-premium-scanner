package com.premiumscanner.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record StockSnapshotDto(String symbol, TradeDto latestTrade, QuoteDto latestQuote,
                               BarDto dailyBar, BarDto prevDailyBar) {
}
