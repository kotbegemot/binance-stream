# Performance Analysis

Tooling and recipes for capturing performance artefacts under real Binance
load. Findings live in `findings.md`; raw outputs (`.jfr`, flame-graph HTML,
text summaries) sit alongside.

## Java Flight Recorder (JFR)

JFR is built into the JDK — no extra tooling to install. Overhead is ~1-2% CPU
at `settings=profile`.

### Capture

```bash
./gradlew runWithJfr                            # default: 600s, perf/quotes-service.jfr
./gradlew runWithJfr -PjfrDurationSec=300       # 5 minutes
./gradlew runWithJfr -PjfrFile=perf/run-2.jfr   # custom output
./gradlew runWithJfr -PjfrSettings=default      # lighter profile
```

The recording auto-starts at JVM boot and stops after `jfrDurationSec`. The
service keeps running afterwards — `Ctrl+C` to stop.

### View

* **Text summary** (commit-friendly, no GUI):

  ```bash
  jfr summary perf/quotes-service.jfr > perf/quotes-service-summary.txt
  jfr print --events CPULoad,GCHeapSummary,ObjectAllocationSample perf/quotes-service.jfr
  ```

* **JDK Mission Control** (`jmc` if installed via SDKMAN/Homebrew) — interactive timeline, hot methods, GC, allocations.

* **IntelliJ IDEA Ultimate** opens `.jfr` natively in the *Profiler* tool window.

## async-profiler

More accurate sampling than JFR for CPU/alloc profiles — no safepoint bias.

### Install

```bash
brew install async-profiler
```

### Capture flame graphs

While the service is running (e.g. via `./gradlew run`):

```bash
PID=$(pgrep -f 'binance-quotes-service\|io.stream.quotes.Main')

# CPU flame graph (60 seconds, HTML output)
asprof -d 60 -f perf/cpu-flame.html "$PID"

# Allocation flame graph
asprof -d 60 -e alloc -f perf/alloc-flame.html "$PID"

# Lock contention
asprof -d 60 -e lock -f perf/lock-flame.html "$PID"
```

`-XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints` are already applied
by the `runWithJfr` task; for `gradle run` you may want to add them manually
to get more accurate stacks:

```bash
JAVA_TOOL_OPTIONS="-XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints" ./gradlew run
```

Open the resulting `.html` files in any browser — flame graphs are
interactive (hover, search, zoom).

## What to look for

The hot path is `frame on wire → entry in LatestQuoteStore`. Under healthy
operation the CPU profile should show:

* Jetty WebSocket frame decoding
* `BookTickerParser.parse` (Jackson `readTree` + a few `BigDecimal` ctors)
* `ConcurrentHashMap.merge` from `LatestQuoteStore.put`
* `LinkedBlockingQueue.offer` from `SqliteQuoteWriter.submit`

Red flags:

* **`safepoint poll` or `safepoint` near the top of the CPU profile** — JVM
  is spending time waiting for safepoints, indicates pause issues.
* **`ReentrantLock.lock` / `synchronized` blocks** — we should have none on
  the hot path.
* **`GC pause` total > 5% of wall time** — heap pressure, likely from
  excessive allocation in the parser.
* **`SQLitePreparedStatement.executeBatch` consuming significant CPU** — the
  batch window or batch size may need tuning.

## Reproducibility

Real-Binance perf measurements are **not deterministic** — market activity
varies. Each capture in this directory is annotated in `findings.md` with the
wall-clock window and a short note about the market regime (BTC volatile vs.
quiet, etc.).