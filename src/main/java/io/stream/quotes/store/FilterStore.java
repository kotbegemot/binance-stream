package io.stream.quotes.store;

import io.stream.quotes.ranking.FilterRules;
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
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Persistent registry of filtered tickers (e.g. stablecoins + wrapped tokens
 * we want to ignore when picking the top instruments). Backed by the
 * {@code filtered_tickers} SQLite table.
 */
public final class FilterStore {

    private static final Logger log = LoggerFactory.getLogger(FilterStore.class);

    private static final String INSERT_FILTERED = """
            INSERT OR IGNORE INTO filtered_tickers (ticker, added_at_wall_ms, source)
            VALUES (?, ?, ?)
            """;
    private static final String DELETE_FILTERED = "DELETE FROM filtered_tickers WHERE ticker = ?";
    private static final String SELECT_FILTERED = "SELECT ticker FROM filtered_tickers ORDER BY ticker";

    private final Connection connection;
    private final Clock clock;

    public FilterStore(Connection connection, Clock clock) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.clock = clock;
    }

    public synchronized Set<String> loadInitial(FilterRules yamlSeed) throws SQLException {
        Set<String> existing = readFiltered();
        if (!existing.isEmpty()) {
            return existing;
        }
        Set<String> union = new LinkedHashSet<>();
        if (yamlSeed != null) {
            union.addAll(yamlSeed.stablecoins());
            union.addAll(yamlSeed.wrapped());
        }
        long now = clock.millis();
        connection.setAutoCommit(false);
        try (PreparedStatement ins = connection.prepareStatement(INSERT_FILTERED)) {
            for (String t : union) {
                if (t == null || t.isBlank()) {
                    continue;
                }
                ins.setString(1, normalize(t));
                ins.setLong(2, now);
                ins.setString(3, "yaml");
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
        return readFiltered();
    }

    public synchronized Diff applyPatch(List<String> add, List<String> remove) throws SQLException {
        Set<String> before = readFiltered();
        long now = clock.millis();
        List<String> actuallyAdded = new ArrayList<>();
        List<String> actuallyRemoved = new ArrayList<>();

        connection.setAutoCommit(false);
        try (PreparedStatement ins = connection.prepareStatement(INSERT_FILTERED);
             PreparedStatement del = connection.prepareStatement(DELETE_FILTERED)) {

            for (String t : add) {
                String norm = normalize(t);
                ins.setString(1, norm);
                ins.setLong(2, now);
                ins.setString(3, "admin");
                ins.executeUpdate();
                if (!before.contains(norm)) {
                    actuallyAdded.add(norm);
                }
            }
            for (String t : remove) {
                String norm = normalize(t);
                del.setString(1, norm);
                int rows = del.executeUpdate();
                if (rows > 0) {
                    actuallyRemoved.add(norm);
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

    public synchronized Diff replace(List<String> newTickers, String source) throws SQLException {
        Set<String> desired = new LinkedHashSet<>();
        for (String t : newTickers) {
            desired.add(normalize(t));
        }
        Set<String> existing = new HashSet<>(readFiltered());
        List<String> toAdd = new ArrayList<>();
        for (String t : desired) {
            if (!existing.contains(t)) {
                toAdd.add(t);
            }
        }
        List<String> toRemove = new ArrayList<>();
        for (String t : existing) {
            if (!desired.contains(t)) {
                toRemove.add(t);
            }
        }
        long now = clock.millis();

        connection.setAutoCommit(false);
        try (PreparedStatement ins = connection.prepareStatement(INSERT_FILTERED);
             PreparedStatement del = connection.prepareStatement(DELETE_FILTERED)) {

            for (String t : toAdd) {
                ins.setString(1, t);
                ins.setLong(2, now);
                ins.setString(3, source);
                ins.executeUpdate();
            }
            for (String t : toRemove) {
                del.setString(1, t);
                del.executeUpdate();
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

    /**
     * Returns the persisted filter set, upper-cased — the canonical case for
     * API responses and {@code CoinGeckoRanker} filtering. The table stores
     * lowercase internally for compatibility with {@code FilterRules}; this
     * helper centralises the case conversion so callers don't have to.
     */
    public synchronized Set<String> currentSetUpperCase() throws SQLException {
        return readFiltered().stream()
                .map(s -> s.toUpperCase(Locale.ROOT))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private Set<String> readFiltered() throws SQLException {
        try (PreparedStatement st = connection.prepareStatement(SELECT_FILTERED);
             ResultSet rs = st.executeQuery()) {
            Set<String> out = new TreeSet<>();
            while (rs.next()) {
                out.add(rs.getString(1));
            }
            return out;
        }
    }

    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
