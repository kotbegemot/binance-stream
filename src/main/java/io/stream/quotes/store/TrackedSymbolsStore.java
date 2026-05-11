package io.stream.quotes.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Persistent registry of tracked symbols. Backed by SQLite tables
 * {@code tracked_symbols} and {@code admin_removed_symbols}.
 * <p>
 * Admin-removed symbols act as tombstones — once admin removes a symbol it
 * stays in the tombstone table forever (until an explicit re-add) so that
 * the seeder/refresh loop never silently re-adds it.
 */
public final class TrackedSymbolsStore {

    private static final Logger log = LoggerFactory.getLogger(TrackedSymbolsStore.class);

    private static final String INSERT_TRACKED = """
            INSERT OR IGNORE INTO tracked_symbols (symbol, added_at_wall_ms, source)
            VALUES (?, ?, ?)
            """;
    private static final String DELETE_TRACKED = "DELETE FROM tracked_symbols WHERE symbol = ?";
    private static final String INSERT_REMOVED = """
            INSERT OR IGNORE INTO admin_removed_symbols (symbol, removed_at_wall_ms)
            VALUES (?, ?)
            """;
    private static final String DELETE_REMOVED = "DELETE FROM admin_removed_symbols WHERE symbol = ?";
    private static final String SELECT_TRACKED = "SELECT symbol FROM tracked_symbols ORDER BY symbol";
    private static final String SELECT_REMOVED = "SELECT symbol FROM admin_removed_symbols";

    private final Connection connection;
    private final Clock clock;

    public TrackedSymbolsStore(Connection connection, Clock clock) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.clock = clock;
    }

    public synchronized List<String> loadInitial(List<String> seedIfEmpty, String source) throws SQLException {
        List<String> existing = readTracked();
        if (!existing.isEmpty()) {
            return existing;
        }
        Set<String> removed = readRemoved();
        long now = clock.millis();
        connection.setAutoCommit(false);
        try (PreparedStatement ins = connection.prepareStatement(INSERT_TRACKED)) {
            for (String sym : seedIfEmpty) {
                if (sym == null || sym.isBlank()) {
                    continue;
                }
                if (removed.contains(sym)) {
                    continue;
                }
                ins.setString(1, sym);
                ins.setLong(2, now);
                ins.setString(3, source);
                ins.addBatch();
            }
            ins.executeBatch();
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
        return readTracked();
    }

    public synchronized Diff applyPatch(List<String> add, List<String> remove) throws SQLException {
        Set<String> before = new HashSet<>(readTracked());
        long now = clock.millis();
        List<String> actuallyAdded = new ArrayList<>();
        List<String> actuallyRemoved = new ArrayList<>();

        connection.setAutoCommit(false);
        try (PreparedStatement insTracked = connection.prepareStatement(INSERT_TRACKED);
             PreparedStatement delTracked = connection.prepareStatement(DELETE_TRACKED);
             PreparedStatement insRemoved = connection.prepareStatement(INSERT_REMOVED);
             PreparedStatement delRemoved = connection.prepareStatement(DELETE_REMOVED)) {

            for (String sym : add) {
                insTracked.setString(1, sym);
                insTracked.setLong(2, now);
                insTracked.setString(3, "admin");
                insTracked.executeUpdate();
                delRemoved.setString(1, sym);
                delRemoved.executeUpdate();
                if (!before.contains(sym)) {
                    actuallyAdded.add(sym);
                }
            }
            for (String sym : remove) {
                delTracked.setString(1, sym);
                int rows = delTracked.executeUpdate();
                insRemoved.setString(1, sym);
                insRemoved.setLong(2, now);
                insRemoved.executeUpdate();
                if (rows > 0) {
                    actuallyRemoved.add(sym);
                }
            }
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
        return new Diff(List.copyOf(actuallyAdded), List.copyOf(actuallyRemoved));
    }

    public synchronized Diff replace(List<String> newSymbols, String source) throws SQLException {
        Set<String> desired = new LinkedHashSet<>(newSymbols);
        Set<String> existing = new HashSet<>(readTracked());
        List<String> toAdd = new ArrayList<>();
        for (String sym : desired) {
            if (!existing.contains(sym)) {
                toAdd.add(sym);
            }
        }
        List<String> toRemove = new ArrayList<>();
        for (String sym : existing) {
            if (!desired.contains(sym)) {
                toRemove.add(sym);
            }
        }
        long now = clock.millis();

        connection.setAutoCommit(false);
        try (PreparedStatement insTracked = connection.prepareStatement(INSERT_TRACKED);
             PreparedStatement delTracked = connection.prepareStatement(DELETE_TRACKED);
             PreparedStatement insRemoved = connection.prepareStatement(INSERT_REMOVED)) {

            for (String sym : toAdd) {
                insTracked.setString(1, sym);
                insTracked.setLong(2, now);
                insTracked.setString(3, source);
                insTracked.executeUpdate();
            }
            for (String sym : toRemove) {
                delTracked.setString(1, sym);
                delTracked.executeUpdate();
                insRemoved.setString(1, sym);
                insRemoved.setLong(2, now);
                insRemoved.executeUpdate();
            }
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
        return new Diff(List.copyOf(toAdd), List.copyOf(toRemove));
    }

    public synchronized List<String> current() throws SQLException {
        return readTracked();
    }

    public synchronized Set<String> removedSymbols() throws SQLException {
        return readRemoved();
    }

    /**
     * Atomic single-pass read of both tracked and admin-removed sets for the
     * periodic refresh scheduler. Without this, the scheduler issues two
     * separate synchronized calls and there's a race window where admin can
     * mutate between the two reads.
     */
    public synchronized RefreshSnapshot snapshotForRefresh() throws SQLException {
        return new RefreshSnapshot(readTracked(), readRemoved());
    }

    public record RefreshSnapshot(List<String> tracked, Set<String> adminRemoved) {
    }

    private List<String> readTracked() throws SQLException {
        try (PreparedStatement st = connection.prepareStatement(SELECT_TRACKED);
             ResultSet rs = st.executeQuery()) {
            List<String> out = new ArrayList<>();
            while (rs.next()) {
                out.add(rs.getString(1));
            }
            return out;
        }
    }

    private Set<String> readRemoved() throws SQLException {
        try (PreparedStatement st = connection.prepareStatement(SELECT_REMOVED);
             ResultSet rs = st.executeQuery()) {
            Set<String> out = new TreeSet<>();
            while (rs.next()) {
                out.add(rs.getString(1));
            }
            return out;
        }
    }
}
