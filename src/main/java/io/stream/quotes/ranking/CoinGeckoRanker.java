package io.stream.quotes.ranking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

public final class CoinGeckoRanker implements InstrumentRanker {

    private static final Logger log = LoggerFactory.getLogger(CoinGeckoRanker.class);
    private static final String MARKETS_PATH =
            "/api/v3/coins/markets?vs_currency=usd&order=market_cap_desc&per_page=30&page=1";
    private static final int TOP_N = 10;

    private final URI baseUrl;
    private final Duration timeout;
    private final InstrumentRanker fallback;
    private final Supplier<Set<String>> filterSupplier;
    private final TradableSymbols tradable;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    public CoinGeckoRanker(URI baseUrl,
                           Duration timeout,
                           InstrumentRanker fallback,
                           Supplier<Set<String>> filterSupplier) {
        this(baseUrl, timeout, fallback, filterSupplier, () -> Set.of());
    }

    public CoinGeckoRanker(URI baseUrl,
                           Duration timeout,
                           InstrumentRanker fallback,
                           Supplier<Set<String>> filterSupplier,
                           TradableSymbols tradable) {
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.fallback = Objects.requireNonNull(fallback, "fallback");
        this.filterSupplier = Objects.requireNonNull(filterSupplier, "filterSupplier");
        this.tradable = Objects.requireNonNull(tradable, "tradable");
        this.mapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();
    }

    @Override
    public List<String> top10Symbols() {
        try {
            URI target = URI.create(stripTrailingSlash(baseUrl.toString()) + MARKETS_PATH);
            HttpRequest request = HttpRequest.newBuilder(target)
                    .timeout(timeout)
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                log.warn("CoinGecko returned non-2xx status {} - falling back", status);
                return fallback.top10Symbols();
            }

            JsonNode root = mapper.readTree(response.body());
            if (root == null || !root.isArray() || root.isEmpty()) {
                log.warn("CoinGecko response was not a non-empty array - falling back");
                return fallback.top10Symbols();
            }

            Set<String> filterSet = normalizeFilter(filterSupplier.get());
            Set<String> ok = tradable.usdtTradingSymbols();
            List<String> result = new ArrayList<>(TOP_N);
            for (JsonNode node : root) {
                JsonNode symbolNode = node.get("symbol");
                if (symbolNode == null || !symbolNode.isTextual()) {
                    continue;
                }
                String ticker = symbolNode.asText().toUpperCase(Locale.ROOT);
                if (ticker.isEmpty() || filterSet.contains(ticker)) {
                    continue;
                }
                String symbol = ticker + "USDT";
                if (!ok.isEmpty() && !ok.contains(symbol)) {
                    continue;
                }
                result.add(symbol);
                if (result.size() >= TOP_N) {
                    break;
                }
            }

            if (result.size() < TOP_N) {
                log.warn("CoinGecko produced only {} symbols after filtering (need {}) - falling back",
                        result.size(), TOP_N);
                return fallback.top10Symbols();
            }
            return List.copyOf(result);
        } catch (Exception e) {
            log.warn("CoinGecko fetch failed: {} - falling back", e.toString());
            return fallback.top10Symbols();
        }
    }

    private static Set<String> normalizeFilter(Set<String> src) {
        if (src == null || src.isEmpty()) {
            return Set.of();
        }
        return src.stream()
                .map(s -> s.toUpperCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}