package io.stream.quotes.store;

import io.stream.quotes.model.Quote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public final class SqliteQuoteWriter implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SqliteQuoteWriter.class);
    private static final String INSERT_SQL = """
            INSERT OR IGNORE INTO quotes
              (symbol, update_id, bid, bid_size, ask, ask_size, received_at_wall_ms)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

    private final String dbPath;
    private final int batchMaxSize;
    private final long batchMaxWaitNanos;
    private final LinkedBlockingQueue<Quote> queue;
    private volatile boolean running = false;
    private Connection connection;
    private Thread writerThread;

    public SqliteQuoteWriter(String dbPath, int batchMaxSize, Duration batchMaxWait) {
        this(dbPath, batchMaxSize, batchMaxWait, 10_000);
    }

    public SqliteQuoteWriter(String dbPath, int batchMaxSize, Duration batchMaxWait, int queueCapacity) {
        this.dbPath = dbPath;
        this.batchMaxSize = batchMaxSize;
        this.batchMaxWaitNanos = batchMaxWait.toNanos();
        this.queue = new LinkedBlockingQueue<>(queueCapacity);
    }

    public synchronized void start() throws SQLException {
        if (running) {
            return;
        }
        connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        SqliteSchema.applyPragmas(connection);
        SqliteSchema.createTables(connection);
        connection.setAutoCommit(false);
        running = true;
        writerThread = Thread.ofVirtual()
                .name("sqlite-writer")
                .start(this::runLoop);
        log.info("sqlite writer started, db={}", dbPath);
    }

    public boolean submit(Quote q) {
        if (!running) {
            return false;
        }
        return queue.offer(q);
    }

    public int queueSize() {
        return queue.size();
    }

    private void runLoop() {
        try (PreparedStatement stmt = connection.prepareStatement(INSERT_SQL)) {
            while (running || !queue.isEmpty()) {
                List<Quote> batch = collectBatch();
                if (!batch.isEmpty()) {
                    insertBatch(stmt, batch);
                }
            }
        } catch (Exception e) {
            log.error("sqlite writer loop failed", e);
        }
    }

    private List<Quote> collectBatch() throws InterruptedException {
        List<Quote> batch = new ArrayList<>(batchMaxSize);
        long deadlineNanos = System.nanoTime() + batchMaxWaitNanos;
        while (batch.size() < batchMaxSize) {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0) {
                break;
            }
            Quote q = queue.poll(remaining, TimeUnit.NANOSECONDS);
            if (q == null) {
                break;
            }
            batch.add(q);
        }
        if (batch.isEmpty() && !running) {
            queue.drainTo(batch);
        }
        return batch;
    }

    private void insertBatch(PreparedStatement stmt, List<Quote> batch) throws SQLException {
        for (Quote q : batch) {
            stmt.setString(1, q.symbol());
            stmt.setLong(2, q.updateId());
            stmt.setString(3, q.bid().toPlainString());
            stmt.setString(4, q.bidSize().toPlainString());
            stmt.setString(5, q.ask().toPlainString());
            stmt.setString(6, q.askSize().toPlainString());
            stmt.setLong(7, q.receivedAtWallMs());
            stmt.addBatch();
        }
        stmt.executeBatch();
        connection.commit();
    }

    @Override
    public synchronized void close() {
        if (!running) {
            return;
        }
        running = false;
        try {
            if (writerThread != null) {
                writerThread.join(5_000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            log.warn("error closing connection", e);
        }
        log.info("sqlite writer stopped");
    }
}