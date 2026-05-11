package io.stream.quotes.config;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppConfigTest {

    @Test
    void defaultsWhenEnvEmpty() {
        AppConfig config = AppConfig.fromEnv(Map.of());

        assertThat(config.binanceWsUrl()).isEqualTo(URI.create("wss://data-stream.binance.vision"));
        assertThat(config.dbPath()).isEqualTo("./data/quotes.db");
        assertThat(config.httpPort()).isEqualTo(8080);
        assertThat(config.batchMaxSize()).isEqualTo(100);
        assertThat(config.batchMaxWait()).isEqualTo(Duration.ofMillis(50));
        assertThat(config.historyMaxLimit()).isEqualTo(10_000);
        assertThat(config.rankingSource()).isEqualTo("coingecko");
        assertThat(config.coinGeckoUrl()).isEqualTo(URI.create("https://api.coingecko.com"));
        assertThat(config.coinGeckoTimeout()).isEqualTo(Duration.ofMillis(5_000));
        assertThat(config.rankingRefreshInterval()).isEqualTo(Duration.ZERO);
    }

    @Test
    void overridesAllFieldsFromEnv() {
        AppConfig config = AppConfig.fromEnv(Map.ofEntries(
                Map.entry("QUOTES_BINANCE_WS_URL", "ws://localhost:9000"),
                Map.entry("QUOTES_DB_PATH", "/tmp/test.db"),
                Map.entry("QUOTES_HTTP_PORT", "9090"),
                Map.entry("QUOTES_BATCH_MAX_SIZE", "50"),
                Map.entry("QUOTES_BATCH_MAX_WAIT_MS", "100"),
                Map.entry("QUOTES_HISTORY_MAX_LIMIT", "5000"),
                Map.entry("QUOTES_RANKING_SOURCE", "static"),
                Map.entry("QUOTES_COINGECKO_URL", "http://localhost:1234"),
                Map.entry("QUOTES_COINGECKO_TIMEOUT_MS", "1500")));

        assertThat(config.binanceWsUrl()).isEqualTo(URI.create("ws://localhost:9000"));
        assertThat(config.dbPath()).isEqualTo("/tmp/test.db");
        assertThat(config.httpPort()).isEqualTo(9090);
        assertThat(config.batchMaxSize()).isEqualTo(50);
        assertThat(config.batchMaxWait()).isEqualTo(Duration.ofMillis(100));
        assertThat(config.historyMaxLimit()).isEqualTo(5000);
        assertThat(config.rankingSource()).isEqualTo("static");
        assertThat(config.coinGeckoUrl()).isEqualTo(URI.create("http://localhost:1234"));
        assertThat(config.coinGeckoTimeout()).isEqualTo(Duration.ofMillis(1500));
    }

    @Test
    void blankEnvValueFallsBackToDefault() {
        AppConfig config = AppConfig.fromEnv(Map.of(
                "QUOTES_HTTP_PORT", "   "));

        assertThat(config.httpPort()).isEqualTo(8080);
    }

    @Test
    void rankingSourceIsCaseInsensitive() {
        AppConfig config = AppConfig.fromEnv(Map.of("QUOTES_RANKING_SOURCE", "Static"));
        assertThat(config.rankingSource()).isEqualTo("static");
    }

    @Test
    void refreshHoursParsesIntoDuration() {
        AppConfig config = AppConfig.fromEnv(Map.of("QUOTES_RANKING_REFRESH_HOURS", "6"));
        assertThat(config.rankingRefreshInterval()).isEqualTo(Duration.ofHours(6));
    }

    @Test
    void refreshMsOverridesHours() {
        AppConfig config = AppConfig.fromEnv(Map.of(
                "QUOTES_RANKING_REFRESH_HOURS", "6",
                "QUOTES_RANKING_REFRESH_MS", "500"));
        assertThat(config.rankingRefreshInterval()).isEqualTo(Duration.ofMillis(500));
    }

    @Test
    void rejectsUnknownRankingSource() {
        assertThatThrownBy(() ->
                AppConfig.fromEnv(Map.of("QUOTES_RANKING_SOURCE", "binance-volume")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rankingSource");
    }
}