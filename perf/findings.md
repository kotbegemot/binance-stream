# Performance Findings

## Run metadata

| | |
|---|---|
| Date | 2026-05-11 |
| JFR window | 17:30:49 → 17:40:49 UTC+3 (600s) |
| Async-profiler windows | CPU 17:31–17:32, alloc 17:33–17:34, lock 17:34–17:35 |
| Service version | `d256520` (after Commit 16) |
| Load | real Binance `wss://data-stream.binance.vision`, 10 symbols, combined stream |
| Hardware | Apple Silicon (Darwin 25.4.0, arm64), OpenJDK 21.0.11 via toolchain |
| JVM settings | G1 default, `-XX:StartFlightRecording=settings=profile`, no heap-size overrides |
| Market regime | BTC ~$80k zone, normal weekend activity |

## Throughput

| Metric | Value |
|---|---|
| Total rows written to SQLite | **723 739** |
| Sustained rate | **1 206 quotes/sec** average over 600s |
| Reconnects | **0** (no Binance disconnects during the window) |
| Queue-full WARN events | **0** (writer never saturated) |
| Top symbol (BTCUSDT) | ~196 quotes/sec |

Per-symbol rate last 60s of the run:
```
BTCUSDT   11 765      ~196/s
ETHUSDT    5 223       ~87/s
SOLUSDT    3 798       ~63/s
DOGEUSDT   2 838       ~47/s
XRPUSDT    2 314       ~39/s
BNBUSDT    1 475       ~25/s
LINKUSDT   1 069       ~18/s
AVAXUSDT     982       ~16/s
ADAUSDT      778       ~13/s
TRXUSDT      664       ~11/s
─────────────────────
TOTAL    30 906       ~515/s   (60s window)
```

## GC behaviour (G1)

| Metric | Value |
|---|---|
| Total GCs | **121** (120 young, 1 old) |
| Total pause time | **139.6 ms over 600 000 ms** |
| % of wall-clock spent paused | **0.0233 %** |
| Pause min / p50 / p99 / max | **0.53 / 0.97 / 3.75 / 6.83 ms** |
| Young GC frequency | ~1 every 5 s |
| Old GC count | 1 (cold-start metadata threshold) |

No GC concerns. All pauses sub-10ms, total cost negligible.

## CPU profile

`jdk.NativeMethodSample` (28 265 samples):

```
26748   94.6%  sun.nio.ch.KQueue.poll                # epoll wait (idle threads parked)
 1481    5.2%  sun.nio.ch.Net.accept                 # listener accept
   13    0.0%  jdk.internal.loader.NativeLibraries.load
   10    0.0%  org.sqlite.core.NativeDB.step         # SQLite execute
    2    0.0%  org.sqlite.core.NativeDB.bind_text_utf8
```

**Reading**: 94.6% of native time is `KQueue.poll` — JVM threads parked
waiting for I/O / queue events. This is **the desired shape**: there is no
active polling loop burning CPU. The hot path completes work and returns to
park. SQLite native code is barely visible (10 samples in 600s), confirming
the writer-thread design is sound.

`jdk.ExecutionSample` (Java-frame sampling, 48 samples over 600s — JFR's
default rate is sparse):

```
4   8.3%  java.util.concurrent.locks.AQS.compareAndSetState        # park/unpark
3   6.2%  java.lang.Long.stringSize                                # toString for JDBC writes
3   6.2%  com.fasterxml.jackson.core.json.UTF8StreamJsonParser._finishAndReturnString
2   4.2%  com.fasterxml.jackson.core.util.TextBuffer.*             # Jackson buffer mgmt
2   4.2%  org.eclipse.jetty.util.Utf8Appendable.appendByte          # WS frame decode
2   4.2%  org.eclipse.jetty.websocket.core.internal.WebSocketCoreSession$IncomingAdaptor.onFrame
1   2.1%  java.util.concurrent.ConcurrentHashMap.merge             # LatestQuoteStore.put
1   2.1%  java.math.BigDecimal.<init>                              # parser
1   2.1%  com.fasterxml.jackson.core.json.UTF8StreamJsonParser._parseName
...
```

The Java hot path is what we'd expect: WS frame decode → Jackson parse →
`ConcurrentHashMap.merge`. No surprising blockers; nothing in our own code
(`io.stream.quotes.*`) jumps out as a hotspot.

## Allocation profile

`jdk.ObjectAllocationSample` (33 541 samples):

```
10865   32.4%  java.lang.AbstractStringBuilder.<init>
 3481   10.4%  java.lang.Long.toString
 2810    8.4%  java.lang.String.encodeUTF8
 2308    6.9%  java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject.newConditionNode
 2258    6.7%  java.util.Arrays.copyOfRangeByte
 1650    4.9%  java.lang.StringBuilder.toString
 1513    4.5%  java.math.BigDecimal.getValueString
  768    2.3%  org.sqlite.jdbc3.JDBC3PreparedStatement.setLong
  670    2.0%  java.util.LinkedHashMap.newNode
  479    1.4%  io.stream.quotes.source.BookTickerParser.parseBookTicker  ← our code
  436    1.3%  java.lang.StringLatin1.toChars
  413    1.2%  java.lang.StringUTF16.compress
  388    1.2%  com.fasterxml.jackson.core.json.ByteSourceJsonBootstrapper.constructParser
  344    1.0%  java.util.concurrent.ScheduledThreadPoolExecutor.schedule
  324    1.0%  java.util.HashMap.resize
  ...
```

**Reading**: roughly half of all allocations are along the chain
`BigDecimal → toPlainString → StringBuilder/Long.toString → encodeUTF8 →
byte[]` that runs inside `JDBC3PreparedStatement.setString` when we hand the
SQLite writer the textual representation of a price. The next biggest chunk
is AQS `ConditionNode`s — one per `LinkedBlockingQueue.poll` round-trip in
the writer loop. Our own parser code (`BookTickerParser.parseBookTicker`) is
1.4 % of allocations.

Total sample rate: 33 541 / 600 s ≈ 56 alloc events/s in the JFR
sampling. The absolute allocation rate from the OS counters is comfortably
inside the young-generation budget — G1 evacuates every ~5 s with sub-ms
pauses (see GC section).

## Lock contention

`asprof -e lock` for 60 s produced `perf/lock-flame.html`. The flame graph
shows only park/unpark stacks for the same blocking queues (writer → reader
of the SQLite submit queue and the WS-source reconnect latch). **No
`synchronized` or `ReentrantLock` contention on the hot path** — confirming
the architectural intent that all coordination happens via lock-free
structures (`ConcurrentHashMap`) and bounded blocking queues.

## What we would tune *if* this mattered

Listed in order of expected payoff, with the caveat that the current numbers
are well within budget — none of these is recommended for the test
assignment scope.

1. **Skip `BigDecimal.toPlainString()` in the writer.** Half of allocations
   are downstream of `JDBC3PreparedStatement.setString(price.toPlainString())`.
   Storing prices as REAL (with precision loss) or as INTEGER (price * 10^8)
   would eliminate this path. Trade-off: gives up the exact-decimal
   guarantee that the TZ implicitly requires.

2. **Replace `LinkedBlockingQueue` with a wait-free MPSC ring buffer**
   (e.g. JCTools `MpscArrayQueue`). The 6.9 % AQS `ConditionNode`
   allocations vanish. Same trade-off as any third-party perf lib:
   meaningful only at >10 k events/s, which we are nowhere near.

3. **Switch parser to Jackson Streaming (`JsonParser`) instead of
   `readTree(...)`.** Eliminates the `ObjectNode`/`TextNode` allocations
   visible in `ObjectAllocationSample` (cumulatively ~3 %). Code becomes a
   bit more verbose but immune to future Jackson default changes.

4. **Reduce SQLite batch window from 50 ms → 5 ms** under heavy load. We
   wouldn't see any p99-on-write difference until ingest exceeds 5 000
   quotes/s; below that the current setting is optimal for write
   amplification.

## Summary

- **CPU: idle-dominated.** 94.6 % of native time is `KQueue.poll` — the JVM
  is mostly parked between events. No busy loops, no lock spins.
- **GC: invisible.** 0.023 % wall-clock paused; max single pause 6.83 ms.
- **Allocations: dominated by JDBC string conversion**, not by our parser
  or store. Half the allocator pressure goes into `BigDecimal → String →
  byte[]` for SQLite TEXT columns.
- **No `synchronized`/`ReentrantLock` contention** on the hot path.
- Sustained ~1 200 quotes/s with **zero queue saturation, zero dropped
  quotes**, **zero reconnects** in this window.

The architecture (in-memory `ConcurrentHashMap` on the hot path; async
batched SQLite writer on a virtual thread) is doing what it was designed
to do.