package io.stream.quotes.ranking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * In-memory observer-pattern source of truth for the currently tracked symbol list.
 * Listeners fire only when the new list actually differs from the previous one.
 */
public final class SymbolRegistry {

    private static final Logger log = LoggerFactory.getLogger(SymbolRegistry.class);

    private final AtomicReference<List<String>> ref = new AtomicReference<>(List.of());
    private final CopyOnWriteArrayList<Consumer<List<String>>> listeners = new CopyOnWriteArrayList<>();

    public SymbolRegistry() {
    }

    public List<String> get() {
        return ref.get();
    }

    public void set(List<String> newList) {
        List<String> snapshot = List.copyOf(newList);
        List<String> old = ref.getAndSet(snapshot);
        if (old.equals(snapshot)) {
            return;
        }
        for (Consumer<List<String>> listener : listeners) {
            try {
                listener.accept(snapshot);
            } catch (RuntimeException e) {
                log.warn("symbol-registry listener threw", e);
            }
        }
    }

    public void addListener(Consumer<List<String>> listener) {
        listeners.add(listener);
    }
}
