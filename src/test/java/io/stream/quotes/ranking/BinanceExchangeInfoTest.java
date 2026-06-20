package io.stream.quotes.ranking;

import io.stream.quotes.support.MockBinanceRestServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BinanceExchangeInfoTest {

    private MockBinanceRestServer server;
    private URI baseUrl;
    private String fixtureJson;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockBinanceRestServer();
        baseUrl = server.start();
        fixtureJson = Files.readString(Path.of("src/test/resources/fixtures/binance-exchangeinfo.json"));
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void returnsOnlyUsdtTradingSymbols() {
        server.respondWith(200, fixtureJson);
        BinanceExchangeInfo info = new BinanceExchangeInfo(baseUrl, Duration.ofSeconds(2), Duration.ofHours(1));

        Set<String> symbols = info.usdtTradingSymbols();

        assertThat(symbols).contains("BTCUSDT", "ETHUSDT");
        assertThat(symbols).doesNotContain("ETHBTC", "FOOUSDT");
    }

    @Test
    void cachesWithinTtlAndHitsServerOnce() {
        server.respondWith(200, fixtureJson);
        BinanceExchangeInfo info = new BinanceExchangeInfo(baseUrl, Duration.ofSeconds(2), Duration.ofHours(1));

        Set<String> first = info.usdtTradingSymbols();
        Set<String> second = info.usdtTradingSymbols();

        assertThat(first).contains("BTCUSDT");
        assertThat(second).contains("BTCUSDT");
        assertThat(server.requestCount()).isEqualTo(1);
    }

    @Test
    void fivexxReturnsEmptySet() {
        server.respondWith(503, "service unavailable");
        BinanceExchangeInfo info = new BinanceExchangeInfo(baseUrl, Duration.ofSeconds(2), Duration.ofHours(1));

        assertThat(info.usdtTradingSymbols()).isEmpty();
    }

    @Test
    void malformedBodyReturnsEmptySet() {
        server.respondWith(200, "not json");
        BinanceExchangeInfo info = new BinanceExchangeInfo(baseUrl, Duration.ofSeconds(2), Duration.ofHours(1));

        assertThat(info.usdtTradingSymbols()).isEmpty();
    }
}
