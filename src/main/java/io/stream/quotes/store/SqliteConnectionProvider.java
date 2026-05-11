package io.stream.quotes.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Owns the lifecycle of all SQLite connections used by the service.
 * <p>
 * Each store (writer, history reader, tracked-symbols, filter) gets a
 * dedicated connection. Pragmas and schema are applied centrally — no store
 * has to repeat that work, and every connection inherits the same per-session
 * settings (synchronous, cache_size, mmap_size). Lifecycle is single
 * `open()` / `close()` pair from {@link io.stream.quotes.Main}.
 * <p>
 * Connection count by design (separate writer connections cooperate via
 * SQLite WAL file-level locking; same JDBC Connection is NOT thread-safe,
 * so the high-frequency writer must not share its Connection with the
 * admin-store writers):
 * <ul>
 *   <li>{@link #quoteWriterConnection()} — high-frequency batched inserts
 *   from the {@code SqliteQuoteWriter} thread, auto-commit OFF.</li>
 *   <li>{@link #trackedSymbolsConnection()} / {@link #filterStoreConnection()}
 *   — rare admin mutations from HTTP request threads; each runs short
 *   single-statement or small-batch transactions.</li>
 *   <li>{@link #historyReaderConnection()} — read-only via
 *   {@link SQLiteConfig#setReadOnly(boolean)}.</li>
 * </ul>
 */
public final class SqliteConnectionProvider implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SqliteConnectionProvider.class);

    private final String dbPath;
    private Connection quoteWriter;
    private Connection trackedSymbols;
    private Connection filterStore;
    private Connection historyReader;
    private boolean opened = false;

    public SqliteConnectionProvider(String dbPath) {
        this.dbPath = dbPath;
    }

    public synchronized void open() throws SQLException {
        if (opened) {
            return;
        }
        String url = "jdbc:sqlite:" + dbPath;

        // Open the quote-writer connection first — it applies pragmas and
        // creates schema. Other connections then attach to the same DB file
        // and inherit the persistent settings (e.g. journal_mode=WAL).
        quoteWriter = DriverManager.getConnection(url);
        SqliteSchema.applyPragmas(quoteWriter);
        SqliteSchema.createTables(quoteWriter);
        quoteWriter.setAutoCommit(false);

        trackedSymbols = DriverManager.getConnection(url);
        SqliteSchema.applyPragmas(trackedSymbols);

        filterStore = DriverManager.getConnection(url);
        SqliteSchema.applyPragmas(filterStore);

        SQLiteConfig readerCfg = new SQLiteConfig();
        readerCfg.setReadOnly(true);
        historyReader = readerCfg.createConnection(url);

        opened = true;
        log.info("sqlite connection provider opened on {}", dbPath);
    }

    public synchronized Connection quoteWriterConnection() {
        ensureOpen();
        return quoteWriter;
    }

    public synchronized Connection trackedSymbolsConnection() {
        ensureOpen();
        return trackedSymbols;
    }

    public synchronized Connection filterStoreConnection() {
        ensureOpen();
        return filterStore;
    }

    public synchronized Connection historyReaderConnection() {
        ensureOpen();
        return historyReader;
    }

    @Override
    public synchronized void close() {
        if (!opened) {
            return;
        }
        opened = false;
        closeQuietly("quote-writer", quoteWriter);
        closeQuietly("tracked-symbols", trackedSymbols);
        closeQuietly("filter-store", filterStore);
        closeQuietly("history-reader", historyReader);
        log.info("sqlite connection provider closed");
    }

    private void ensureOpen() {
        if (!opened) {
            throw new IllegalStateException("provider not opened");
        }
    }

    private static void closeQuietly(String label, Connection c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (SQLException e) {
            log.warn("error closing {} connection", label, e);
        }
    }
}