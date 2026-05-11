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

A single endpoint exposing the in-memory "latest by symbol" snapshot:

```
GET /quotes/latest
→ 200 OK, application/json
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

Field names follow the `snake_case` from the task description. Prices and
sizes are serialised as **strings** to preserve `BigDecimal` precision through
JSON. Symbols that have not yet received a quote (cold start) simply do not
appear in the array. `received_at_ms` is the wall‑clock time at which the
frame arrived at this service.

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

### Why this is fast

The latency-critical path is `frame on wire → entry in LatestQuoteStore`. On
that path the service does only what is necessary: a single JSON parse
(Jackson, thread-local mapper), an integer compare-and-set (`ConcurrentHashMap.merge`),
and a non-blocking offer to the writer queue. There are **no locks, no
blocking I/O, no allocations beyond the parse** between the listener and the
map update. SQLite writes happen on a separate virtual thread; the HTTP path
reads from the in-memory map without touching the writer or the database.

## Configuration

All values are optional; defaults come from `AppConfig`. Set via the
container's environment (compose auto-loads `.env` if present), or directly
when running locally.

| Variable                    | Default                                   | Description                                              |
|-----------------------------|-------------------------------------------|----------------------------------------------------------|
| `QUOTES_BINANCE_WS_URL`     | `wss://data-stream.binance.vision`        | Binance spot market-data WebSocket endpoint              |
| `QUOTES_DB_PATH`            | `./data/quotes.db` (host) / `/data/...`   | SQLite file path                                         |
| `QUOTES_HTTP_PORT`          | `8080`                                    | Port for `GET /quotes/latest`                            |
| `QUOTES_BATCH_MAX_SIZE`     | `100`                                     | Flush SQLite batch after this many quotes                |
| `QUOTES_BATCH_MAX_WAIT_MS`  | `50`                                      | Or after this many ms since the first quote in the batch |

## Top-10 instrument list

The list is determined at startup. The current implementation uses
`StaticRanker`, which loads a curated snapshot from
`src/main/resources/instruments-fallback.yaml` (dated 2026-05-11):

```
BTCUSDT  ETHUSDT  SOLUSDT  XRPUSDT  BNBUSDT
DOGEUSDT TRXUSDT  ADAUSDT  LINKUSDT AVAXUSDT
```

The snapshot already excludes stablecoins (USDT, USDC, DAI, FDUSD, USDe,
PYUSD, TUSD, USDD) and wrapped / liquid-staking derivatives (WBTC, WETH,
stETH, WSTETH, cbBTC, rETH) — their `@bookTicker` streams are either pegged
to the dollar or derivative of an underlying asset, so they are not the
intended "top crypto by market capitalisation".

The YAML carries both the fallback list and the filter rules, so swapping in
a dynamic ranker (e.g. one that calls CoinGecko at startup) only needs to
reuse `FilterRules` and fall back to `StaticRanker` on error.

## Project layout

```
src/main/java/io/stream/quotes/
├── Main.java                  application entry-point + shutdown hook
├── config/AppConfig.java      env-driven configuration record
├── model/Quote.java           7-field immutable record (BigDecimal prices)
├── source/                    WebSocket source + parser + backoff
├── ranking/                   InstrumentRanker + StaticRanker + FilterRules
├── store/                     LatestQuoteStore + SqliteQuoteWriter + schema
├── pipeline/QuotePipeline.java glue: source → store + writer
└── api/                       Javalin HTTP server + DTO
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

## Known limitations (and conscious omissions)

- **No `/metrics` endpoint, no HdrHistogram, no benchmark harness.** The
  task description ("performance is paramount") is treated as a design
  pressure on the hot path (no locks, no blocking I/O) rather than as a
  requirement for a separate measurement artefact. The architecture
  paragraph above is the documented evidence.
- **Top-10 ranking source / filter rules are an open question** to the task
  author. The implementation uses the static snapshot pending a decision
  between (a) global market-cap ranking via CoinGecko, (b) Binance 24-hour
  quote-volume ranking, or (c) the static list. `InstrumentRanker` is a
  one-method interface so swapping in a dynamic ranker is straightforward.
- **Spot, not futures.** Spot was chosen because `wss://data-stream.binance.vision`
  is a read-only public mirror that requires no credentials. The trade-off:
  spot `@bookTicker` does **not** carry exchange event/transaction
  timestamps, so end-to-end latency (exchange → client) cannot be measured;
  in-process latency (wire → memory, wire → DB) can.
- **Binance's 24-hour forced disconnect** is handled by ordinary
  `onClose` → backoff → reconnect. Proactive rotation (open a new socket
  before closing the old) was considered and rejected as scope creep for a
  one-day project.
- **No history endpoint.** The task specifies "a way to get *latest*
  quotes" — that is what `/quotes/latest` provides. The SQLite file is
  available for ad-hoc inspection via `sqlite3 data/quotes.db`.
- **No `/health`.** Reviewers verify the service by hitting `/quotes/latest`
  directly.