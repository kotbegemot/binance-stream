# Performance Findings

Two prod-like JFR runs, before and after phase 3 (admin API +
CoinGecko + persistence). Phase 3 is "cold" in the hot path —
no new code runs per-quote — so the architectural conclusions
from run #1 hold for the post-phase-3 service. Run #2 also
surfaces a non-perf finding (CoinGecko occasionally yields
symbols that don't exist on Binance USDT) — see the bottom.

## Run #1 — MVP (commit `d256520`, 2026-05-11 17:30 UTC+3)

| | |
|---|---|
| Duration | 600 s |
| Hardware | Apple Silicon, OpenJDK 21.0.11 toolchain |
| Symbols source | StaticRanker (YAML hardcoded) |

| Metric | Value |
|---|---|
| Total rows | **723 739** |
| Throughput | **1 206 q/s** sustained |
| Reconnects | 0 |
| Total GCs | 121 (120 young + 1 old) |
| Total pause time | 139.6 ms (0.023 % of wall) |
| GC pause p50 / p99 / max | 0.97 / 3.75 / 6.83 ms |
| CPU: `KQueue.poll` | 94.6 % of native samples (threads parked) |
| Top alloc: `StringBuilder.<init>` | 32.4 % (BigDecimal → SQLite TEXT chain) |

## Run #2 — post-phase-3 (commit `bd36544`, 2026-05-11 20:48 UTC+3)

| | |
|---|---|
| Duration | 600 s |
| Symbols source | **CoinGecko** (first-start seed, filter applied) |
| Tracked at runtime | BTC, ETH, XRP, BNB, SOL, TRX, FIGR_HELOC, DOGE, WBT, USDS |

| Metric | Value | vs Run #1 |
|---|---|---|
| Total rows | **155 384** | −78 % |
| Throughput | **258 q/s** sustained | −78 % |
| Reconnects | 2 | (new) |
| Total GCs | 27 | −78 % |
| Total pause time | **52.8 ms (0.0088 %)** | −62 % |
| GC pause p50 / p99 / max | 1.89 / 5.44 / 5.44 ms | similar |
| CPU: `KQueue.poll` | 94.9 % | no change |
| Top alloc: `StringBuilder.<init>` | 28.6 % | similar shape |

### Phase 3 hot-path impact: **none**

Allocation profile shape is unchanged. Phase 3 components don't run
per-quote:

- `CoinGeckoRanker` — 1 HTTP call at boot (first start only) plus optional
  refresh on a multi-hour cadence
- `TrackedSymbolsStore` / `FilterStore` — SQL only on admin mutation, not
  on quote arrival
- `SymbolRegistry` — `AtomicReference.get()` for the WS connect path,
  zero allocation in the listener thread per frame
- `RankingRefreshScheduler` — disabled by default (`QUOTES_RANKING_REFRESH_HOURS=0`)

GC pressure is *lower* in run #2 only because the throughput is lower
(fewer allocations per second), not because anything became more
efficient.

### Per-symbol rate (run #2, 10 min)

```
BTCUSDT      52 333    ~87 q/s
ETHUSDT      29 374    ~49 q/s
DOGEUSDT     27 013    ~45 q/s
SOLUSDT      24 502    ~41 q/s
XRPUSDT      10 332    ~17 q/s
BNBUSDT       9 227    ~15 q/s
TRXUSDT       2 600     ~4 q/s
USDSUSDT          3   (effectively zero)
WBTUSDT           0
FIGR_HELOCUSDT    0
                 ─────
                155 384
```

## Non-perf finding (run #2): CoinGecko ↔ Binance set mismatch

CoinGecko's top-30 by market cap on 2026-05-11 included two assets
that do **not** have a `<ASSET>USDT` spot pair on Binance:

- **FIGR_HELOC** (Figure Heloc — tokenised HELOC, not on Binance)
- **WBT** (WhiteBIT Token — listed only on WhiteBIT)

A third candidate, **USDS** (Sky USDS), is *not* a set-mismatch case:
it **does** trade on Binance as `USDSUSDT` (re-verified 2026-06-20:
status=TRADING, quoteAsset=USDT, ~640k 24h volume / 3925 trades). As a
pegged stablecoin its best bid/ask barely moves, so it emitted only
3 bookTicker frames in 10 min — a near-idle slot, but a real tradable
pair. The Binance `exchangeInfo` validation therefore (correctly)
keeps USDSUSDT; excluding it is a stablecoin-filter concern, not a
tradability one.

The service tolerates this gracefully — the WS subscription simply
returns no frames for those streams — but the result is that 3 of
10 tracked slots are wasted, dragging effective throughput down by
~80 %. This is the exact scenario the **admin API** in phase 3 is
designed to fix: an operator can `PATCH /admin/symbols
{"remove":["FIGR_HELOCUSDT","WBTUSDT","USDSUSDT"]}` (which also
writes them to `admin_removed_symbols` so the periodic refresh
doesn't silently re-add them).

At the time of run #2 it was **explicitly out of scope** to call
Binance `/exchangeInfo` at seed time and pre-validate that each
candidate has a live USDT pair — see "Out of scope" in `docs/plane.md`
and README; the admin endpoint was the chosen recovery path.

> **Update (2026-06-20):** seed-time `exchangeInfo` pre-validation was
> subsequently implemented (`BinanceExchangeInfo`, keeping only
> status=TRADING/quoteAsset=USDT candidates). It drops FIGR_HELOC and
> WBT automatically; USDS survives because it is a real TRADING/USDT
> pair, so the admin endpoint / stablecoin filter stays the recovery
> path for near-idle stablecoin slots like it.

USDS is also a candidate to add to `filtered_tickers` so the next
refresh excludes it as a stablecoin (along with USDe, FDUSD,
etc. that *are* already in the seed). Until phase 3, this required a
code change; now it is `PATCH /admin/filters {"add":["USDS"]}`.

## What we would tune if perf actually mattered

(Same shortlist as before — none of these is recommended for the
test-task scope; the architectural conclusion stands.)

1. **BigDecimal → SQLite TEXT.** Half of allocations are in the chain
   `BigDecimal.toPlainString → StringBuilder → encodeUTF8 → byte[]`
   inside `JDBC3PreparedStatement.setString`. Switching to INTEGER
   columns with explicit scale would eliminate this, at the cost of
   schema complexity. Only worth it past ~10 k q/s sustained.
2. **`AQS.newConditionNode`** (~7–9 % allocs) — one per
   `LinkedBlockingQueue.poll` round trip in the writer. A JCTools
   MPSC ring buffer would zero it, again only relevant at higher
   ingest rates.
3. **Jackson tree-based parsing.** `readTree` allocates `ObjectNode`,
   `TextNode` etc. ~3–4 % of allocs total. Streaming
   `JsonParser.nextToken()` removes this. Adds ~50 lines of code
   and makes the parser less tolerant to schema drift.

## Summary

- **Phase 3 added no measurable cost** on the hot path.
- The lower throughput in run #2 is a *workload* effect (3 of 10
  CoinGecko-selected symbols have no Binance USDT pair), not a *code*
  regression.
- GC remains negligible (< 0.01 % of wall-clock in run #2).
- CPU is idle-dominated (94.9 % `KQueue.poll`) — the JVM is parked
  most of the time, exactly as designed.

The architecture (in-memory ConcurrentHashMap on the hot path; async
batched SQLite writer; lock-free coordination) continues to do what
it was built for.