package io.stream.quotes.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.javalin.Javalin;
import io.javalin.json.JavalinJackson;
import io.stream.quotes.store.LatestQuoteStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;

public final class HttpServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HttpServer.class);

    private final int port;
    private final LatestQuoteStore store;
    private final Javalin app;

    public HttpServer(int port, LatestQuoteStore store) {
        this.port = port;
        this.store = store;
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