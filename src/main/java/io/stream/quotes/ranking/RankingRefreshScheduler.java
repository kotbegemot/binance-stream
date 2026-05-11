package io.stream.quotes.ranking;

import io.stream.quotes.store.Diff;
import io.stream.quotes.store.TrackedSymbolsStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Periodically polls an {@link InstrumentRanker} (typically CoinGecko) for the
 * current top-10 and merges new entries into the tracked-symbols store.
 *
 * <p>Semantics:
 * <ul>
 *   <li>Add-only — never removes symbols, even if they fall out of the source's
 *       top-10.</li>
 *   <li>Respects admin tombstones — anything in {@code admin_removed_symbols}
 *       is skipped, so explicit admin deletions survive across refreshes.</li>
 *   <li>On error logs WARN and continues the loop on the next tick — never
 *       fails the JVM.</li>
 * </ul>
 */
public final class RankingRefreshScheduler implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RankingRefreshScheduler.class);

    private final InstrumentRanker ranker;
    private final TrackedSymbolsStore store;
    private final SymbolRegistry registry;
    private final Duration interval;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread thread;

    public RankingRefreshScheduler(InstrumentRanker ranker,
                                    TrackedSymbolsStore store,
                                    SymbolRegistry registry,
                                    Duration interval) {
        this.ranker = Objects.requireNonNull(ranker, "ranker");
        this.store = Objects.requireNonNull(store, "store");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.interval = Objects.requireNonNull(interval, "interval");
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval must be positive, got " + interval);
        }
    }

    public synchronized void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        thread = Thread.ofVirtual()
                .name("ranking-refresh-scheduler")
                .start(this::loop);
        log.info("ranking refresh scheduler started, interval={}ms", interval.toMillis());
    }

    /**
     * Run one refresh tick synchronously. Exposed for tests; production
     * traffic goes through the background loop.
     */
    public void runOnce() {
        try {
            List<String> fresh = ranker.top10Symbols();
            TrackedSymbolsStore.RefreshSnapshot snapshot = store.snapshotForRefresh();
            Set<String> currentSet = new HashSet<>(snapshot.tracked());
            Set<String> removedSet = snapshot.adminRemoved();

            List<String> toAdd = new ArrayList<>();
            for (String sym : fresh) {
                if (currentSet.contains(sym) || removedSet.contains(sym)) {
                    continue;
                }
                toAdd.add(sym);
            }

            if (toAdd.isEmpty()) {
                log.debug("refresh: no new symbols");
                return;
            }

            Diff diff = store.applyPatch(toAdd, List.of());
            List<String> after = store.current();
            registry.set(after);
            log.info("refresh: +{} (admin-removed ignored: {})", diff.added(),
                    intersection(fresh, removedSet));
        } catch (Exception e) {
            log.warn("ranking refresh tick failed", e);
        }
    }

    private void loop() {
        while (running.get()) {
            try {
                Thread.sleep(interval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!running.get()) {
                return;
            }
            runOnce();
        }
    }

    @Override
    public synchronized void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        log.info("ranking refresh scheduler stopped");
    }

    private static List<String> intersection(List<String> a, Set<String> b) {
        List<String> out = new ArrayList<>();
        for (String s : a) {
            if (b.contains(s)) {
                out.add(s);
            }
        }
        return out;
    }
}