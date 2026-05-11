package io.stream.quotes.support;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketListener;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class MockBinanceWsServerTest {

    private MockBinanceWsServer server;
    private WebSocketClient client;

    @AfterEach
    void tearDown() throws Exception {
        if (client != null) {
            client.stop();
        }
        if (server != null) {
            server.close();
        }
    }

    @Test
    void startsAndAcceptsConnection() throws Exception {
        server = new MockBinanceWsServer();
        URI uri = server.start();
        client = new WebSocketClient();
        client.start();

        RecordingListener listener = new RecordingListener();
        client.connect(listener, URI.create(uri + "/stream"));

        assertThat(listener.connected.await(3, TimeUnit.SECONDS)).isTrue();
        await().atMost(2, TimeUnit.SECONDS).until(() -> server.getConnectedClients() == 1);
    }

    @Test
    void emitsBookTickerToAllClients() throws Exception {
        server = new MockBinanceWsServer();
        URI uri = server.start();
        client = new WebSocketClient();
        client.start();

        RecordingListener listener = new RecordingListener();
        client.connect(listener, URI.create(uri + "/stream"));
        assertThat(listener.connected.await(3, TimeUnit.SECONDS)).isTrue();

        server.emitBookTicker("BTCUSDT",
                new BigDecimal("50000.00"), new BigDecimal("1.5"),
                new BigDecimal("50001.00"), new BigDecimal("2.0"));

        await().atMost(2, TimeUnit.SECONDS).until(() -> !listener.messages.isEmpty());
        assertThat(listener.messages.peek())
                .contains("\"s\":\"BTCUSDT\"")
                .contains("\"b\":\"50000.00\"")
                .contains("\"a\":\"50001.00\"");
    }

    @Test
    void disconnectAllClosesSessions() throws Exception {
        server = new MockBinanceWsServer();
        URI uri = server.start();
        client = new WebSocketClient();
        client.start();

        RecordingListener listener = new RecordingListener();
        client.connect(listener, URI.create(uri + "/stream"));
        assertThat(listener.connected.await(3, TimeUnit.SECONDS)).isTrue();
        await().atMost(2, TimeUnit.SECONDS).until(() -> server.getConnectedClients() == 1);

        server.disconnectAll();
        await().atMost(2, TimeUnit.SECONDS).until(() -> server.getConnectedClients() == 0);
    }

    private static final class RecordingListener implements WebSocketListener {
        final CountDownLatch connected = new CountDownLatch(1);
        final ConcurrentLinkedQueue<String> messages = new ConcurrentLinkedQueue<>();

        @Override
        public void onWebSocketConnect(Session session) {
            connected.countDown();
        }

        @Override
        public void onWebSocketText(String message) {
            messages.add(message);
        }

        @Override
        public void onWebSocketBinary(byte[] payload, int offset, int len) {
        }

        @Override
        public void onWebSocketClose(int statusCode, String reason) {
        }

        @Override
        public void onWebSocketError(Throwable cause) {
        }
    }
}