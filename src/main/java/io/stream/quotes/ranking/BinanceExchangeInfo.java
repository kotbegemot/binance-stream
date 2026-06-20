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
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public final class BinanceExchangeInfo implements TradableSymbols {

    private static final Logger log = LoggerFactory.getLogger(BinanceExchangeInfo.class);
    private static final String EXCHANGE_INFO_PATH = "/api/v3/exchangeInfo";

    private final URI baseUrl;
    private final Duration timeout;
    private final Duration cacheTtl;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    private volatile Set<String> snapshot = null;
    private volatile long expiryNanos = 0L;

    public BinanceExchangeInfo(URI binanceRestUrl, Duration timeout, Duration cacheTtl) {
        this.baseUrl = Objects.requireNonNull(binanceRestUrl, "binanceRestUrl");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.cacheTtl = Objects.requireNonNull(cacheTtl, "cacheTtl");
        this.mapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();
    }

    @Override
    public Set<String> usdtTradingSymbols() {
        Set<String> snap = snapshot;
        if (snap != null && System.nanoTime() < expiryNanos) {
            return snap;
        }
        return refresh();
    }

    private synchronized Set<String> refresh() {
        if (snapshot != null && System.nanoTime() < expiryNanos) {
            return snapshot;
        }
        try {
            URI target = URI.create(stripTrailingSlash(baseUrl.toString()) + EXCHANGE_INFO_PATH);
            HttpRequest request = HttpRequest.newBuilder(target)
                    .timeout(timeout)
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                log.warn("Binance exchangeInfo returned non-2xx status {} - serving empty (no filtering)", status);
                return Set.of();
            }

            JsonNode root = mapper.readTree(response.body());
            JsonNode symbols = root == null ? null : root.get("symbols");
            if (symbols == null || !symbols.isArray()) {
                log.warn("Binance exchangeInfo missing 'symbols' array - serving empty (no filtering)");
                return Set.of();
            }

            Set<String> tradable = new LinkedHashSet<>();
            for (JsonNode node : symbols) {
                JsonNode symbolNode = node.get("symbol");
                JsonNode statusNode = node.get("status");
                JsonNode quoteNode = node.get("quoteAsset");
                if (symbolNode == null || !symbolNode.isTextual()
                        || statusNode == null || !statusNode.isTextual()
                        || quoteNode == null || !quoteNode.isTextual()) {
                    continue;
                }
                if ("TRADING".equals(statusNode.asText()) && "USDT".equals(quoteNode.asText())) {
                    tradable.add(symbolNode.asText().toUpperCase(Locale.ROOT));
                }
            }

            Set<String> result = Set.copyOf(tradable);
            snapshot = result;
            expiryNanos = System.nanoTime() + cacheTtl.toNanos();
            return result;
        } catch (Exception e) {
            log.warn("Binance exchangeInfo fetch failed: {} - serving empty (no filtering)", e.toString());
            return Set.of();
        }
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
