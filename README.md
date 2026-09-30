# Theta Premium Scanner

A Spring Boot + Thymeleaf application that loads a full option chain from the **Alpaca** API and finds
**deep out-of-the-money short strangles**. It sells an OTM put and an OTM call on the same expiration and
collects the premium. It ranks candidates by premium, time decay, probability, breakeven cushion,
liquidity and DTE.

> **Analysis only.** The application never places, stages or simulates orders. There is no order endpoint.

---

## 1. Quick start

**Requirements:** Java 17+, Maven 3.9+, and an Alpaca account (a free paper account works).

```bash
# 1. Credentials come ONLY from the environment (never committed, never logged)
export ALPACA_API_KEY=your-paper-key-id
export ALPACA_API_SECRET=your-paper-secret
# optional
export ALPACA_OPTIONS_FEED=indicative   # free feed; use "opra" with an OPRA subscription
export ALPACA_STOCK_FEED=iex            # free feed; "sip" requires a subscription

# 2. Build and run all tests
mvn verify

# 3. Start
mvn spring-boot:run
# open http://localhost:8080   (e.g. http://localhost:8080/scan?symbol=SPY)
```

On Windows PowerShell, use `$env:ALPACA_API_KEY="..."` instead of `export`. A `.env.example` file is included for reference.

| Variable | Required | Default | Purpose |
|---|---|---|---|
| `ALPACA_API_KEY` | yes | – | Sent as `APCA-API-KEY-ID` |
| `ALPACA_API_SECRET` | yes | – | Sent as `APCA-API-SECRET-KEY` |
| `ALPACA_OPTIONS_FEED` | no | `indicative` | `indicative` (free) or `opra` |
| `ALPACA_STOCK_FEED` | no | `iex` | `iex` (free) or `sip` |
| `ALPACA_DATA_URL` / `ALPACA_TRADING_URL` | no | Alpaca production data / paper trading | Override hosts (the integration test uses this) |
| `PORT` | no | `8080` | HTTP port |

If the keys are missing, the application still starts. Every scan then returns a clear
`503 ALPACA_NOT_CONFIGURED` message instead of crashing on startup.

---

## 2. Architecture

```
 Browser (Thymeleaf UI)            REST clients
        │  GET /, /scan                 │  GET /api/options/...
        ▼                               ▼
   WebController                OptionsApiController ── ApiExceptionHandler (uniform JSON errors)
        └───────────────┬───────────────┘
                        ▼
   ┌───────────── Strategy layer ─────────────────────────────────────────┐
   │ StrangleService      orchestrates: fetch OTM legs → filter → pair →  │
   │                      rank → top N                                    │
   │ OptionFilterService  leg rules (OTM, delta, theta, DTE, quote,       │
   │                      liquidity) with a recorded rejection reason     │
   │ StrangleRankingService   multi-factor score (0–100)                  │
   │ OptionChainService   two-sided chain grouped by expiration           │
   │ OptionCalculationService pure math: mid, premium, breakevens,        │
   │                      distance, DTE, expected move                    │
   │ ParameterResolver    request values over configured defaults,        │
   │                      then validation                                 │
   └──────────────────────────────┬───────────────────────────────────────┘
                                  ▼
   AlpacaOptionService   "Option processor": maps Alpaca DTOs → domain, merges
                       open interest, drops expired/unparseable contracts,
                       records data issues, short TTL cache
                                  ▼
   AlpacaClient        HTTP, auth headers, pagination (next_page_token),
                       retry/backoff (429/5xx/network), error mapping
                                  ▼
   Alpaca: GET /v1beta1/options/snapshots/{underlying}   quotes, trades, Greeks, IV
           GET /v2/stocks/{symbol}/snapshot              underlying price
           GET /v2/options/contracts (Trading API)       open interest
```

**Package layout** (`com.premiumscanner`): `client` (HTTP + DTOs), `config`, `domain`
(immutable records), `exception`, `service`, `web`.

### Design decisions worth knowing
- **Three Alpaca endpoints, not one.** Option snapshots do not include open interest or the
  underlying price. OI is read from the Trading API contracts endpoint and merged by OCC symbol.
  If that call fails (for example with a 403), the scan still runs, shows a visible warning, and
  rejects legs when a minimum OI is set.
- **Server-side filtering.** Expiration and strike ranges are pushed down as query parameters.
  The scanner requests only OTM strikes: calls with `strike_price_gte=spot` and puts with
  `strike_price_lte=spot`. This roughly halves the pages pulled and protects the rate limit.
- **Money is `BigDecimal`.** Premiums and breakevens are exact (0.375 + 0.300 = 0.675, not
  0.67499999). Greeks and scores use `double`.
- **Exchange time.** DTE is counted in calendar days in `America/New_York`. A contract counts as
  expired after 16:15 ET on its expiration date.
- **Freshest underlying price.** The newer of the latest trade and the quote midpoint is used,
  falling back to the daily close and then the previous close. The source and timestamp are shown
  in the UI.
- **Short cache (20 s, configurable, 0 disables).** The chain view and the scanner see the same
  snapshot, and repeated clicks don't burn rate limit. It is kept short because quotes move.
- **One bad contract never fails a chain.** Missing quotes or Greeks, negative prices and
  unparseable symbols are recorded per contract as data issues. The chain still shows those rows;
  the scanner excludes them.

---

## 3. REST API

All endpoints are `GET`, read-only, and return JSON. Parameters left out of a request use the
configured defaults (section 5).

### `GET /api/options/chain/{symbol}`
| Param | Default | Meaning |
|---|---|---|
| `minDte` | 0 | first expiration to include |
| `maxDte` | `scanner.chain.default-max-dte` (45) | last expiration |
| `strikeWindowPct` | `scanner.chain.strike-window-pct` (0.15) | strikes within ±15% of spot; `0` = every strike |

```bash
curl "http://localhost:8080/api/options/chain/SPY?maxDte=30&strikeWindowPct=0.10"
```
Response (abridged, illustrative values):
```json
{
  "symbol": "SPY", "underlyingPrice": 650.0, "underlyingPriceSource": "latest trade",
  "feed": "indicative", "totalContracts": 2412, "contractsMissingGreeks": 37,
  "expirations": [{
    "expiration": "2026-08-21", "dte": 12, "monthly": true, "multiplier": 100, "spotRowIndex": 25,
    "rows": [{ "strike": 680, "callInTheMoney": false, "putInTheMoney": true,
               "call": { "contract": {...}, "quote": {"bid":0.35,"ask":0.40,"mid":0.375,"spread":0.05},
                         "greeks": {"delta":0.08,"theta":-0.018,"impliedVolatility":0.15},
                         "volume": 1200, "openInterest": 2500, "dte": 12, "dataIssues": [] },
               "put": {...} }]
  }],
  "warnings": []
}
```

### `GET /api/options/strangle/{symbol}`
| Param | Default | Meaning |
|---|---|---|
| `minDelta` / `maxDelta` | 0.05 / 0.15 | absolute delta band applied to both legs (put delta −0.15…−0.05) |
| `minTheta` | 0.015 | minimum **\|theta\|** per leg (daily decay per share) |
| `minDte` / `maxDte` | 7 / 45 | expiration window |
| `minPremium` | 0.20 | minimum **mid per leg** |
| `maxSpread` | 0.20 | maximum ask − bid per leg |
| `minOpenInterest` | 500 | per leg |
| `minVolume` | 0 | per leg (session volume) |
| `minTotalPremium` | 0.00 | minimum combined credit (call mid + put mid) |
| `topN` | 25 | number of candidates returned (1–200) |

```bash
curl "http://localhost:8080/api/options/strangle/SPY?minDelta=0.05&maxDelta=0.15&minTheta=0.015&minDte=7&maxDte=45&minPremium=0.20&maxSpread=0.20&minOpenInterest=500"
```
Response (abridged, illustrative values):
```json
{
  "symbol": "SPY", "underlyingPrice": 650.0, "feed": "indicative",
  "parameters": { "minDelta": 0.05, "maxDelta": 0.15, ... },
  "callsEvaluated": 412, "putsEvaluated": 530, "eligibleCalls": 38, "eligiblePuts": 51, "pairsEvaluated": 322,
  "callRejections": { "DELTA_OUT_OF_RANGE": 301, "THETA_BELOW_MIN": 40, ... },
  "putRejections":  { ... },
  "candidates": [{
    "id": "SPY260821P00625000_SPY260821C00680000", "rank": 1, "score": 81.2,
    "scoreBreakdown": { "premium": 0.9, "theta": 0.8, "delta": 0.6, "cushion": 1.0, "liquidity": 0.82, "dteFit": 0.63, "balance": 0.93 },
    "metrics": {
      "expiration": "2026-08-21", "dte": 12, "putMid": 0.30, "callMid": 0.375,
      "totalPremium": 0.675, "premiumPerContract": 67.50, "naturalCredit": 0.63,
      "lowerBreakeven": 624.325, "upperBreakeven": 680.675,
      "putDistancePct": 0.0385, "callDistancePct": 0.0462,
      "impliedVolatility": 0.19, "expectedMove": 22.39, "putCushion": 1.15, "callCushion": 1.37,
      "probabilityOfProfit": 0.85, "thetaPerDay": 3.40, "positionDelta": -0.01
    },
    "shortPut": { ... }, "shortCall": { ... }, "flags": []
  }],
  "eligiblePutLegs": [...], "eligibleCallLegs": [...], "warnings": [...]
}
```

### `GET /api/options/parameters/defaults`
Returns the resolved defaults, which is handy for clients that build forms.

### Errors
Every error has the same shape:
```json
{ "timestamp": "...", "status": 429, "code": "ALPACA_RATE_LIMITED",
  "message": "Alpaca rate limit exceeded ...", "path": "/api/options/strangle/SPY", "retryAfterSeconds": 3 }
```

| Situation | HTTP | `code` |
|---|---|---|
| Malformed ticker, bad or inconsistent parameters, non-numeric value | 400 | `INVALID_PARAMETERS` |
| Alpaca 400/422 (bad request / invalid symbol) | 400 | `ALPACA_BAD_REQUEST` |
| Unknown ticker (no stock data) | 404 | `INVALID_SYMBOL` |
| No option contracts in range | 404 | `NO_OPTIONS_AVAILABLE` |
| Alpaca 429, still failing after retries | 429 + `Retry-After` | `ALPACA_RATE_LIMITED` |
| Alpaca 401: **our** credentials are wrong | 502 | `ALPACA_AUTH_FAILED` |
| Alpaca 403: feed or subscription not allowed (e.g. `opra` without a plan) | 502 | `ALPACA_FORBIDDEN` |
| Alpaca 5xx, still failing after retries | 502 | `ALPACA_UNAVAILABLE` |
| No usable underlying price | 502 | `UNDERLYING_PRICE_UNAVAILABLE` |
| Pagination loop or runaway paging | 502 | `PAGINATION_ERROR` |
| Alpaca unreachable (network) | 503 | `ALPACA_UNREACHABLE` |
| Keys not set | 503 | `ALPACA_NOT_CONFIGURED` |
| Anything unexpected | 500 | `INTERNAL_ERROR` (details only in server logs) |

An Alpaca 401 is returned as **502**, not 401. The caller of *our* API is not unauthenticated;
our server's upstream credentials are wrong. Returning 401 would mislead clients.

**Resilience:** 429, 5xx and network errors are retried up to `alpaca.max-retries` (3) times.
The wait uses exponential backoff (500 ms, 1 s, 2 s…, capped at 10 s) and honours `Retry-After`
when Alpaca sends it. 400/401/403/404 errors are never retried. Pagination follows
`next_page_token` until it is empty, stops after `alpaca.max-pages`, and fails fast if a token
repeats.

---

## 4. The UI

Go to `http://localhost:8080`, type a ticker or click a quick-pick chip (SPY, QQQ, AAPL, NVDA,
TSLA), adjust the parameters if needed, and press **Scan**.

**Section A: option chain**
- The layout is two-sided: **Calls (Ask | Theta | Bid) | Strike | Puts (Bid | Theta | Ask)**. Asks are red and bids are green.
- Rows alternate in shading. In-the-money cells have a warm tint.
- A blue divider line marks the current underlying price.
- Each expiration has a header row showing the date, a `(W)` tag for non-monthlies, the 100 multiplier, and DTE. Click the row (or press Enter) to expand or collapse it; *Expand all* and *Collapse all* are also available.
- A dash means Alpaca returned no value for that field. Hovering a missing theta explains why.

**Section B: recommended short strangles**
- The **funnel** shows OTM legs fetched, legs passing the filters, pairs scored, and pairs shown.
- **Filtered options** (expandable) shows how many legs each rule excluded, per side, plus sortable tables of every eligible put and call.
- The **candidate table** is sortable: click any header.
- **Selecting a row** opens its details and outlines its two legs in the chain above, expanding that expiration if needed. Details include:
  - both legs' Greeks, quotes, IV, volume, OI and distance
  - total and per-contract premium, natural credit (bids only), breakevens with distance
  - expected move, cushion, POP estimate, theta dollars per day, position Greeks
  - a **price ladder** of strikes, breakevens, spot, ±1 expected move, and the profit zone
  - the **score breakdown** with each factor and its weight
  - risk flags

Screenshots are in `docs/screenshots/`.

---

## 5. Filtering methodology

Every value is configurable, with defaults in `application.yml` under `scanner.defaults`, and
every value can be overridden per request. No strategy value is hard-coded in Java.

Each leg is tested in this order. The **first** failing rule is recorded and shown in the UI and API.

1. **Expired / DTE window**: expired contracts are dropped. `minDte ≤ DTE ≤ maxDte`. 0DTE is only possible if `minDte=0`, and then it triggers a warning.
2. **Out of the money**: calls need `strike > spot`, puts need `strike < spot`. At-the-money is excluded.
3. **Quote sanity**: an ask must exist, the **bid must be > 0** (a zero bid cannot be sold), and the quote must not be crossed (`ask ≥ bid`).
4. **Greeks present and consistent**: delta and theta must exist. Call delta must be in [0, 1], put delta in [−1, 0], and theta ≤ 0. Contracts violating this are not trusted.
5. **Delta**: `minDelta ≤ |delta| ≤ maxDelta`, applied symmetrically to both sides.
6. **Theta**: `|theta| ≥ minTheta`.
7. **Spread**: `ask − bid ≤ maxSpread`.
8. **Premium**: `mid ≥ minPremium` per leg.
9. **Open interest**: `OI ≥ minOpenInterest`. A missing OI is its own reason when a minimum is set.
10. **Volume**: `volume ≥ minVolume`.

Surviving puts and calls are paired **only within the same expiration**. To keep pairing bounded,
the top `scanner.pairing.max-legs-per-side` legs per side per expiration (default 25) are kept,
ordered by `mid × (0.25 + liquidity)`. Pairs below `minTotalPremium` are dropped.

### Interpreting theta
Alpaca reports theta **per share, per calendar day**, and it is negative for a long option: the
value it loses in a day, all else equal. A premium seller earns that decay, so the scanner compares
**|theta|** against `minTheta`, and reports `thetaPerDay = (|θcall| + |θput|) × 100`, the dollars
of decay per strangle per day.

Higher theta is not automatically better. Theta rises as strikes move closer to spot and as
expiration approaches, and so do gamma risk and assignment risk. That is why theta is only one
factor in the ranking.

---

## 6. Ranking methodology

Each pair gets seven factor scores in [0, 1], a weighted sum, and a final score scaled to 0–100.
Weights live in `scanner.weights` and are normalised, so they do not need to sum to 1.

| Factor | Default weight | How it is scored |
|---|---|---|
| **Premium** | 25% | total mid credit, min–max normalised across all candidates |
| **Theta decay** | 15% | \|θc\|+\|θp\|, min–max normalised |
| **Delta safety** | 15% | POP estimate `1 − |Δc| − |Δp|`, min–max normalised |
| **Breakeven cushion** | 20% | `min(putCushion, callCushion) / target`, capped at 1 |
| **Liquidity** | 15% | weaker leg of `0.40·spread + 0.35·OI + 0.25·volume` |
| **DTE fit** | 5% | 1.0 at the middle of the DTE window, 0.5 at the edges |
| **Delta balance** | 5% | `1 − | |Δc| − |Δp| | / (|Δc| + |Δp|)` |

**Why these choices**
- **Premium, theta and delta safety conflict with each other.** Lower delta is safer but pays
  less; closer strikes pay more but carry more risk. Normalising all three across the current
  candidate set and blending them means neither "lowest delta" nor "biggest credit" wins
  automatically. A unit test checks this: a far-OTM, thin, illiquid pair loses to a liquid,
  well-paid pair whose breakevens are still outside one expected move.
- **Cushion is measured in expected moves, not percent.** A 4% distance is generous for SPY and
  tight for TSLA.
  - `EM = S · IV · √(DTE/365)`, using the **at-the-money IV** of that expiration (the nearest OTM
    call and put, averaged), because the wings carry skew.
  - `putCushion = (S − lowerBE) / EM` and `callCushion = (upperBE − S) / EM`. The weaker side counts.
  - The score saturates at `scanner.pairing.target-cushion` (1.25 EM). Beyond that, extra distance
    only costs premium, so it earns nothing extra. This is the "reasonable distance" balance.
  - Without IV, the factor is neutral (0.5) and the candidate is flagged.
- **Liquidity is absolute and uses the weaker leg.** You have to fill both legs.
  - The spread is judged relative to mid: 0.05 wide is fine on a 2.00 option and terrible on a 0.10 one.
  - OI and volume use log scales that saturate at 10,000 and 5,000.
  - An absolute score means a universally illiquid set cannot look good just by comparison.
- **The DTE fit is gentle.** Every DTE inside your window is acceptable; the middle is slightly preferred.
- **Delta balance** favours positions that start close to delta-neutral.

Ties are broken by higher premium. Each candidate also carries **flags**:
- a breakeven inside one expected move
- a spread wider than 25% of mid
- 0DTE, or expiration within 3 days
- IV or OI unavailable

---

## 7. Calculations

| Metric | Formula | Spec example |
|---|---|---|
| Mid | (bid + ask) / 2 | put 0.300, call 0.375 |
| Total premium | call mid + put mid | 0.675 |
| Premium per contract | total × 100 | $67.50 |
| Lower breakeven | put strike − total premium | 625 − 0.675 = **624.325** |
| Upper breakeven | call strike + total premium | 680 + 0.675 = **680.675** |
| Call distance | (call strike − spot) / spot | 30 / 650 = 4.62% |
| Put distance | (spot − put strike) / spot | 25 / 650 = 3.85% |
| Natural credit | call bid + put bid | 0.63 |
| Expected move (1σ) | spot × IV × √(DTE / 365) | 650 × 0.19 × √(12/365) ≈ 22.39 |
| POP estimate | 1 − \|Δcall\| − \|Δput\| | 0.85 |
| Theta $/day | (\|θc\| + \|θp\|) × 100 | 3.40 |
| Position delta (short) | −(Δcall + Δput) | −0.01 |

The POP estimate treats delta as a rough proxy for the probability of finishing in the money.
It is a screening heuristic, not a pricing model.

---

## 8. Testing

```bash
mvn verify                  # unit + integration tests (no network, no keys needed)
mvn verify -Plive           # also runs LiveAlpacaSmokeTest against real Alpaca (needs the env vars)
```

| Test class | Covers |
|---|---|
| `AlpacaClientTest` | success, auth headers and server-side filters; pagination with token encoding; empty response; 401 (no retry); 400 invalid symbol; 429 retry honouring Retry-After; 429 exhausted with exponential backoff; 5xx; repeated-token and runaway pagination; missing credentials; OI and stock snapshot parsing |
| `OccSymbolParserTest` | calls/puts, fractional strikes, adjusted roots, invalid symbols and dates |
| `OptionCalculationServiceTest` | mid, spread, **spec example 0.675 / 624.325 / 680.675**, distances, DTE, expiry cutoff, OTM/ITM, expected move, full pair metrics |
| `OptionFilterServiceTest` | call/put separation, ITM/ATM exclusion, delta band both sides (inclusive), \|theta\|, DTE, 0DTE, zero bid, crossed/missing quotes, spread, premium, missing/inconsistent Greeks, OI, volume |
| `StrangleRankingServiceTest` | not simply lowest delta; cushion penalty; delta balance; bounded scores and consecutive ranks; neutral IV fallback; weaker-leg liquidity; DTE fit |
| `StrangleServiceTest` | spec example end to end; same-expiration pairing only; min total premium; top N; ITM never recommended; ATM IV |
| `OptionChainServiceTest` | grouping, strike merge, ITM flags, spot divider, monthly vs weekly labels, missing-Greeks warning |
| `ParameterResolverTest` / `AlpacaOptionServiceTest` | defaults vs overrides, validation messages; price source selection, DTO mapping, data-issue recording |
| `OptionsApiControllerTest` | parameter binding and every error mapping (400/404/429 + Retry-After/502/500) |
| `StrangleScannerIntegrationTest` | **end to end**: the real app on a random port talks to a fake Alpaca HTTP server (paginated, auth-checked, filter-aware). Verifies rejection counts, the 3 expected pairs, the spec pair's numbers, the chain JSON, invalid/unknown symbols, and the rendered HTML |
| `LiveAlpacaSmokeTest` | real Alpaca paper API (opt-in) |

---

## 9. Optional extras

| Extra | Status |
|---|---|
| A. Expected move analysis | ✅ ATM-IV expected move, cushion in EMs, shown on the ladder |
| B. IV in ranking | ✅ through the expected-move cushion factor |
| C. IV rank | ❌ needs a year of IV history, which Alpaca does not provide in this API |
| D. Earnings flag | ❌ Alpaca has no earnings calendar; this would need a second data vendor |
| E. Strike / breakeven visualisation | ✅ price ladder in the candidate detail view |
| F. Historical performance | ❌ out of scope (would need historical option bars and a backtest engine) |

## 10. Limitations and honest caveats

- **Indicative feed.** On a free account, option quotes come from Alpaca's `indicative` feed,
  which is delayed and derived, not the live OPRA NBBO. The UI shows a warning. Set
  `ALPACA_OPTIONS_FEED=opra` if you have the subscription.
- **Outside market hours** quotes and Greeks are stale or missing. The scanner then correctly
  finds fewer candidates, and the rejection breakdown shows why.
- **Greeks** come from Alpaca's model. They are validated for sign and range, but not recomputed.
- **OI** is the previous session's figure, which is how OCC publishes it.
- **No margin or buying-power estimate.** Check with your broker.
- **Monthly detection** uses the third Friday. Holiday-shifted monthlies are labelled `(W)`.

## 11. Risk disclosure

This tool identifies potential premium-selling opportunities. It does not guarantee profit, and it
places no trades. The risks include:
- theoretically unlimited loss on the short call and large loss on the short put
- early assignment
- delta shifting as the underlying moves, and gamma risk growing into expiration
- IV expansion repricing both legs against you
- liquidity and slippage on entry and exit
- margin requirements
