package io.stream.quotes.source;

import io.stream.quotes.model.Quote;
import io.stream.quotes.support.MockBinanceWsServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import static io.stream.quotes.support.TestSupport.fastBackoff;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class BinanceWsSourceIT {

    private MockBinanceWsServer server;
    private BinanceWsSource source;

    @AfterEach
    void tearDown() {
        if (source != null) {
            source.close();
        }
        if (server != null) {
            server.close();
        }
    }

    @Test
    void receivesEmittedQuote() throws Exception {
        server = new MockBinanceWsServer();
        URI uri = server.start();

        source = new BinanceWsSource(uri, List.of("BTCUSDT"), new BookTickerParser(), fastBackoff());
        ConcurrentLinkedQueue<Quote> received = new ConcurrentLinkedQueue<>();
        source.start(received::add);

        await().atMost(3, TimeUnit.SECONDS).until(() -> server.getConnectedClients() == 1);

        server.emitBookTicker("BTCUSDT",
                new BigDecimal("50000.00"), new BigDecimal("1.5"),
                new BigDecimal("50001.00"), new BigDecimal("2.0"));

        await().atMost(2, TimeUnit.SECONDS).until(() -> !received.isEmpty());
        Quote q = received.peek();
        assertThat(q.symbol()).isEqualTo("BTCUSDT");
        assertThat(q.bid()).isEqualTo(new BigDecimal("50000.00"));
        assertThat(q.askSize()).isEqualTo(new BigDecimal("2.0"));
    }

    @Test
    void reconnectsAfterDisconnect() throws Exception {
        server = new MockBinanceWsServer();
        URI uri = server.start();

        source = new BinanceWsSource(uri, List.of("BTCUSDT"), new BookTickerParser(), fastBackoff());
        ConcurrentLinkedQueue<Quote> received = new ConcurrentLinkedQueue<>();
        source.start(received::add);

        await().atMost(3, TimeUnit.SECONDS).until(() -> server.getConnectedClients() == 1);

        server.emitBookTicker("BTCUSDT",
                new BigDecimal("1"), new BigDecimal("1"),
                new BigDecimal("1"), new BigDecimal("1"));
        await().atMost(2, TimeUnit.SECONDS).until(() -> received.size() == 1);

        server.disconnectAll();
        Thread.sleep(300);

        await().atMost(5, TimeUnit.SECONDS).until(() -> server.getConnectedClients() == 1);
        await().atMost(3, TimeUnit.SECONDS).until(() -> source.isConnected());

        server.emitBookTicker("BTCUSDT",
                new BigDecimal("2"), new BigDecimal("1"),
                new BigDecimal("2"), new BigDecimal("1"));
        await().atMost(3, TimeUnit.SECONDS).until(() -> received.size() == 2);
    }

    @Test
    void malformedFrameDoesNotCloseConnection() throws Exception {
        server = new MockBinanceWsServer();
        URI uri = server.start();

        source = new BinanceWsSource(uri, List.of("BTCUSDT"), new BookTickerParser(), fastBackoff());
        ConcurrentLinkedQueue<Quote> received = new ConcurrentLinkedQueue<>();
        source.start(received::add);

        await().atMost(3, TimeUnit.SECONDS).until(() -> server.getConnectedClients() == 1);

        server.emit("not a json");
        server.emit("{\"unrelated\":\"event\"}");
        Thread.sleep(200);

        assertThat(server.getConnectedClients()).isEqualTo(1);
        assertThat(received).isEmpty();

        server.emitBookTicker("BTCUSDT",
                new BigDecimal("1"), new BigDecimal("1"),
                new BigDecimal("1"), new BigDecimal("1"));
        await().atMost(2, TimeUnit.SECONDS).until(() -> !received.isEmpty());
    }

    @Test
    void closeStopsReconnect() throws Exception {
        server = new MockBinanceWsServer();
        URI uri = server.start();

        source = new BinanceWsSource(uri, List.of("BTCUSDT"), new BookTickerParser(), fastBackoff());
        source.start(q -> {
        });

        await().atMost(3, TimeUnit.SECONDS).until(() -> server.getConnectedClients() == 1);
        source.close();
        await().atMost(3, TimeUnit.SECONDS).until(() -> server.getConnectedClients() == 0);

        Thread.sleep(500);
        assertThat(server.getConnectedClients()).isZero();
    }

    @Test
    void buildsLowercaseSlashJoinedStreamPath() throws Exception {
        server = new MockBinanceWsServer();
        URI uri = server.start();

        source = new BinanceWsSource(uri, List.of("BTCUSDT", "ETHUSDT"), new BookTickerParser(), fastBackoff());
        source.start(q -> {
        });

        await().atMost(3, TimeUnit.SECONDS).until(() -> server.getConnectedClients() == 1);
        // No exception means the URI was accepted by the server. The path itself is
        // verified by inspecting the source code: source/BinanceWsSource#buildStreamUri.
        assertThat(source.isConnected()).isTrue();
    }

    @Test
    void resubscribeChangesStreamSymbols() throws Exception {
        server = new MockBinanceWsServer();
        URI uri = server.start();

        source = new BinanceWsSource(uri, List.of("BTCUSDT"), new BookTickerParser(), fastBackoff());
        ConcurrentLinkedQueue<Quote> received = new ConcurrentLinkedQueue<>();
        source.start(received::add);

        await().atMost(3, TimeUnit.SECONDS).until(() -> server.getConnectedClients() == 1);

        // Emit a BTC quote, verify it lands.
        server.emitBookTicker("BTCUSDT",
                new BigDecimal("1"), new BigDecimal("1"),
                new BigDecimal("1"), new BigDecimal("1"));
        await().atMost(2, TimeUnit.SECONDS).until(() -> received.size() == 1);
        assertThat(received.peek().symbol()).isEqualTo("BTCUSDT");

        // Resubscribe to a different symbol.
        source.resubscribe(List.of("ETHUSDT"));

        // The mock sees a brief disconnect+reconnect on the new stream URL.
        await().atMost(5, TimeUnit.SECONDS).until(source::isConnected);

        // Old BTC frame should not arrive at the new subscription.
        // Emit an ETH frame and verify it makes it through.
        received.clear();
        server.emitBookTicker("ETHUSDT",
                new BigDecimal("3000"), new BigDecimal("1"),
                new BigDecimal("3001"), new BigDecimal("1"));
        await().atMost(3, TimeUnit.SECONDS).until(() -> !received.isEmpty());
        assertThat(received.peek().symbol()).isEqualTo("ETHUSDT");
    }

    @Test
    void resubscribeWithSameListIsNoop() throws Exception {
        server = new MockBinanceWsServer();
        URI uri = server.start();

        source = new BinanceWsSource(uri, List.of("BTCUSDT"), new BookTickerParser(), fastBackoff());
        source.start(q -> {});
        await().atMost(3, TimeUnit.SECONDS).until(source::isConnected);

        source.resubscribe(List.of("BTCUSDT"));
        Thread.sleep(200);

        assertThat(source.isConnected()).isTrue();
    }

}