package io.stream.quotes;

import io.stream.quotes.api.HttpServer;
import io.stream.quotes.config.AppConfig;
import io.stream.quotes.pipeline.QuotePipeline;
import io.stream.quotes.ranking.InstrumentRanker;
import io.stream.quotes.ranking.StaticRanker;
import io.stream.quotes.source.BackoffPolicy;
import io.stream.quotes.source.BinanceWsSource;
import io.stream.quotes.source.BookTickerParser;
import io.stream.quotes.source.QuoteSource;
import io.stream.quotes.store.LatestQuoteStore;
import io.stream.quotes.store.SqliteQuoteWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;

public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        AppConfig config = AppConfig.fromEnv();
        log.info("starting with {}", config);

        ensureParentDir(config.dbPath());

        InstrumentRanker ranker = StaticRanker.fromClasspath();
        List<String> symbols = ranker.top10Symbols();
        log.info("tracking {} symbols: {}", symbols.size(), symbols);

        LatestQuoteStore store = new LatestQuoteStore();
        SqliteQuoteWriter writer = new SqliteQuoteWriter(
                config.dbPath(), config.batchMaxSize(), config.batchMaxWait());
        QuoteSource source = new BinanceWsSource(
                config.binanceWsUrl(), symbols, new BookTickerParser(), BackoffPolicy.defaultPolicy());

        QuotePipeline pipeline = new QuotePipeline(source, store, writer);
        pipeline.start();

        HttpServer http = new HttpServer(config.httpPort(), store, symbols);
        http.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("shutdown signal received");
            try {
                http.close();
            } catch (Exception e) {
                log.warn("error closing http server", e);
            }
            try {
                pipeline.close();
            } catch (Exception e) {
                log.warn("error closing pipeline", e);
            }
            log.info("shutdown complete");
        }, "shutdown-hook"));

        Thread.currentThread().join();
    }

    private static void ensureParentDir(String dbPath) {
        File parent = new File(dbPath).getAbsoluteFile().getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            log.warn("failed to create parent directory for db at {}", parent);
        }
    }
}