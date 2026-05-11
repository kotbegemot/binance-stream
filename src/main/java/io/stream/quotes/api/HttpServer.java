package io.stream.quotes.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.javalin.Javalin;
import io.javalin.http.HttpStatus;
import io.javalin.json.JavalinJackson;
import io.stream.quotes.store.LatestQuoteStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class HttpServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HttpServer.class);

    private final int port;
    private final LatestQuoteStore store;
    private final List<String> trackedSymbols;
    private final Set<String> trackedSymbolsSet;
    private final Javalin app;

    public HttpServer(int port, LatestQuoteStore store, List<String> trackedSymbols) {
        this.port = port;
        this.store = store;
        this.trackedSymbols = List.copyOf(trackedSymbols);
        this.trackedSymbolsSet = Set.copyOf(trackedSymbols);
        ObjectMapper mapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        this.app = Javalin.create(config ->
                config.jsonMapper(new JavalinJackson(mapper, false))
        );
        registerRoutes();
    }

    private void registerRoutes() {
        app.get("/quotes/latest", ctx -> {
            List<QuoteDto> body = store.snapshot().values().stream()
                    .map(QuoteDto::from)
                    .sorted(Comparator.comparing(QuoteDto::symbol))
                    .toList();
            ctx.json(body);
        });

        app.get("/quotes/latest/{symbol}", ctx -> {
            String symbol = ctx.pathParam("symbol").toUpperCase(Locale.ROOT);
            if (!trackedSymbolsSet.contains(symbol)) {
                ctx.status(HttpStatus.NOT_FOUND);
                ctx.json(Map.of("error", "symbol not tracked", "symbol", symbol));
                return;
            }
            store.get(symbol)
                    .map(QuoteDto::from)
                    .ifPresentOrElse(ctx::json, () -> {
                        ctx.status(HttpStatus.NOT_FOUND);
                        ctx.json(Map.of("error", "no quote yet", "symbol", symbol));
                    });
        });

        app.get("/symbols", ctx ->
                ctx.json(Map.of("symbols", trackedSymbols, "count", trackedSymbols.size())));
    }

    public void start() {
        app.start(port);
        log.info("http server listening on :{}", actualPort());
    }

    public int actualPort() {
        return app.port();
    }

    @Override
    public void close() {
        app.stop();
    }
}