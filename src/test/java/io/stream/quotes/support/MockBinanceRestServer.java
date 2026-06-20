package io.stream.quotes.support;

import io.javalin.Javalin;

import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class MockBinanceRestServer implements AutoCloseable {

    private final Javalin app;
    private final AtomicReference<Response> nextResponse = new AtomicReference<>(
            new Response(200, "{\"symbols\":[]}"));
    private final AtomicInteger requestCount = new AtomicInteger(0);

    public MockBinanceRestServer() {
        this.app = Javalin.create();
        this.app.get("/api/v3/exchangeInfo", ctx -> {
            requestCount.incrementAndGet();
            Response r = nextResponse.get();
            ctx.status(r.status);
            ctx.contentType("application/json");
            ctx.result(r.body);
        });
    }

    public URI start() {
        app.start(0);
        return URI.create("http://127.0.0.1:" + app.port());
    }

    public int requestCount() {
        return requestCount.get();
    }

    public void respondWith(int status, String body) {
        nextResponse.set(new Response(status, body));
    }

    @Override
    public void close() {
        try {
            app.stop();
        } catch (Exception ignored) {
        }
    }

    private record Response(int status, String body) {
    }
}
