package io.stream.quotes.config;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public record AppConfig(
        URI binanceWsUrl,
        String dbPath,
        int httpPort,
        int batchMaxSize,
        Duration batchMaxWait,
        int historyMaxLimit,
        String rankingSource,
        URI coinGeckoUrl,
        Duration coinGeckoTimeout,
        Duration rankingRefreshInterval,
        String adminApiKey,
        List<String> corsAllowedOrigins,
        long httpAsyncTimeoutMs,
        URI binanceRestUrl,
        Duration binanceRestTimeout,
        Duration exchangeInfoTtl
) {

    public static final URI DEFAULT_BINANCE_WS = URI.create("wss://data-stream.binance.vision");
    public static final String DEFAULT_DB_PATH = "./data/quotes.db";
    public static final int DEFAULT_HTTP_PORT = 8080;
    public static final int DEFAULT_BATCH_MAX_SIZE = 100;
    public static final Duration DEFAULT_BATCH_MAX_WAIT = Duration.ofMillis(50);
    public static final int DEFAULT_HISTORY_MAX_LIMIT = 10_000;
    public static final String DEFAULT_RANKING_SOURCE = "coingecko";
    public static final URI DEFAULT_COINGECKO_URL = URI.create("https://api.coingecko.com");
    public static final Duration DEFAULT_COINGECKO_TIMEOUT = Duration.ofMillis(5_000);
    public static final Duration DEFAULT_RANKING_REFRESH = Duration.ZERO;
    public static final String DEFAULT_ADMIN_API_KEY = "";
    public static final long DEFAULT_HTTP_ASYNC_TIMEOUT_MS = 0L;
    // data-api.binance.vision — keyless market-data REST mirror, same family as the WS default
    // data-stream.binance.vision; lower geo-block (HTTP 451) risk than api.binance.com. Override via env.
    public static final URI DEFAULT_BINANCE_REST_URL = URI.create("https://data-api.binance.vision");
    public static final Duration DEFAULT_BINANCE_REST_TIMEOUT = Duration.ofMillis(5_000);
    public static final Duration DEFAULT_EXCHANGE_INFO_TTL = Duration.ofHours(1);

    public static final String RANKING_SOURCE_COINGECKO = "coingecko";
    public static final String RANKING_SOURCE_STATIC = "static";

    public AppConfig {
        String src = rankingSource == null ? "" : rankingSource.toLowerCase(Locale.ROOT);
        if (!RANKING_SOURCE_COINGECKO.equals(src) && !RANKING_SOURCE_STATIC.equals(src)) {
            throw new IllegalArgumentException(
                    "rankingSource must be 'coingecko' or 'static', got: " + rankingSource);
        }
        rankingSource = src;
    }

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
                        getOrDefault(env, "QUOTES_BATCH_MAX_WAIT_MS", Long.toString(DEFAULT_BATCH_MAX_WAIT.toMillis())))),
                Integer.parseInt(getOrDefault(env, "QUOTES_HISTORY_MAX_LIMIT", Integer.toString(DEFAULT_HISTORY_MAX_LIMIT))),
                getOrDefault(env, "QUOTES_RANKING_SOURCE", DEFAULT_RANKING_SOURCE),
                URI.create(getOrDefault(env, "QUOTES_COINGECKO_URL", DEFAULT_COINGECKO_URL.toString())),
                Duration.ofMillis(Long.parseLong(
                        getOrDefault(env, "QUOTES_COINGECKO_TIMEOUT_MS", Long.toString(DEFAULT_COINGECKO_TIMEOUT.toMillis())))),
                parseRefreshInterval(env),
                getOrDefault(env, "QUOTES_ADMIN_API_KEY", DEFAULT_ADMIN_API_KEY),
                Arrays.stream(getOrDefault(env, "QUOTES_CORS_ALLOWED_ORIGINS", "").split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList(),
                Long.parseLong(getOrDefault(env, "QUOTES_HTTP_TIMEOUT_MS",
                        Long.toString(DEFAULT_HTTP_ASYNC_TIMEOUT_MS))),
                URI.create(getOrDefault(env, "QUOTES_BINANCE_REST_URL", DEFAULT_BINANCE_REST_URL.toString())),
                Duration.ofMillis(Long.parseLong(getOrDefault(env, "QUOTES_BINANCE_REST_TIMEOUT_MS",
                        Long.toString(DEFAULT_BINANCE_REST_TIMEOUT.toMillis())))),
                Duration.ofMillis(Long.parseLong(getOrDefault(env, "QUOTES_EXCHANGE_INFO_TTL_MS",
                        Long.toString(DEFAULT_EXCHANGE_INFO_TTL.toMillis()))))
        );
    }

    private static Duration parseRefreshInterval(Map<String, String> env) {
        String hoursStr = env.get("QUOTES_RANKING_REFRESH_HOURS");
        String msStr = env.get("QUOTES_RANKING_REFRESH_MS");
        if (msStr != null && !msStr.isBlank()) {
            return Duration.ofMillis(Long.parseLong(msStr.trim()));
        }
        if (hoursStr != null && !hoursStr.isBlank()) {
            return Duration.ofHours(Long.parseLong(hoursStr.trim()));
        }
        return DEFAULT_RANKING_REFRESH;
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
                "AppConfig[binanceWsUrl=%s, dbPath=%s, httpPort=%d, batchMaxSize=%d, batchMaxWait=%dms, "
                        + "historyMaxLimit=%d, rankingSource=%s, coinGeckoUrl=%s, coinGeckoTimeout=%dms, "
                        + "rankingRefreshInterval=%dms, adminApiKey=%s, corsAllowedOrigins=%s, "
                        + "httpAsyncTimeoutMs=%d, binanceRestUrl=%s, binanceRestTimeout=%dms, "
                        + "exchangeInfoTtl=%dms]",
                binanceWsUrl, dbPath, httpPort, batchMaxSize, batchMaxWait.toMillis(),
                historyMaxLimit, rankingSource, coinGeckoUrl, coinGeckoTimeout.toMillis(),
                rankingRefreshInterval.toMillis(), adminApiKey.isEmpty() ? "(none)" : "(set)",
                corsAllowedOrigins.isEmpty() ? "(none)" : corsAllowedOrigins,
                httpAsyncTimeoutMs, binanceRestUrl, binanceRestTimeout.toMillis(),
                exchangeInfoTtl.toMillis());
    }
}