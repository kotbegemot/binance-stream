package io.stream.quotes.store;

import io.stream.quotes.model.Quote;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class LatestQuoteStore {

    private final ConcurrentHashMap<String, Quote> latest = new ConcurrentHashMap<>();

    public void put(Quote q) {
        latest.merge(q.symbol(), q, (oldQuote, newQuote) ->
                newQuote.updateId() > oldQuote.updateId() ? newQuote : oldQuote);
    }

    public Optional<Quote> get(String symbol) {
        return Optional.ofNullable(latest.get(symbol));
    }

    public Map<String, Quote> snapshot() {
        return Map.copyOf(latest);
    }

    public int size() {
        return latest.size();
    }
}