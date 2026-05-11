# Binance Quotes Service

A Java 21 service that streams top-of-book quotes (bid / bid_size / ask / ask_size)
for the top‑10 crypto instruments from Binance via WebSocket, persists them to
SQLite, and exposes the latest snapshot over HTTP.

## Quick start (Docker)

```bash
# Run the service
docker compose up service

# In another terminal
curl http://localhost:8080/quotes/latest | jq

# Run the full test suite in the same container environment
docker compose --profile test run --rm tests
```

No local JDK is required — `docker compose` is sufficient. The service stores
SQLite data under `./data/quotes.db` via a bind mount so quotes survive
restarts.

To override configuration, copy the example and edit:

```bash
cp .env.example .env
```

## Build, run, test (local Gradle)

Requires JDK 21+ (Gradle's Foojay resolver will download JDK 21 for the
toolchain on first build if you only have a newer one installed).

```bash
./gradlew build       # compile + tests
./gradlew test        # tests only
./gradlew run         # start the service (Ctrl+C to stop)
./gradlew installDist # produces build/install/binance-quotes-service/
```

## API

All endpoints return JSON. Prices and sizes are serialised as **strings** to
preserve `BigDecimal` precision through JSON; field names follow `snake_case`.
`received_at_ms` is the wall-clock time at which the frame arrived at this
service.

### `GET /quotes/latest` — all latest

```
[
  {
    "symbol": "BTCUSDT",
    "bid": "50000.00",
    "bid_size": "1.5",
    "ask": "50001.00",
    "ask_size": "2.0",
    "received_at_ms": 1715472000123
  },
  ...
]
```

Symbols that have not yet received a quote do not appear.

### `GET /quotes/latest/{symbol}` — single latest

```
GET /quotes/latest/BTCUSDT   → 200 OK, single object as above
GET /quotes/latest/UNKNOWN   → 404 {"error":"symbol not tracked","symbol":"UNKNOWN"}
GET /quotes/latest/BTCUSDT   → 404 {"error":"no quote yet","symbol":"BTCUSDT"} during cold-start
```

Path param is case-insensitive (`/quotes/latest/btcusdt` works).

### `GET /quotes/{symbol}/history` — history from SQLite

```
GET /quotes/BTCUSDT/history                                # default limit=100, all time
GET /quotes/BTCUSDT/history?limit=500
GET /quotes/BTCUSDT/history?from=1715472000000&to=1715475600000
GET /quotes/BTCUSDT/history?from=1715472000000&limit=2000
```

- **Response**: same JSON array shape as `/quotes/latest`, sorted by
  `received_at_ms DESC` (most recent first).
- **`limit`**: default 100. Cap is `QUOTES_HISTORY_MAX_LIMIT` (default
  10 000). Requests above the cap return `400`.
- **`from` / `to`**: epoch milliseconds; bounds are inclusive. `from > to`
  returns `400`. Either or both can be omitted.
- **Symbol must be in the tracked set** — otherwise `404`. This avoids
  scanning the DB for arbitrary tickers.
- Backed by an indexed read of `quotes(symbol, received_at_wall_ms DESC)`
  via a separate read-only SQLite connection.

### `GET /symbols` — tracked symbols

```
{"symbols": ["BTCUSDT", "ETHUSDT", ...], "count": 10}
```

The list is resolved at startup (CoinGecko on first start, SQLite on
subsequent), and can be mutated at runtime via the admin endpoints below.

## Symbol management (admin API)

Tracked symbols and the CoinGecko filter list are both managed through
admin endpoints. By default they are **unauthenticated** — handy for
local dev. Set `QUOTES_ADMIN_API_KEY=<secret>` to require an
`X-Admin-Key: <secret>` header on every `/admin/*` request (missing or
wrong key → 401). This is a single shared-secret gate, not enterprise
auth; in production it should sit behind mTLS / a real identity layer.

```bash
# with auth enabled:
curl -H "X-Admin-Key: $QUOTES_ADMIN_API_KEY" http://localhost:8080/admin/symbols
```

### CORS

By default CORS is disabled — the server emits no
`Access-Control-Allow-Origin` header, so cross-origin browser requests
are blocked by the browser. Set
`QUOTES_CORS_ALLOWED_ORIGINS=https://app.example.com,https://admin.example.com`
(comma-separated) to whitelist origins; requests whose `Origin` matches
the list get the header back, others don't.

### Async handlers and request timeout

All HTTP endpoints run on a shared virtual-thread executor and are
returned to Javalin as `CompletableFuture`s. With `QUOTES_HTTP_TIMEOUT_MS`
unset or `0` the future never times out — handlers run to completion
(current default behaviour). Set it to a positive value (e.g.
`QUOTES_HTTP_TIMEOUT_MS=2000`) and the future is cut off after that many
milliseconds; the response is `408 Request Timeout` with an explicit
JSON error body. The timeout is enforced inside our own
`CompletableFuture.orTimeout`, not via Javalin's `asyncTimeout`, so the
surfaced status is the same regardless of the underlying Jetty version.

### Bootstrap model

```
First start (SQLite tracked_symbols empty):
  1. Read instruments-fallback.yaml ONCE (filter lists + fallback symbols)
  2. Call configured ranker (CoinGecko by default) → filter via SQLite
     filtered_tickers → map asset symbol → <ASSET>USDT → take top 10
  3. If CoinGecko fails, fall back to YAML.symbols (offline-friendly)
  4. INSERT the seeded list into tracked_symbols

Subsequent starts (tracked_symbols non-empty):
  - Read SQLite directly. CoinGecko is NOT queried.
  - Admin's curated list survives across restarts.

Runtime (optional, QUOTES_RANKING_REFRESH_HOURS > 0):
  - Scheduler polls CoinGecko every N hours.
  - Adds symbols that are in top-10 AND not currently tracked
    AND not admin-removed (tombstones).
  - Never removes. Only admin can shrink the tracked list.
```

### `GET /admin/symbols`

```
{"symbols": ["BTCUSDT","ETHUSDT","SOLUSDT",...], "count": N}
```

### `PUT /admin/symbols` — replace whole list

```
body  {"symbols": ["BTCUSDT","ETHUSDT","SOLUSDT"]}
→ 200 {"symbols":[...], "count":N, "added":[...], "removed":[...]}
→ 400 if `symbols` is missing/empty (the service refuses to wipe the list)
→ 400 if any symbol fails validation
```

Symbol validation: `^[A-Z0-9_]{2,20}USDT$` (case-insensitive on input —
underscore is allowed to accommodate CoinGecko-style symbols such as
`FIGR_HELOCUSDT`).
Removed symbols are written to `admin_removed_symbols` so a later
periodic refresh from CoinGecko cannot silently re-add them.

### `PATCH /admin/symbols` — atomic add + remove

```
body  {"add": ["AVAXUSDT"], "remove": ["SHIBUSDT"]}    # both optional
→ 200 {"symbols":[...], "count":N, "added":[...], "removed":[...]}
→ 400 if the resulting list would be empty
→ 400 on validation errors
```

Both `add` and `remove` are independently optional. The whole change is
one SQLite transaction. After mutation, the in-memory `SymbolRegistry`
fires listeners that (a) tell `BinanceWsSource.resubscribe(...)` to
reopen the WS stream with the new symbol set and (b) update HttpServer's
internal "tracked set" used by `/quotes/latest/{symbol}` and `/quotes/{symbol}/history` 404 logic.

PATCH `add` of a symbol that is in `admin_removed_symbols` also deletes
the tombstone — i.e. admin add un-does admin remove explicitly.

### `GET /admin/filters` — CoinGecko exclusion list

```
{"filtered": ["USDT","USDC","WBTC","stETH",...], "count": N}
```

These tickers are excluded from CoinGecko's response before mapping to
`<ASSET>USDT`. The list is seeded once at first start from YAML's
`stablecoin_filter ∪ wrapped_filter` (category labels are not retained
— a ticker is either in the filter or not).

### `PUT /admin/filters` — replace whole filter set

```
body  {"filtered": ["USDT","DAI"]}                # may be empty
→ 200 {"filtered":[...], "count":N, "added":[...], "removed":[...]}
```

Empty filter is allowed — corresponds to "no exclusions at all".

### `PATCH /admin/filters` — atomic add + remove

```
body  {"add": ["FDUSD"], "remove": ["USDT"]}
→ 200 {"filtered":[...], "count":N, "added":[...], "removed":[...]}
```

Filter changes do **not** trigger a WS resubscribe. The currently
tracked symbol set stays subscribed; the new filter only affects the
next CoinGecko query (first-start seed or scheduled refresh).

## Architecture

```
Binance WS (spot, combined stream, data-stream.binance.vision)
    │
    ▼  Jetty WebSocket Client listener (virtual thread)
BookTickerParser (Jackson, accepts raw + combined-stream wrapper)
    │
    ▼  synchronously in the WS listener (hot path, no queues)
LatestQuoteStore (ConcurrentHashMap, merge by updateId)  ◄── HTTP reads
    │
    ▼  async submit (bounded LinkedBlockingQueue)
SqliteQuoteWriter (virtual thread, batched insert 50 ms / 100 quotes)
    │
    ▼
SQLite (WAL, synchronous = NORMAL, PK (symbol, update_id))
```

Key design decisions:

- **Spot** market data via `wss://data-stream.binance.vision`. A single
  *combined stream* connection carries all 10 symbols.
- **In-memory** `ConcurrentHashMap<String, Quote>` is the source of truth for
  `/quotes/latest`. Writes merge by Binance `updateId`, so out-of-order frames
  never overwrite a fresher quote. Reads are O(1) and lock-free.
- **SQLite** is persistent storage, not a read path — the API never queries
  it. WAL mode + `synchronous=NORMAL` give safe-on-crash durability with
  minimal write amplification. PRIMARY KEY `(symbol, update_id)` provides
  natural dedup; the writer uses `INSERT OR IGNORE`.
- **Async writer.** The WS listener does the bare minimum on the hot path
  (parse → put-in-map → non-blocking offer). The writer batches up to 100
  quotes or 50 ms and commits a single transaction, so per-frame I/O cost
  is amortised.
- **Reconnect.** A `BackoffPolicy` (exponential 500 ms → 30 s cap, ±20 %
  jitter) drives a virtual-thread reconnect loop in `BinanceWsSource`.
  `backoff.reset()` runs on every successful connect.
- **`BigDecimal`** for all prices and sizes — never `double`. Stored as `TEXT`
  in SQLite, serialised as JSON strings.
- **`System.currentTimeMillis()`** for the wall-clock `received_at_ms`.
- **Virtual threads** (Project Loom) for the WS source loop and the SQLite
  writer — sub-µs context switches, no thread‑pool sizing to tune.

## Configuration

All values are optional; defaults come from `AppConfig`. Set via the
container's environment (compose auto-loads `.env` if present), or directly
when running locally.

| Variable                      | Default                                   | Description                                              |
|-------------------------------|-------------------------------------------|----------------------------------------------------------|
| `QUOTES_BINANCE_WS_URL`       | `wss://data-stream.binance.vision`        | Binance spot market-data WebSocket endpoint              |
| `QUOTES_DB_PATH`              | `./data/quotes.db` (host) / `/data/...`   | SQLite file path                                         |
| `QUOTES_HTTP_PORT`            | `8080`                                    | Port for `GET /quotes/latest`                            |
| `QUOTES_BATCH_MAX_SIZE`       | `100`                                     | Flush SQLite batch after this many quotes                |
| `QUOTES_BATCH_MAX_WAIT_MS`    | `50`                                      | Or after this many ms since the first quote in the batch |
| `QUOTES_HISTORY_MAX_LIMIT`    | `10000`                                   | Hard cap for `/quotes/{symbol}/history?limit=N`          |
| `QUOTES_RANKING_SOURCE`       | `coingecko`                               | `coingecko` or `static` (use only YAML.symbols, skip HTTP) |
| `QUOTES_COINGECKO_URL`        | `https://api.coingecko.com`               | CoinGecko base URL (override for tests/mocks)            |
| `QUOTES_COINGECKO_TIMEOUT_MS` | `5000`                                    | HTTP timeout for CoinGecko requests                      |
| `QUOTES_RANKING_REFRESH_HOURS`| `0`                                       | Periodic refresh interval in hours; `0` disables         |
| `QUOTES_RANKING_REFRESH_MS`   | (unset)                                   | Same as above but in milliseconds; overrides `_HOURS` when set (useful for tests) |
| `QUOTES_ADMIN_API_KEY`        | (empty)                                   | If set, every `/admin/*` request must carry `X-Admin-Key: <value>` (else 401). Empty = unauthenticated (default). |
| `QUOTES_CORS_ALLOWED_ORIGINS` | (empty)                                   | Comma-separated list of allowed CORS origins. Empty = no CORS plugin (cross-origin browser requests blocked). |
| `QUOTES_HTTP_TIMEOUT_MS`      | `0`                                       | Per-request async timeout in ms. `0` = no timeout. Positive value → `408 Request Timeout` after that many ms. |

## Retention

**The SQLite database grows unbounded.** Each quote is one INSERT; nothing
deletes them. Observed footprint on the bundled top-10 list under normal
Binance activity: roughly **750 MB / hour**, or ~18 GB / 24 h. Operators
running the service long-term should monitor `./data/quotes.db` (and the
WAL sidecar) and either truncate the file or add their own retention job.
Retention is intentionally out of process — the service's responsibility
ends at durable insert.

## Top-10 instrument list

The first-start ranker queries CoinGecko, filters stablecoins and
wrapped / liquid-staking derivatives (their `@bookTicker` streams are
either dollar-pegged or derivatives of an underlying), maps each
remaining asset to `<ASSET>USDT`, and persists the top 10 — see
"Bootstrap model" above for the exact flow.

If CoinGecko is unreachable on first start, the service falls back to a
curated snapshot in `src/main/resources/instruments-fallback.yaml`:

```
BTCUSDT  ETHUSDT  SOLUSDT  XRPUSDT  BNBUSDT
DOGEUSDT TRXUSDT  ADAUSDT  LINKUSDT AVAXUSDT
```

The YAML carries both the fallback list and the filter rules
(`stablecoin_filter`, `wrapped_filter`); the same rules seed the
runtime-mutable `filtered_tickers` table that `CoinGeckoRanker` consults on
every refresh. Both lists are mutable via the `/admin/symbols` and
`/admin/filters` endpoints — see "Symbol management".

To bypass CoinGecko entirely, set `QUOTES_RANKING_SOURCE=static`; the
service then uses the YAML list verbatim and never makes a network call
for ranking.

## Project layout

```
src/main/java/io/stream/quotes/
├── Main.java                    application entry-point + shutdown hook
├── config/AppConfig.java        env-driven configuration record
├── model/Quote.java             7-field immutable record (BigDecimal prices)
├── source/                      WebSocket source + parser + backoff policy
├── ranking/                     ranker abstraction + CoinGecko / static impls
│                                + SymbolRegistry + periodic refresh scheduler
├── store/                       in-memory LatestQuoteStore + SQLite writer
│                                + history reader + tracked-symbols / filter
│                                stores + SqliteConnectionProvider + schema
├── pipeline/QuotePipeline.java  glue: source → in-memory store + writer
└── api/                         Javalin HTTP server, DTOs, validators,
                                 history-query parsing; admin DTOs in admin/
```

## Tests

Run with `./gradlew test` or `docker compose --profile test run --rm tests`.
JaCoCo coverage reports are generated under `build/reports/jacoco/test/html/`
when `./gradlew jacocoTestReport` is run.

All tests are **offline**. The integration tests (`*IT`) use an embedded
Javalin WS server (`MockBinanceWsServer`) as the Binance stand-in, so no
network access is required.

Notable test coverage:

- `BookTickerParserTest` — happy path, combined-stream wrapper, BigDecimal
  precision (1e-8 and 1e9 with 8 decimals), every required field missing,
  malformed JSON, non-bookTicker events.
- `LatestQuoteStoreTest` — newer `updateId` wins, older never overwrites,
  16 × 5 000 concurrent puts converge on the maximum `updateId`.
- `SqliteQuoteWriterTest` — WAL active, schema columns, batch of 1 000
  quotes, primary-key dedup, BigDecimal round-trip via TEXT, `close()`
  drains the queue.
- `BinanceWsSourceIT` — emit/receive end-to-end, server-side disconnect →
  automatic reconnect → fresh emit received, malformed frame does not close
  the connection.
- `QuotePipelineIT` — full flow from a mock-emitted bookTicker to both the
  in-memory store and the SQLite table.

## Known limitations and design choices

- **No `/metrics` endpoint, no HdrHistogram, no benchmark harness.**
  Performance shows up as design pressure on the hot path (no locks,
  no blocking I/O), not as a separate measurement artefact.
- **History endpoint 404 for dropped symbols.** If admin removes a
  symbol, `/quotes/{symbol}/history` for it returns 404 ("symbol not
  tracked") even though historical rows for that symbol remain in
  SQLite. The API reflects the currently tracked set, not everything
  that was ever tracked; the data is still available via raw
  `sqlite3 data/quotes.db`.
- **Periodic refresh is add-only** — it never removes symbols that fall
  out of CoinGecko's top-10. Only admin (via PATCH/PUT) can shrink the
  tracked list, and those admin removals are persisted as tombstones in
  `admin_removed_symbols` so subsequent refreshes don't silently
  re-add them.
- **Spot, not futures.** Spot was chosen because `wss://data-stream.binance.vision`
  is a read-only public mirror that requires no credentials. The trade-off:
  spot `@bookTicker` does **not** carry exchange event/transaction
  timestamps, so end-to-end latency (exchange → client) cannot be measured;
  in-process latency (wire → memory, wire → DB) can.
- **Binance's 24-hour forced disconnect** is handled by ordinary
  `onClose` → backoff → reconnect. No proactive socket rotation.
- **No `/health` endpoint.** Liveness is observable by hitting
  `/quotes/latest` directly.