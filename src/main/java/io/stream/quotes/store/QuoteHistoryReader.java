package io.stream.quotes.store;

import io.stream.quotes.model.Quote;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class QuoteHistoryReader implements AutoCloseable {

    private static final String SELECT_SQL = """
            SELECT symbol, update_id, bid, bid_size, ask, ask_size, received_at_wall_ms
            FROM quotes
            WHERE symbol = ?
              AND received_at_wall_ms >= ?
              AND received_at_wall_ms <= ?
            ORDER BY received_at_wall_ms DESC
            LIMIT ?
            """;

    private final Connection connection;

    public QuoteHistoryReader(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    public List<Quote> read(String symbol, long fromMs, long toMs, int limit) throws SQLException {
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

    /**
     * No-op: this reader does not own the underlying Connection — the
     * {@link SqliteConnectionProvider} does.
     */
    @Override
    public void close() {
    }
}