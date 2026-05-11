package io.stream.quotes.pipeline;

import io.stream.quotes.model.Quote;
import io.stream.quotes.source.QuoteSource;
import io.stream.quotes.store.LatestQuoteStore;
import io.stream.quotes.store.SqliteQuoteWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class QuotePipeline implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(QuotePipeline.class);

    private final QuoteSource source;
    private final LatestQuoteStore latestStore;
    private final SqliteQuoteWriter writer;

    public QuotePipeline(QuoteSource source, LatestQuoteStore latestStore, SqliteQuoteWriter writer) {
        this.source = source;
        this.latestStore = latestStore;
        this.writer = writer;
    }

    public void start() throws Exception {
        writer.start();
        source.start(this::onQuote);
        log.info("pipeline started");
    }

    private void onQuote(Quote q) {
        latestStore.put(q);
        if (!writer.submit(q)) {
            log.warn("sqlite writer queue full, dropping quote for {}", q.symbol());
        }
    }

    @Override
    public void close() {
        try {
            source.close();
        } catch (Exception e) {
            log.warn("error closing source", e);
        }
        try {
            writer.close();
        } catch (Exception e) {
            log.warn("error closing writer", e);
        }
        log.info("pipeline closed");
    }
}