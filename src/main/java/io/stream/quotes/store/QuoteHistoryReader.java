package io.stream.quotes.store;

import io.stream.quotes.model.Quote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteConfig;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class QuoteHistoryReader implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(QuoteHistoryReader.class);
    private static final String SELECT_SQL = """
            SELECT symbol, update_id, bid, bid_size, ask, ask_size, received_at_wall_ms
            FROM quotes
            WHERE symbol = ?
              AND received_at_wall_ms >= ?
              AND received_at_wall_ms <= ?
            ORDER BY received_at_wall_ms DESC
            LIMIT ?
            """;

    private final String dbPath;
    private Connection connection;

    public QuoteHistoryReader(String dbPath) {
        this.dbPath = dbPath;
    }

    public synchronized void open() throws SQLException {
        if (connection != null) {
            return;
        }
        SQLiteConfig cfg = new SQLiteConfig();
        cfg.setReadOnly(true);
        connection = cfg.createConnection("jdbc:sqlite:" + dbPath);
        log.info("history reader opened on {}", dbPath);
    }

    public List<Quote> read(String symbol, long fromMs, long toMs, int limit) throws SQLException {
        if (connection == null) {
            throw new IllegalStateException("reader not opened");
        }
        try (PreparedStatement stmt = connection.prepareStatement(SELECT_SQL)) {
            stmt.setString(1, symbol);
            stmt.setLong(2, fromMs);
            stmt.setLong(3, toMs);
            stmt.setInt(4, limit);
            try (ResultSet rs = stmt.executeQuery()) {
                List<Quote> out = new ArrayList<>(Math.min(limit, 1024));
                while (rs.next()) {
                    out.add(new Quote(
                            rs.getString("symbol"),
                            new BigDecimal(rs.getString("bid")),
                            new BigDecimal(rs.getString("bid_size")),
                            new BigDecimal(rs.getString("ask")),
                            new BigDecimal(rs.getString("ask_size")),
                            rs.getLong("update_id"),
                            rs.getLong("received_at_wall_ms")
                    ));
                }
                return out;
            }
        }
    }

    @Override
    public synchronized void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                log.warn("error closing history reader", e);
            }
            connection = null;
        }
    }
}