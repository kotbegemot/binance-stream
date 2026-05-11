package io.stream.quotes.store;

import io.stream.quotes.support.TestClocks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TrackedSymbolsStoreTest {

    private SqliteConnectionProvider provider;
    private TrackedSymbolsStore store;

    @AfterEach
    void tearDown() {
        if (provider != null) {
            provider.close();
        }
    }

    private void openStore(Path tmp) throws Exception {
        String db = tmp.resolve("t.db").toString();
        provider = new SqliteConnectionProvider(db);
        provider.open();
        store = new TrackedSymbolsStore(provider.trackedSymbolsConnection(), TestClocks.fixedClock());
    }

    @Test
    void loadInitialOnEmptyDbSeedsAndReturnsList(@TempDir Path tmp) throws Exception {
        openStore(tmp);

        List<String> got = store.loadInitial(List.of("BTCUSDT", "ETHUSDT", "SOLUSDT"), "seed");

        assertThat(got).containsExactly("BTCUSDT", "ETHUSDT", "SOLUSDT");
        assertThat(store.current()).containsExactly("BTCUSDT", "ETHUSDT", "SOLUSDT");
    }

    @Test
    void loadInitialOnNonEmptyDbReturnsExistingIgnoringSeed(@TempDir Path tmp) throws Exception {
        openStore(tmp);
        store.loadInitial(List.of("BTCUSDT"), "seed");

        List<String> got = store.loadInitial(List.of("XRPUSDT", "DOGEUSDT"), "seed");

        assertThat(got).containsExactly("BTCUSDT");
    }

    @Test
    void loadInitialSkipsAdminRemoved(@TempDir Path tmp) throws Exception {
        String db = tmp.resolve("t.db").toString();
        // Pre-create schema and pre-seed admin_removed.
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement s = c.createStatement()) {
            SqliteSchema.createTables(c);
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO admin_removed_symbols (symbol, removed_at_wall_ms) VALUES (?, ?)")) {
                ps.setString(1, "BTCUSDT");
                ps.setLong(2, 1L);
                ps.executeUpdate();
            }
        }

        provider = new SqliteConnectionProvider(db);
        provider.open();
        store = new TrackedSymbolsStore(provider.trackedSymbolsConnection(), TestClocks.fixedClock());

        List<String> got = store.loadInitial(List.of("BTCUSDT", "ETHUSDT"), "seed");

        assertThat(got).containsExactly("ETHUSDT");
        assertThat(store.removedSymbols()).containsExactly("BTCUSDT");
    }

    @Test
    void applyPatchAddRemoveAtomicAndReturnsDiff(@TempDir Path tmp) throws Exception {
        openStore(tmp);
        store.loadInitial(List.of("BTCUSDT", "ETHUSDT"), "seed");

        Diff diff = store.applyPatch(List.of("SOLUSDT"), List.of("BTCUSDT"));

        assertThat(diff.added()).containsExactly("SOLUSDT");
        assertThat(diff.removed()).containsExactly("BTCUSDT");
        assertThat(store.current()).containsExactly("ETHUSDT", "SOLUSDT");
        assertThat(store.removedSymbols()).containsExactly("BTCUSDT");
    }

    @Test
    void replaceMigratesRemovedSymbolsToAdminRemoved(@TempDir Path tmp) throws Exception {
        openStore(tmp);
        store.loadInitial(List.of("BTCUSDT", "ETHUSDT", "SOLUSDT"), "seed");

        Diff diff = store.replace(List.of("BTCUSDT", "DOGEUSDT"), "ranker");

        assertThat(diff.added()).containsExactly("DOGEUSDT");
        assertThat(diff.removed()).containsExactlyInAnyOrder("ETHUSDT", "SOLUSDT");
        assertThat(store.current()).containsExactly("BTCUSDT", "DOGEUSDT");
        assertThat(store.removedSymbols()).containsExactlyInAnyOrder("ETHUSDT", "SOLUSDT");
    }

    @Test
    void applyPatchIdempotency(@TempDir Path tmp) throws Exception {
        openStore(tmp);
        store.loadInitial(List.of("BTCUSDT"), "seed");

        Diff first = store.applyPatch(List.of("ETHUSDT"), List.of());
        Diff second = store.applyPatch(List.of("ETHUSDT"), List.of());

        assertThat(first.added()).containsExactly("ETHUSDT");
        assertThat(second.added()).isEmpty();
        assertThat(second.removed()).isEmpty();
        assertThat(store.current()).containsExactly("BTCUSDT", "ETHUSDT");
    }
}