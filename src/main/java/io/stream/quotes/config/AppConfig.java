package io.stream.quotes.config;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

public record AppConfig(
        URI binanceWsUrl,
        String dbPath,
        int httpPort,
        int batchMaxSize,
        Duration batchMaxWait
) {

    public static final URI DEFAULT_BINANCE_WS = URI.create("wss://data-stream.binance.vision");
    public static final String DEFAULT_DB_PATH = "./data/quotes.db";
    public static final int DEFAULT_HTTP_PORT = 8080;
    public static final int DEFAULT_BATCH_MAX_SIZE = 100;
    public static final Duration DEFAULT_BATCH_MAX_WAIT = Duration.ofMillis(50);

    public static AppConfig fromEnv() {
        return fromEnv(System.getenv());
    }

    public static AppConfig fromEnv(Map<String, String> env) {
        return new AppConfig(
                URI.create(getOrDefault(env, "QUOTES_BINANCE_WS_URL", DEFAULT_BINANCE_WS.toString())),
                getOrDefault(env, "QUOTES_DB_PATH", DEFAULT_DB_PATH),
                Integer.parseInt(getOrDefault(env, "QUOTES_HTTP_PORT", Integer.toString(DEFAULT_HTTP_PORT))),
                Integer.parseInt(getOrDefault(env, "QUOTES_BATCH_MAX_SIZE", Integer.toString(DEFAULT_BATCH_MAX_SIZE))),
                Duration.ofMillis(Long.parseLong(
                        getOrDefault(env, "QUOTES_BATCH_MAX_WAIT_MS", Long.toString(DEFAULT_BATCH_MAX_WAIT.toMillis()))))
        );
    }

    private static String getOrDefault(Map<String, String> env, String key, String defaultValue) {
        String value = env.get(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim();
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT,
                "AppConfig[binanceWsUrl=%s, dbPath=%s, httpPort=%d, batchMaxSize=%d, batchMaxWait=%dms]",
                binanceWsUrl, dbPath, httpPort, batchMaxSize, batchMaxWait.toMillis());
    }
}