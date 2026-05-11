package io.stream.quotes.support;

import io.javalin.Javalin;
import io.javalin.websocket.WsContext;

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

public final class MockBinanceWsServer implements AutoCloseable {

    private final Javalin app;
    private final Map<String, WsContext> sessions = new ConcurrentHashMap<>();
    private final List<String> receivedMessages = new CopyOnWriteArrayList<>();
    private final AtomicLong updateIdSeq = new AtomicLong();
    private int port = -1;

    public MockBinanceWsServer() {
        this.app = Javalin.create();
        this.app.ws("/stream", ws -> {
            ws.onConnect(ctx -> sessions.put(ctx.sessionId(), ctx));
            ws.onMessage(ctx -> receivedMessages.add(ctx.message()));
            ws.onClose(ctx -> sessions.remove(ctx.sessionId()));
            ws.onError(ctx -> sessions.remove(ctx.sessionId()));
        });
    }

    public URI start() {
        app.start(0);
        port = app.port();
        return URI.create("ws://127.0.0.1:" + port);
    }

    public int port() {
        return port;
    }

    public void emit(String json) {
        for (WsContext ctx : sessions.values()) {
            try {
                ctx.send(json);
            } catch (Exception ignored) {
            }
        }
    }

    public void emitBookTicker(String symbol, BigDecimal bid, BigDecimal bidSize,
                                BigDecimal ask, BigDecimal askSize) {
        long updateId = updateIdSeq.incrementAndGet();
        String json = String.format(
                "{\"u\":%d,\"s\":\"%s\",\"b\":\"%s\",\"B\":\"%s\",\"a\":\"%s\",\"A\":\"%s\"}",
                updateId, symbol,
                bid.toPlainString(), bidSize.toPlainString(),
                ask.toPlainString(), askSize.toPlainString());
        String wrapped = "{\"stream\":\"" + symbol.toLowerCase() + "@bookTicker\",\"data\":" + json + "}";
        emit(wrapped);
    }

    public void disconnectAll() {
        for (WsContext ctx : sessions.values()) {
            try {
                ctx.closeSession();
            } catch (Exception ignored) {
            }
        }
        sessions.clear();
    }

    public int getConnectedClients() {
        return sessions.size();
    }

    public List<String> getReceivedMessages() {
        return List.copyOf(receivedMessages);
    }

    public void clearReceivedMessages() {
        receivedMessages.clear();
    }

    @Override
    public void close() {
        try {
            app.stop();
        } catch (Exception ignored) {
        }
    }
}