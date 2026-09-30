# Screenshots

Capture with the app running against Alpaca during market hours (09:30–16:00 ET), so quotes and Greeks are live.

| File | What to show |
|---|---|
| `01-spy-chain.png` | `/scan?symbol=SPY`: Section A with the first expiration expanded (spot line visible) |
| `02-spy-candidates.png` | Section B: funnel, candidate table, rank 1 details with the ladder |
| `03-spy-filtered.png` | "Filtered options" expanded: rejection counts and eligible legs |
| `04-qqq-candidates.png` | `/scan?symbol=QQQ` candidates + details |
| `05-aapl-candidates.png` | `/scan?symbol=AAPL` candidates + details |
| `06-api-strangle.png` | `curl` / browser JSON of `/api/options/strangle/SPY` |
| `07-error.png` | `/scan?symbol=ZZZZ` showing the invalid-symbol message |
