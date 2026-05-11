package io.stream.quotes.config;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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
    }

    @Test
    void overridesAllFieldsFromEnv() {
        AppConfig config = AppConfig.fromEnv(Map.of(
                "QUOTES_BINANCE_WS_URL", "ws://localhost:9000",
                "QUOTES_DB_PATH", "/tmp/test.db",
                "QUOTES_HTTP_PORT", "9090",
                "QUOTES_BATCH_MAX_SIZE", "50",
                "QUOTES_BATCH_MAX_WAIT_MS", "100",
                "QUOTES_HISTORY_MAX_LIMIT", "5000"));

        assertThat(config.binanceWsUrl()).isEqualTo(URI.create("ws://localhost:9000"));
        assertThat(config.dbPath()).isEqualTo("/tmp/test.db");
        assertThat(config.httpPort()).isEqualTo(9090);
        assertThat(config.batchMaxSize()).isEqualTo(50);
        assertThat(config.batchMaxWait()).isEqualTo(Duration.ofMillis(100));
        assertThat(config.historyMaxLimit()).isEqualTo(5000);
    }

    @Test
    void blankEnvValueFallsBackToDefault() {
        AppConfig config = AppConfig.fromEnv(Map.of(
                "QUOTES_HTTP_PORT", "   "));

        assertThat(config.httpPort()).isEqualTo(8080);
    }
}
