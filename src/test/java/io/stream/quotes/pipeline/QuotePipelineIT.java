package io.stream.quotes.pipeline;

import io.stream.quotes.source.BackoffPolicy;
import io.stream.quotes.source.BinanceWsSource;
import io.stream.quotes.source.BookTickerParser;
import io.stream.quotes.store.LatestQuoteStore;
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
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class QuotePipelineIT {

    private MockBinanceWsServer mockServer;
    private QuotePipeline pipeline;

    @AfterEach
    void tearDown() {
        if (pipeline != null) {
            pipeline.close();
        }
        if (mockServer != null) {
            mockServer.close();
        }
    }

    @Test
    void fullFlowFromWsToStoreAndSqlite(@TempDir Path tmp) throws Exception {
        mockServer = new MockBinanceWsServer();
        URI mockUri = mockServer.start();

        LatestQuoteStore store = new LatestQuoteStore();
        String dbPath = tmp.resolve("quotes.db").toString();
        SqliteQuoteWriter writer = new SqliteQuoteWriter(dbPath, 100, Duration.ofMillis(50));
        BinanceWsSource source = new BinanceWsSource(
                mockUri, List.of("BTCUSDT"), new BookTickerParser(), fastBackoff());

        pipeline = new QuotePipeline(source, store, writer);
        pipeline.start();

        await().atMost(3, TimeUnit.SECONDS).until(() -> mockServer.getConnectedClients() == 1);

        mockServer.emitBookTicker("BTCUSDT",
                new BigDecimal("50000.00"), new BigDecimal("1.5"),
                new BigDecimal("50001.00"), new BigDecimal("2.0"));

        await().atMost(3, TimeUnit.SECONDS).until(() -> store.size() == 1);
        assertThat(store.get("BTCUSDT")).isPresent();
        assertThat(store.get("BTCUSDT").orElseThrow().bid())
                .isEqualTo(new BigDecimal("50000.00"));

        await().atMost(3, TimeUnit.SECONDS).until(() -> rowCount(dbPath) >= 1);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT symbol, bid, ask FROM quotes")) {
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
        SqliteQuoteWriter writer = new SqliteQuoteWriter(dbPath, 100, Duration.ofMillis(50));
        BinanceWsSource source = new BinanceWsSource(
                mockUri, List.of("BTCUSDT"), new BookTickerParser(), fastBackoff());

        pipeline = new QuotePipeline(source, store, writer);
        pipeline.start();
        await().atMost(3, TimeUnit.SECONDS).until(() -> mockServer.getConnectedClients() == 1);

        pipeline.close();
        pipeline = null;

        await().atMost(3, TimeUnit.SECONDS).until(() -> mockServer.getConnectedClients() == 0);
        assertThat(writer.submit(quoteOf("BTC"))).isFalse();
    }

    private static io.stream.quotes.model.Quote quoteOf(String symbol) {
        return new io.stream.quotes.model.Quote(
                symbol, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, 1L, 0L);
    }

    private static long rowCount(String dbPath) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM quotes")) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    private static BackoffPolicy fastBackoff() {
        return new BackoffPolicy(
                Duration.ofMillis(50),
                Duration.ofMillis(500),
                2.0,
                0.0,
                new Random(0));
    }
}