package io.stream.quotes.support;

import io.javalin.Javalin;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

public final class MockCoinGeckoServer implements AutoCloseable {

    private final Javalin app;
    private final AtomicReference<Response> nextResponse = new AtomicReference<>(
            new Response(200, "[]", Duration.ZERO));
    private int port = -1;

    public MockCoinGeckoServer() {
        this.app = Javalin.create();
        this.app.get("/api/v3/coins/markets", ctx -> {
            Response r = nextResponse.get();
            if (r.delay != null && !r.delay.isZero() && !r.delay.isNegative()) {
                try {
                    Thread.sleep(r.delay.toMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
            ctx.status(r.status);
            ctx.contentType("application/json");
            ctx.result(r.body);
        });
    }

    public URI start() {
        app.start(0);
        port = app.port();
        return URI.create("http://127.0.0.1:" + port);
    }

    public int port() {
        return port;
    }

    public void respondWith(int status, String body) {
        nextResponse.set(new Response(status, body, Duration.ZERO));
    }

    public void respondWith(int status, String body, Duration delay) {
        nextResponse.set(new Response(status, body, delay));
    }

    @Override
    public void close() {
        try {
            app.stop();
        } catch (Exception ignored) {
        }
    }

    private record Response(int status, String body, Duration delay) {
    }
}
