package io.stream.quotes.source;

import io.stream.quotes.model.Quote;

import java.util.function.Consumer;

public interface QuoteSource extends AutoCloseable {

    void start(Consumer<Quote> onQuote) throws Exception;

    boolean isConnected();

    @Override
    void close();
}