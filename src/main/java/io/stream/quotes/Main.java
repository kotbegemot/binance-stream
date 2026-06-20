package io.stream.quotes;

import io.stream.quotes.api.HttpServer;
import io.stream.quotes.api.HttpServerOptions;
import io.stream.quotes.config.AppConfig;
import io.stream.quotes.pipeline.QuotePipeline;
import io.stream.quotes.ranking.BinanceExchangeInfo;
import io.stream.quotes.ranking.CoinGeckoRanker;
import io.stream.quotes.ranking.InstrumentRanker;
import io.stream.quotes.ranking.RankingRefreshScheduler;
import io.stream.quotes.ranking.StaticRanker;
import io.stream.quotes.ranking.SymbolRegistry;
import io.stream.quotes.ranking.TradableSymbols;
import io.stream.quotes.source.BackoffPolicy;
import io.stream.quotes.source.BinanceWsSource;
import io.stream.quotes.source.BookTickerParser;
import io.stream.quotes.store.FilterStore;
import io.stream.quotes.store.LatestQuoteStore;
import io.stream.quotes.store.QuoteHistoryReader;
import io.stream.quotes.store.SqliteConnectionProvider;
import io.stream.quotes.store.SqliteQuoteWriter;
import io.stream.quotes.store.TrackedSymbolsStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.time.Clock;
import java.util.List;
import java.util.Set;

public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((t, e) ->
                log.error("uncaught exception in thread {}", t.getName(), e));

        AppConfig config = AppConfig.fromEnv();
        log.info("starting with {}", config);

        ensureParentDir(config.dbPath());

        Clock clock = Clock.systemUTC();
        StaticRanker staticRanker = StaticRanker.fromClasspath();

        SqliteConnectionProvider db = new SqliteConnectionProvider(config.dbPath());
        db.open();

        FilterStore filterStore = new FilterStore(db.filterStoreConnection(), clock);
        Set<String> initialFilter = filterStore.loadInitial(staticRanker.filterRules());
        log.info("filter store loaded {} tickers", initialFilter.size());

        InstrumentRanker ranker = buildRanker(config, staticRanker, filterStore);

        TrackedSymbolsStore symbolsStore = new TrackedSymbolsStore(db.trackedSymbolsConnection(), clock);
        List<String> symbols;
        if (symbolsStore.current().isEmpty()) {
            List<String> seed = ranker.top10Symbols();
            String sourceLabel = AppConfig.RANKING_SOURCE_STATIC.equals(config.rankingSource())
                    ? "yaml-fallback"
                    : "coingecko";
            symbols = symbolsStore.loadInitial(seed, sourceLabel);
            log.info("first start: seeded {} symbols from {}", symbols.size(), sourceLabel);
        } else {
            symbols = symbolsStore.current();
            log.info("loaded {} tracked symbols from sqlite", symbols.size());
        }

        SymbolRegistry registry = new SymbolRegistry();
        registry.set(symbols);

        LatestQuoteStore latestStore = new LatestQuoteStore();
        SqliteQuoteWriter writer = new SqliteQuoteWriter(
                db.quoteWriterConnection(), config.batchMaxSize(), config.batchMaxWait());
        BinanceWsSource source = new BinanceWsSource(
                config.binanceWsUrl(), symbols, new BookTickerParser(), BackoffPolicy.defaultPolicy());

        QuotePipeline pipeline = new QuotePipeline(source, latestStore, writer);
        pipeline.start();

        QuoteHistoryReader history = new QuoteHistoryReader(db.historyReaderConnection());

        HttpServerOptions httpOptions = HttpServerOptions.defaults()
                .withHistory(history, config.historyMaxLimit())
                .withSymbolAdmin(symbolsStore, registry)
                .withFilterAdmin(filterStore)
                .withAdminApiKey(config.adminApiKey())
                .withCorsAllowedOrigins(config.corsAllowedOrigins())
                .withHttpAsyncTimeoutMs(config.httpAsyncTimeoutMs());
        HttpServer http = new HttpServer(config.httpPort(), latestStore, symbols, httpOptions);

        registry.addListener(source::resubscribe);
        registry.addListener(http::setTrackedSymbols);

        http.start();

        RankingRefreshScheduler scheduler = null;
        if (!config.rankingRefreshInterval().isZero()) {
            scheduler = new RankingRefreshScheduler(
                    ranker, symbolsStore, registry, config.rankingRefreshInterval());
            scheduler.start();
        } else {
            log.info("ranking refresh disabled (QUOTES_RANKING_REFRESH_HOURS=0)");
        }
        final RankingRefreshScheduler schedulerRef = scheduler;

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("shutdown signal received");
            if (schedulerRef != null) {
                try {
                    schedulerRef.close();
                } catch (Exception e) {
                    log.warn("error closing ranking scheduler", e);
                }
            }
            try {
                http.close();
            } catch (Exception e) {
                log.warn("error closing http server", e);
            }
            try {
                pipeline.close();  // stops writer thread; connection lifecycle is db.close() below
            } catch (Exception e) {
                log.warn("error closing pipeline", e);
            }
            try {
                db.close();  // closes ALL underlying SQLite connections (writer, tracked, filter, reader)
            } catch (Exception e) {
                log.warn("error closing sqlite provider", e);
            }
            log.info("shutdown complete");
        }, "shutdown-hook"));

        Thread.currentThread().join();
    }

    private static InstrumentRanker buildRanker(AppConfig config,
                                                 StaticRanker staticRanker,
                                                 FilterStore filterStore) {
        if (AppConfig.RANKING_SOURCE_STATIC.equals(config.rankingSource())) {
            log.info("ranking source: static (YAML)");
            return staticRanker;
        }
        log.info("ranking source: coingecko ({})", config.coinGeckoUrl());
        TradableSymbols tradable = new BinanceExchangeInfo(
                config.binanceRestUrl(), config.binanceRestTimeout(), config.exchangeInfoTtl());
        return new CoinGeckoRanker(
                config.coinGeckoUrl(),
                config.coinGeckoTimeout(),
                staticRanker,
                () -> {
                    try {
                        return filterStore.currentSetUpperCase();
                    } catch (Exception e) {
                        log.warn("failed to read filter store, using empty filter for this call", e);
                        return Set.of();
                    }
                },
                tradable);
    }

    private static void ensureParentDir(String dbPath) {
        File parent = new File(dbPath).getAbsoluteFile().getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            log.warn("failed to create parent directory for db at {}", parent);
        }
    }
}