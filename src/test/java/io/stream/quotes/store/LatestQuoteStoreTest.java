package io.stream.quotes.store;

import io.stream.quotes.model.Quote;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static io.stream.quotes.support.TestSupport.quote;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LatestQuoteStoreTest {

    private final LatestQuoteStore store = new LatestQuoteStore();

    @Test
    void putThenGetReturnsSame() {
        Quote q = quote("BTCUSDT", 1L);
        store.put(q);

        assertThat(store.get("BTCUSDT")).contains(q);
    }

    @Test
    void newerUpdateIdReplacesOlder() {
        Quote old = quote("BTCUSDT", 1L);
        Quote newer = quote("BTCUSDT", 2L);

        store.put(old);
        store.put(newer);

        assertThat(store.get("BTCUSDT")).contains(newer);
    }

    @Test
    void olderUpdateIdDoesNotOverwriteNewer() {
        Quote newer = quote("BTCUSDT", 10L);
        Quote older = quote("BTCUSDT", 5L);

        store.put(newer);
        store.put(older);

        assertThat(store.get("BTCUSDT")).contains(newer);
    }

    @Test
    void getUnknownSymbolReturnsEmpty() {
        assertThat(store.get("UNKNOWN")).isEmpty();
    }

    @Test
    void snapshotIsImmutableAndReflectsState() {
        store.put(quote("BTCUSDT", 1L));
        store.put(quote("ETHUSDT", 2L));

        Map<String, Quote> snap = store.snapshot();

        assertThat(snap).hasSize(2);
        assertThat(snap.keySet()).containsExactlyInAnyOrder("BTCUSDT", "ETHUSDT");
        assertThatThrownBy(() -> snap.put("X", quote("X", 1L)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void sizeReflectsDistinctSymbols() {
        assertThat(store.size()).isZero();
        store.put(quote("BTCUSDT", 1L));
        store.put(quote("BTCUSDT", 2L));
        store.put(quote("ETHUSDT", 1L));
        assertThat(store.size()).isEqualTo(2);
    }

    @Test
    void concurrentPutsKeepHighestUpdateIdPerSymbol() throws InterruptedException {
        int threads = 16;
        int perThread = 5_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicLong maxIdSeen = new AtomicLong();

        for (int t = 0; t < threads; t++) {
            final int base = t * perThread;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        long id = base + i;
                        store.put(quote("BTCUSDT", id));
                        maxIdSeen.accumulateAndGet(id, Math::max);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        Optional<Quote> latest = store.get("BTCUSDT");
        assertThat(latest).isPresent();
        assertThat(latest.get().updateId()).isEqualTo(maxIdSeen.get());
    }

}