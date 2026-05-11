package io.stream.quotes.store;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public final class SqliteSchema {

    public static final String CREATE_QUOTES = """
            CREATE TABLE IF NOT EXISTS quotes (
              symbol              TEXT    NOT NULL,
              update_id           INTEGER NOT NULL,
              bid                 TEXT    NOT NULL,
              bid_size            TEXT    NOT NULL,
              ask                 TEXT    NOT NULL,
              ask_size            TEXT    NOT NULL,
              received_at_wall_ms INTEGER NOT NULL,
              PRIMARY KEY (symbol, update_id)
            ) WITHOUT ROWID
            """;

    public static final String CREATE_HISTORY_INDEX = """
            CREATE INDEX IF NOT EXISTS idx_quotes_symbol_recv
              ON quotes(symbol, received_at_wall_ms DESC)
            """;

    private SqliteSchema() {
    }

    public static void applyPragmas(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA journal_mode = WAL");
            s.execute("PRAGMA synchronous = NORMAL");
            s.execute("PRAGMA temp_store = MEMORY");
            s.execute("PRAGMA mmap_size = 268435456");
            s.execute("PRAGMA cache_size = -64000");
        }
    }

    public static void createTables(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(CREATE_QUOTES);
            s.execute(CREATE_HISTORY_INDEX);
        }
    }
}