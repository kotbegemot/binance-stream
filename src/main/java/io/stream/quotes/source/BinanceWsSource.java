package io.stream.quotes.source;

import io.stream.quotes.model.Quote;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketListener;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public final class BinanceWsSource implements QuoteSource {

    private static final Logger log = LoggerFactory.getLogger(BinanceWsSource.class);
    private static final long CONNECT_TIMEOUT_SECONDS = 10;

    private final URI baseUrl;
    private volatile List<String> symbols;
    private final BookTickerParser parser;
    private final BackoffPolicy backoff;
    private final WebSocketClient client;

    private volatile boolean running = false;
    private final AtomicReference<Session> currentSession = new AtomicReference<>();
    private Consumer<Quote> onQuote;
    private Thread reconnectThread;

    public BinanceWsSource(URI baseUrl, List<String> symbols, BookTickerParser parser, BackoffPolicy backoff) {
        this.baseUrl = baseUrl;
        this.symbols = List.copyOf(symbols);
        this.parser = parser;
        this.backoff = backoff;
        this.client = new WebSocketClient();
    }

    /**
     * Replace the subscribed symbol set. The current WS session is closed; the
     * existing reconnect loop will then open a new connection with the new
     * stream URI (built from the updated symbols field).
     */
    public synchronized void resubscribe(List<String> newSymbols) {
        if (newSymbols == null || newSymbols.isEmpty()) {
            return;
        }
        List<String> snapshot = List.copyOf(newSymbols);
        if (snapshot.equals(this.symbols)) {
            return;
        }
        this.symbols = snapshot;
        log.info("ws source resubscribing to {} symbols", snapshot.size());
        closeCurrentSession();
    }

    @Override
    public synchronized void start(Consumer<Quote> onQuote) throws Exception {
        if (running) {
            return;
        }
        this.onQuote = onQuote;
        client.start();
        running = true;
        reconnectThread = Thread.ofVirtual()
                .name("binance-ws-source")
                .start(this::connectionLoop);
    }

    @Override
    public boolean isConnected() {
        Session s = currentSession.get();
        return s != null && s.isOpen();
    }

    private void connectionLoop() {
        while (running) {
            URI uri = buildStreamUri();
            CountDownLatch closed = new CountDownLatch(1);
            try {
                Session session = client.connect(new Listener(closed), uri)
                        .get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                currentSession.set(session);
                backoff.reset();
                log.info("ws connected: {}", uri);
                closed.await();
                currentSession.set(null);
            } catch (Exception e) {
                log.warn("ws connect failed", e);
            }
            if (!running) {
                return;
            }
            Duration sleep = backoff.next();
            log.info("reconnecting in {}ms", sleep.toMillis());
            try {
                Thread.sleep(sleep);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private URI buildStreamUri() {
        String streams = symbols.stream()
                .map(s -> s.toLowerCase() + "@bookTicker")
                .collect(Collectors.joining("/"));
        String base = baseUrl.toString();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + "/stream?streams=" + streams);
    }

    @Override
    public synchronized void close() {
        if (!running) {
            return;
        }
        running = false;
        closeCurrentSession();
        if (reconnectThread != null) {
            reconnectThread.interrupt();
            try {
                reconnectThread.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            client.stop();
        } catch (Exception e) {
            log.warn("error stopping ws client", e);
        }
        log.info("ws source closed");
    }

    /**
     * CAS-out the current session and close it. If between getAndSet and close
     * the connectionLoop already established a new session, we will not close
     * the new one — only the one we captured.
     */
    private void closeCurrentSession() {
        Session captured = currentSession.getAndSet(null);
        if (captured != null) {
            try {
                captured.close();
            } catch (Exception ignored) {
                // session may already be half-closed (peer dropped, reconnect in flight) — nothing actionable
            }
        }
    }

    private final class Listener implements WebSocketListener {

        private final CountDownLatch closed;

        Listener(CountDownLatch closed) {
            this.closed = closed;
        }

        @Override
        public void onWebSocketConnect(Session session) {
        }

        @Override
        public void onWebSocketText(String message) {
            try {
                parser.parse(message.getBytes(StandardCharsets.UTF_8), System.currentTimeMillis())
                        .ifPresent(onQuote);
            } catch (Exception e) {
                log.warn("error processing ws text frame", e);
            }
        }

        @Override
        public void onWebSocketBinary(byte[] payload, int offset, int len) {
        }

        @Override
        public void onWebSocketClose(int statusCode, String reason) {
            log.info("ws closed: code={} reason={}", statusCode, reason);
            closed.countDown();
        }

        @Override
        public void onWebSocketError(Throwable cause) {
            log.warn("ws error", cause);
            closed.countDown();
        }
    }
}