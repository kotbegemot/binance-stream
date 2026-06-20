package io.stream.quotes.store;

import io.stream.quotes.model.Quote;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static io.stream.quotes.support.TestSupport.quote;
import static io.stream.quotes.support.TestSupport.rowCount;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class SqliteQuoteWriterTest {

    private SqliteConnectionProvider provider;
    private SqliteQuoteWriter writer;
    private String dbPath;

    @AfterEach
    void tearDown() {
        if (writer != null) {
            writer.close();
        }
        if (provider != null) {
            provider.close();
        }
    }

    private void setupWriter(Path tmp, Duration batchWait) throws Exception {
        dbPath = tmp.resolve("quotes.db").toString();
        provider = new SqliteConnectionProvider(dbPath);
        provider.open();
        writer = new SqliteQuoteWriter(provider.quoteWriterConnection(), 100, batchWait);
        writer.start();
    }

    @Test
    void persistsSingleQuoteReadableViaSql(@TempDir Path tmp) throws Exception {
        setupWriter(tmp, Duration.ofMillis(50));

        Quote q = quote("BTCUSDT", 1L, "50000.00", "1.5", "50001.00", "2.0", 1715472000123L);
        assertThat(writer.submit(q)).isTrue();

        await().atMost(2, TimeUnit.SECONDS).until(() -> rowCount(dbPath) == 1);

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT * FROM quotes")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("symbol")).isEqualTo("BTCUSDT");
            assertThat(rs.getLong("update_id")).isEqualTo(1L);
            assertThat(new BigDecimal(rs.getString("bid"))).isEqualTo(new BigDecimal("50000.00"));
            assertThat(new BigDecimal(rs.getString("bid_size"))).isEqualTo(new BigDecimal("1.5"));
            assertThat(new BigDecimal(rs.getString("ask"))).isEqualTo(new BigDecimal("50001.00"));
            assertThat(new BigDecimal(rs.getString("ask_size"))).isEqualTo(new BigDecimal("2.0"));
            assertThat(rs.getLong("received_at_wall_ms")).isEqualTo(1715472000123L);
        }
    }

    @Test
    void batchInsertPersistsThousandQuotes(@TempDir Path tmp) throws Exception {
        setupWriter(tmp, Duration.ofMillis(50));

        for (int i = 0; i < 1000; i++) {
            assertThat(writer.submit(quote("BTCUSDT", i, "1", "1", "1", "1", 0))).isTrue();
        }

        await().atMost(10, TimeUnit.SECONDS).until(() -> rowCount(dbPath) == 1000);
    }

    @Test
    void walModeActiveAfterStart(@TempDir Path tmp) throws Exception {
        setupWriter(tmp, Duration.ofMillis(50));
        writer.submit(quote("BTC", 1L, "1", "1", "1", "1", 0));
        await().atMost(2, TimeUnit.SECONDS).until(() -> rowCount(dbPath) == 1);

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("PRAGMA journal_mode")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualToIgnoringCase("wal");
        }
    }

    @Test
    void schemaHasExpectedColumns(@TempDir Path tmp) throws Exception {
        setupWriter(tmp, Duration.ofMillis(50));

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("PRAGMA table_info(quotes)")) {
            List<String> cols = new ArrayList<>();
            while (rs.next()) {
                cols.add(rs.getString("name"));
            }
            assertThat(cols).containsExactlyInAnyOrder(
                    "symbol", "update_id", "bid", "bid_size",
                    "ask", "ask_size", "received_at_wall_ms");
        }
    }

    @Test
    void closeDrainsRemainingQueue(@TempDir Path tmp) throws Exception {
        setupWriter(tmp, Duration.ofMillis(500));

        for (int i = 0; i < 500; i++) {
            writer.submit(quote("BTC", i, "1", "1", "1", "1", 0));
        }
        writer.close();

        assertThat(rowCount(dbPath)).isEqualTo(500);
    }

    @Test
    void dedupByPrimaryKeySymbolUpdateId(@TempDir Path tmp) throws Exception {
        setupWriter(tmp, Duration.ofMillis(50));

        writer.submit(quote("BTCUSDT", 1L, "100", "1", "101", "1", 0));
        writer.submit(quote("BTCUSDT", 1L, "999", "9", "999", "9", 0));
        writer.submit(quote("BTCUSDT", 2L, "200", "2", "201", "2", 0));

        await().atMost(2, TimeUnit.SECONDS).until(() -> rowCount(dbPath) == 2);

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT bid FROM quotes WHERE symbol='BTCUSDT' AND update_id=1")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("100");
        }
    }

    @Test
    void bigDecimalPrecisionRoundTrip(@TempDir Path tmp) throws Exception {
        setupWriter(tmp, Duration.ofMillis(50));

        writer.submit(quote("BTC", 1L, "0.00000001", "0.00000002", "123456789.12345678", "0.5", 0));
        await().atMost(2, TimeUnit.SECONDS).until(() -> rowCount(dbPath) == 1);

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT bid, bid_size, ask FROM quotes")) {
            assertThat(rs.next()).isTrue();
            assertThat(new BigDecimal(rs.getString("bid"))).isEqualTo(new BigDecimal("0.00000001"));
            assertThat(new BigDecimal(rs.getString("bid_size"))).isEqualTo(new BigDecimal("0.00000002"));
            assertThat(new BigDecimal(rs.getString("ask"))).isEqualTo(new BigDecimal("123456789.12345678"));
        }
    }

    @Test
    void submitAfterCloseReturnsFalse(@TempDir Path tmp) throws Exception {
        setupWriter(tmp, Duration.ofMillis(50));
        writer.close();

        assertThat(writer.submit(quote("BTC", 1L, "1", "1", "1", "1", 0))).isFalse();
    }

    @Test
    void concurrentSubmitsFromMultipleThreads(@TempDir Path tmp) throws Exception {
        setupWriter(tmp, Duration.ofMillis(50));

        int threads = 8;
        int perThread = 250;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            final int base = t * perThread;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        writer.submit(quote("SYM" + (base + i), 1L, "1", "1", "1", "1", 0));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        await().atMost(10, TimeUnit.SECONDS).until(() -> rowCount(dbPath) == threads * perThread);
    }

    @Test
    void zeroBatchWaitStillPersistsWhileRunning(@TempDir Path tmp) throws Exception {
        dbPath = tmp.resolve("quotes.db").toString();
        provider = new SqliteConnectionProvider(dbPath);
        provider.open();
        writer = new SqliteQuoteWriter(provider.quoteWriterConnection(), 100, Duration.ZERO);
        writer.start();

        for (int i = 0; i < 50; i++) {
            assertThat(writer.submit(quote("BTCUSDT", i, "1", "1", "1", "1", 0))).isTrue();
        }

        await().atMost(5, TimeUnit.SECONDS).until(() -> rowCount(dbPath) == 50);
    }

}