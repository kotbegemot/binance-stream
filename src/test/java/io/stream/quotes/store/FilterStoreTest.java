package io.stream.quotes.store;

import io.stream.quotes.ranking.FilterRules;
import io.stream.quotes.support.TestClocks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FilterStoreTest {

    private SqliteConnectionProvider provider;
    private FilterStore store;

    @AfterEach
    void tearDown() {
        if (provider != null) {
            provider.close();
        }
    }

    private void openStore(Path tmp) throws Exception {
        String db = tmp.resolve("f.db").toString();
        provider = new SqliteConnectionProvider(db);
        provider.open();
        store = new FilterStore(provider.filterStoreConnection(), TestClocks.fixedClock());
    }

    @Test
    void loadInitialOnEmptyDbSeedsFromYamlUnion(@TempDir Path tmp) throws Exception {
        openStore(tmp);
        FilterRules rules = new FilterRules(List.of("USDT"), List.of("WBTC"));

        Set<String> got = store.loadInitial(rules);

        assertThat(got).containsExactlyInAnyOrder("usdt", "wbtc");
    }

    @Test
    void loadInitialOnNonEmptyDbReturnsExistingIgnoringYamlSeed(@TempDir Path tmp) throws Exception {
        openStore(tmp);
        store.loadInitial(new FilterRules(List.of("USDT"), List.of("WBTC")));

        Set<String> got = store.loadInitial(new FilterRules(List.of("USDC"), List.of("WETH")));

        assertThat(got).containsExactlyInAnyOrder("usdt", "wbtc");
    }

    @Test
    void applyPatchAddRemove(@TempDir Path tmp) throws Exception {
        openStore(tmp);
        store.loadInitial(new FilterRules(List.of("USDT"), List.of("WBTC")));

        Diff diff = store.applyPatch(List.of("USDC"), List.of("WBTC"));

        assertThat(diff.added()).containsExactly("usdc");
        assertThat(diff.removed()).containsExactly("wbtc");
        assertThat(store.currentSetUpperCase()).containsExactlyInAnyOrder("USDT", "USDC");
    }

    @Test
    void replaceReplacesAll(@TempDir Path tmp) throws Exception {
        openStore(tmp);
        store.loadInitial(new FilterRules(List.of("USDT"), List.of("WBTC")));

        Diff diff = store.replace(List.of("DAI", "FRAX"), "admin");

        assertThat(diff.added()).containsExactlyInAnyOrder("dai", "frax");
        assertThat(diff.removed()).containsExactlyInAnyOrder("usdt", "wbtc");
        assertThat(store.currentSetUpperCase()).containsExactlyInAnyOrder("DAI", "FRAX");
    }

    @Test
    void currentSetUpperCaseReturnsUppercase(@TempDir Path tmp) throws Exception {
        openStore(tmp);
        store.loadInitial(new FilterRules(List.of("USDT"), List.of("WBTC")));

        Set<String> upper = store.currentSetUpperCase();

        assertThat(upper).containsExactlyInAnyOrder("USDT", "WBTC");
    }
}