package io.stream.quotes.pipeline;

import io.stream.quotes.source.BinanceWsSource;
import io.stream.quotes.source.BookTickerParser;
import io.stream.quotes.store.LatestQuoteStore;
import io.stream.quotes.store.SqliteConnectionProvider;
import io.stream.quotes.store.SqliteQuoteWriter;
import io.stream.quotes.support.MockBinanceWsServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static io.stream.quotes.support.TestSupport.fastBackoff;
import static io.stream.quotes.support.TestSupport.quote;
import static io.stream.quotes.support.TestSupport.rowCount;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class QuotePipelineIT {

    private MockBinanceWsServer mockServer;
    private QuotePipeline pipeline;
    private SqliteConnectionProvider provider;

    @AfterEach
    void tearDown() {
        if (pipeline != null) {
            pipeline.close();
        }
        if (mockServer != null) {
            mockServer.close();
        }
        if (provider != null) {
            provider.close();
        }
    }

    @Test
    void fullFlowFromWsToStoreAndSqlite(@TempDir Path tmp) throws Exception {
        mockServer = new MockBinanceWsServer();
        URI mockUri = mockServer.start();

        LatestQuoteStore store = new LatestQuoteStore();
        String dbPath = tmp.resolve("quotes.db").toString();
        provider = new SqliteConnectionProvider(dbPath);
        provider.open();
        SqliteQuoteWriter writer = new SqliteQuoteWriter(provider.quoteWriterConnection(), 100, Duration.ofMillis(50));
        BinanceWsSource source = new BinanceWsSource(
                mockUri, List.of("BTCUSDT"), new BookTickerParser(), fastBackoff());

        pipeline = new QuotePipeline(source, store, writer);
        pipeline.start();

        // Wait for BOTH sides to confirm the WS handshake. The mock's
        // server-side onConnect can fire microseconds before the client
        // Listener is fully attached, so without this we sometimes emit
        // into a connection that drops the frame.
        await().atMost(5, TimeUnit.SECONDS).until(() ->
                mockServer.getConnectedClients() == 1 && source.isConnected());

        // Emit, and if the first frame races the listener bring-up, keep
        // re-emitting until the store sees it. Each emit gets a fresh
        // monotonically-increasing update_id from the mock so dedup is a
        // no-op for the second/third attempt.
        await().atMost(5, TimeUnit.SECONDS).until(() -> {
            mockServer.emitBookTicker("BTCUSDT",
                    new BigDecimal("50000.00"), new BigDecimal("1.5"),
                    new BigDecimal("50001.00"), new BigDecimal("2.0"));
            return store.size() == 1;
        });
        assertThat(store.get("BTCUSDT")).isPresent();
        assertThat(store.get("BTCUSDT").orElseThrow().bid())
                .isEqualTo(new BigDecimal("50000.00"));

        await().atMost(5, TimeUnit.SECONDS).until(() -> rowCount(dbPath) >= 1);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT symbol, bid, ask FROM quotes ORDER BY update_id DESC LIMIT 1")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("symbol")).isEqualTo("BTCUSDT");
            assertThat(new BigDecimal(rs.getString("bid"))).isEqualTo(new BigDecimal("50000.00"));
            assertThat(new BigDecimal(rs.getString("ask"))).isEqualTo(new BigDecimal("50001.00"));
        }
    }

    @Test
    void closeShutsDownBothSourceAndWriter(@TempDir Path tmp) throws Exception {
        mockServer = new MockBinanceWsServer();
        URI mockUri = mockServer.start();

        LatestQuoteStore store = new LatestQuoteStore();
        String dbPath = tmp.resolve("quotes.db").toString();
        provider = new SqliteConnectionProvider(dbPath);
        provider.open();
        SqliteQuoteWriter writer = new SqliteQuoteWriter(provider.quoteWriterConnection(), 100, Duration.ofMillis(50));
        BinanceWsSource source = new BinanceWsSource(
                mockUri, List.of("BTCUSDT"), new BookTickerParser(), fastBackoff());

        pipeline = new QuotePipeline(source, store, writer);
        pipeline.start();
        await().atMost(3, TimeUnit.SECONDS).until(() -> mockServer.getConnectedClients() == 1);

        pipeline.close();
        pipeline = null;

        await().atMost(3, TimeUnit.SECONDS).until(() -> mockServer.getConnectedClients() == 0);
        assertThat(writer.submit(quote("BTC", 1L, 0L))).isFalse();
    }
}