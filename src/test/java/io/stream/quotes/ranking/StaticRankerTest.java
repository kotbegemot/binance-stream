package io.stream.quotes.ranking;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class StaticRankerTest {

    @Test
    void loadsBundledFallbackFromClasspath() {
        StaticRanker ranker = StaticRanker.fromClasspath();

        List<String> top = ranker.top10Symbols();
        assertThat(top).hasSize(10);
        assertThat(top).contains("BTCUSDT", "ETHUSDT");
        assertThat(top).allMatch(s -> s.endsWith("USDT"));
    }

    @Test
    void filterRulesIncludeKnownStablecoinsAndWrapped() {
        StaticRanker ranker = StaticRanker.fromClasspath();
        FilterRules rules = ranker.filterRules();

        assertThat(rules.isStablecoin("USDT")).isTrue();
        assertThat(rules.isStablecoin("usdc")).isTrue();
        assertThat(rules.isStablecoin("BTC")).isFalse();

        assertThat(rules.isWrapped("WBTC")).isTrue();
        assertThat(rules.isWrapped("steth")).isTrue();
        assertThat(rules.isWrapped("ETH")).isFalse();

        assertThat(rules.isFiltered("USDT")).isTrue();
        assertThat(rules.isFiltered("WBTC")).isTrue();
        assertThat(rules.isFiltered("BTC")).isFalse();
    }

    @Test
    void loadsFromCustomYamlStream() {
        String yaml = """
                symbols:
                  - AAA
                  - BBB
                stablecoin_filter:
                  - USDT
                wrapped_filter:
                  - WBTC
                """;
        StaticRanker ranker = StaticRanker.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));

        assertThat(ranker.top10Symbols()).containsExactly("AAA", "BBB");
        assertThat(ranker.filterRules().isFiltered("USDT")).isTrue();
        assertThat(ranker.filterRules().isFiltered("WBTC")).isTrue();
    }

    @Test
    void truncatesToTenWhenMoreProvided() {
        StringBuilder yaml = new StringBuilder("symbols:\n");
        for (int i = 1; i <= 15; i++) {
            yaml.append("  - SYM").append(i).append("\n");
        }
        StaticRanker ranker = StaticRanker.load(new ByteArrayInputStream(yaml.toString().getBytes(StandardCharsets.UTF_8)));

        assertThat(ranker.top10Symbols()).hasSize(10);
        assertThat(ranker.top10Symbols().get(0)).isEqualTo("SYM1");
        assertThat(ranker.top10Symbols().get(9)).isEqualTo("SYM10");
    }

    @Test
    void rejectsEmptySymbolList() {
        String yaml = "symbols: []\n";
        assertThatIllegalArgumentException().isThrownBy(() ->
                StaticRanker.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void missingFilterSectionsYieldEmptyRules() {
        String yaml = """
                symbols:
                  - BTC
                """;
        StaticRanker ranker = StaticRanker.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));

        assertThat(ranker.filterRules().stablecoins()).isEmpty();
        assertThat(ranker.filterRules().wrapped()).isEmpty();
        assertThat(ranker.filterRules().isFiltered("USDT")).isFalse();
    }
}