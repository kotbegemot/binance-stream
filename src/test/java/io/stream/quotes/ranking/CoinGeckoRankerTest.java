package io.stream.quotes.ranking;

import io.stream.quotes.support.MockCoinGeckoServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class CoinGeckoRankerTest {

    private MockCoinGeckoServer server;
    private URI baseUrl;
    private StaticRanker staticRanker;
    private Supplier<Set<String>> filterSupplier;
    private String fixtureJson;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockCoinGeckoServer();
        baseUrl = server.start();
        staticRanker = StaticRanker.fromClasspath();
        FilterRules rules = staticRanker.filterRules();
        Set<String> union = new HashSet<>();
        union.addAll(rules.stablecoins());
        union.addAll(rules.wrapped());
        filterSupplier = () -> union;
        fixtureJson = Files.readString(Path.of("src/test/resources/fixtures/coingecko-markets.json"));
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void happyPathReturnsFilteredTopTenUsdtPairs() {
        server.respondWith(200, fixtureJson);
        CoinGeckoRanker ranker = new CoinGeckoRanker(baseUrl, Duration.ofSeconds(2), staticRanker, filterSupplier);

        List<String> top = ranker.top10Symbols();

        assertThat(top).hasSize(10);
        assertThat(top).allSatisfy(s -> assertThat(s).endsWith("USDT"));
        assertThat(top).doesNotContain("USDTUSDT", "USDCUSDT", "DAIUSDT", "WBTCUSDT");
    }

    @Test
    void filterExcludesStablecoinsAndWrapped() {
        server.respondWith(200, fixtureJson);
        CoinGeckoRanker ranker = new CoinGeckoRanker(baseUrl, Duration.ofSeconds(2), staticRanker, filterSupplier);

        List<String> top = ranker.top10Symbols();

        // From fixture order (after filtering USDT/USDC/WBTC/DAI):
        // BTC, ETH, BNB, SOL, XRP, DOGE, ADA, AVAX, TRX, SHIB
        assertThat(top).containsExactly(
                "BTCUSDT",
                "ETHUSDT",
                "BNBUSDT",
                "SOLUSDT",
                "XRPUSDT",
                "DOGEUSDT",
                "ADAUSDT",
                "AVAXUSDT",
                "TRXUSDT",
                "SHIBUSDT"
        );
    }

    @Test
    void runtimeFilterChangeReflectedInNextCall() {
        server.respondWith(200, fixtureJson);
        Set<String> mutable = new HashSet<>(Set.of("USDT", "USDC", "WBTC", "DAI"));
        Supplier<Set<String>> mutableSupplier = () -> mutable;
        CoinGeckoRanker ranker = new CoinGeckoRanker(baseUrl, Duration.ofSeconds(2), staticRanker, mutableSupplier);

        List<String> firstCall = ranker.top10Symbols();
        assertThat(firstCall).contains("BTCUSDT");

        mutable.add("BTC");
        server.respondWith(200, fixtureJson);
        List<String> secondCall = ranker.top10Symbols();
        assertThat(secondCall).doesNotContain("BTCUSDT");
    }

    @Test
    void fivexxFallsBackToStaticRanker() {
        server.respondWith(503, "service unavailable");
        CoinGeckoRanker ranker = new CoinGeckoRanker(baseUrl, Duration.ofSeconds(2), staticRanker, filterSupplier);

        List<String> top = ranker.top10Symbols();

        assertThat(top).isEqualTo(staticRanker.top10Symbols());
    }

    @Test
    void timeoutFallsBackToStaticRanker() {
        server.respondWith(200, fixtureJson, Duration.ofSeconds(2));
        CoinGeckoRanker ranker = new CoinGeckoRanker(baseUrl, Duration.ofMillis(250), staticRanker, filterSupplier);

        List<String> top = ranker.top10Symbols();

        assertThat(top).isEqualTo(staticRanker.top10Symbols());
    }

    @Test
    void malformedJsonFallsBackToStaticRanker() {
        server.respondWith(200, "not json");
        CoinGeckoRanker ranker = new CoinGeckoRanker(baseUrl, Duration.ofSeconds(2), staticRanker, filterSupplier);

        List<String> top = ranker.top10Symbols();

        assertThat(top).isEqualTo(staticRanker.top10Symbols());
    }

    @Test
    void emptyArrayFallsBackToStaticRanker() {
        server.respondWith(200, "[]");
        CoinGeckoRanker ranker = new CoinGeckoRanker(baseUrl, Duration.ofSeconds(2), staticRanker, filterSupplier);

        List<String> top = ranker.top10Symbols();

        assertThat(top).isEqualTo(staticRanker.top10Symbols());
    }

    @Test
    void skipsCandidatesNotTradableOnBinance() {
        server.respondWith(200, fixtureJson);
        CoinGeckoRanker ranker = new CoinGeckoRanker(
                baseUrl, Duration.ofSeconds(2), staticRanker, filterSupplier,
                () -> Set.of("BTCUSDT", "ETHUSDT", "BNBUSDT", "SOLUSDT", "XRPUSDT",
                        "DOGEUSDT", "ADAUSDT", "TRXUSDT", "SHIBUSDT", "DOTUSDT"));

        List<String> top = ranker.top10Symbols();

        assertThat(top).hasSize(10);
        assertThat(top).contains("DOTUSDT");
        assertThat(top).doesNotContain("AVAXUSDT");
    }
}