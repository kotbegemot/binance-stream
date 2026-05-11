package io.stream.quotes.ranking;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SymbolRegistryTest {

    @Test
    void getInitiallyReturnsEmpty() {
        SymbolRegistry r = new SymbolRegistry();
        assertThat(r.get()).isEmpty();
    }

    @Test
    void setUpdatesAndFiresListener() {
        SymbolRegistry r = new SymbolRegistry();
        List<List<String>> seen = new ArrayList<>();
        r.addListener(seen::add);

        r.set(List.of("BTCUSDT", "ETHUSDT"));

        assertThat(r.get()).containsExactly("BTCUSDT", "ETHUSDT");
        assertThat(seen).hasSize(1);
        assertThat(seen.get(0)).containsExactly("BTCUSDT", "ETHUSDT");
    }

    @Test
    void setWithSameListDoesNotFireListener() {
        SymbolRegistry r = new SymbolRegistry();
        r.set(List.of("BTCUSDT"));

        AtomicInteger count = new AtomicInteger();
        r.addListener(l -> count.incrementAndGet());

        r.set(List.of("BTCUSDT"));
        assertThat(count.get()).isZero();

        r.set(List.of("BTCUSDT", "ETHUSDT"));
        assertThat(count.get()).isEqualTo(1);
    }

    @Test
    void multipleListenersAllReceiveUpdates() {
        SymbolRegistry r = new SymbolRegistry();
        AtomicInteger a = new AtomicInteger();
        AtomicInteger b = new AtomicInteger();
        AtomicInteger c = new AtomicInteger();
        r.addListener(l -> a.incrementAndGet());
        r.addListener(l -> b.incrementAndGet());
        r.addListener(l -> c.incrementAndGet());

        r.set(List.of("X"));

        assertThat(a.get()).isEqualTo(1);
        assertThat(b.get()).isEqualTo(1);
        assertThat(c.get()).isEqualTo(1);
    }

    @Test
    void listenerExceptionDoesNotBlockOthers() {
        SymbolRegistry r = new SymbolRegistry();
        AtomicInteger after = new AtomicInteger();
        r.addListener(l -> { throw new RuntimeException("boom"); });
        r.addListener(l -> after.incrementAndGet());

        r.set(List.of("X"));

        assertThat(after.get()).isEqualTo(1);
    }
}
