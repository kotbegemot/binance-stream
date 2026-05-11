package io.stream.quotes.support;

import io.stream.quotes.model.Quote;
import io.stream.quotes.source.BackoffPolicy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.Random;

/** Shared test fixtures so individual tests don't redeclare these helpers. */
public final class TestSupport {

    private TestSupport() {
    }

    public static BackoffPolicy fastBackoff() {
        return new BackoffPolicy(
                Duration.ofMillis(50),
                Duration.ofMillis(500),
                2.0,
                0.0,
                new Random(0));
    }

    public static long rowCount(String dbPath) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM quotes")) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    public static Quote quote(String symbol, long updateId) {
        return quote(symbol, updateId, 0L);
    }

    public static Quote quote(String symbol, long updateId, long receivedAtMs) {
        return new Quote(symbol,
                BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ONE,
                updateId, receivedAtMs);
    }

    public static Quote quote(String symbol, long updateId,
                              String bid, String bidSize,
                              String ask, String askSize,
                              long receivedAtMs) {
        return new Quote(symbol,
                new BigDecimal(bid), new BigDecimal(bidSize),
                new BigDecimal(ask), new BigDecimal(askSize),
                updateId, receivedAtMs);
    }
}
