package io.stream.quotes.ranking;

import io.stream.quotes.store.SqliteConnectionProvider;
import io.stream.quotes.store.TrackedSymbolsStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.awaitility.Awaitility.await;

class RankingRefreshSchedulerTest {

    private SqliteConnectionProvider provider;
    private TrackedSymbolsStore store;
    private SymbolRegistry registry;
    private RankingRefreshScheduler scheduler;

    @BeforeEach
    void setUp(@TempDir Path tmp) throws Exception {
        String dbPath = tmp.resolve("quotes.db").toString();
        provider = new SqliteConnectionProvider(dbPath);
        provider.open();
        store = new TrackedSymbolsStore(provider.trackedSymbolsConnection(), Clock.systemUTC());
        store.loadInitial(List.of("BTCUSDT", "ETHUSDT"), "test-seed");
        registry = new SymbolRegistry();
        registry.set(store.current());
    }

    @AfterEach
    void tearDown() {
        if (scheduler != null) {
            scheduler.close();
        }
        if (provider != null) {
            provider.close();
        }
    }

    @Test
    void runOnceAddsNewSymbolsFromRanker() throws Exception {
        InstrumentRanker ranker = () -> List.of("BTCUSDT", "ETHUSDT", "SOLUSDT");
        scheduler = new RankingRefreshScheduler(ranker, store, registry, Duration.ofSeconds(1));

        scheduler.runOnce();

        assertThat(store.current()).contains("SOLUSDT");
        assertThat(registry.get()).contains("SOLUSDT");
    }

    @Test
    void runOnceRespectsAdminRemovedTombstones() throws Exception {
        // Admin removed SHIBUSDT — even if ranker returns it, scheduler must skip.
        store.applyPatch(List.of(), List.of("SHIBUSDT"));
        registry.set(store.current());
        InstrumentRanker ranker = () -> List.of("BTCUSDT", "ETHUSDT", "SHIBUSDT");
        scheduler = new RankingRefreshScheduler(ranker, store, registry, Duration.ofSeconds(1));

        scheduler.runOnce();

        assertThat(store.current()).doesNotContain("SHIBUSDT");
        assertThat(registry.get()).doesNotContain("SHIBUSDT");
    }

    @Test
    void runOnceNeverRemovesEvenWhenSymbolFellOutOfTop() throws Exception {
        // Ranker no longer includes ETHUSDT, but we should keep it.
        InstrumentRanker ranker = () -> List.of("BTCUSDT", "SOLUSDT");
        scheduler = new RankingRefreshScheduler(ranker, store, registry, Duration.ofSeconds(1));

        scheduler.runOnce();

        assertThat(store.current()).contains("ETHUSDT");
        assertThat(store.current()).contains("SOLUSDT");
    }

    @Test
    void runOnceTolerantToRankerError() {
        InstrumentRanker ranker = () -> {
            throw new RuntimeException("upstream boom");
        };
        scheduler = new RankingRefreshScheduler(ranker, store, registry, Duration.ofSeconds(1));

        // Should not throw.
        scheduler.runOnce();
    }

    @Test
    void schedulerLoopAppliesUpdatesPeriodically() throws Exception {
        AtomicReference<List<String>> rankerReturns = new AtomicReference<>(
                List.of("BTCUSDT", "ETHUSDT"));
        InstrumentRanker ranker = rankerReturns::get;
        scheduler = new RankingRefreshScheduler(ranker, store, registry, Duration.ofMillis(50));
        scheduler.start();

        Thread.sleep(120);
        assertThat(store.current()).doesNotContain("SOLUSDT");

        rankerReturns.set(List.of("BTCUSDT", "ETHUSDT", "SOLUSDT"));

        await().atMost(2, TimeUnit.SECONDS).until(() -> store.current().contains("SOLUSDT"));
        assertThat(registry.get()).contains("SOLUSDT");
    }

    @Test
    void rejectsZeroOrNegativeInterval() {
        InstrumentRanker ranker = List::of;
        assertThatIllegalArgumentException().isThrownBy(() ->
                new RankingRefreshScheduler(ranker, store, registry, Duration.ZERO));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new RankingRefreshScheduler(ranker, store, registry, Duration.ofMillis(-1)));
    }
}