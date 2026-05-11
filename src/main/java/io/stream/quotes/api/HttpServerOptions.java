package io.stream.quotes.api;

import io.stream.quotes.ranking.SymbolRegistry;
import io.stream.quotes.store.FilterStore;
import io.stream.quotes.store.QuoteHistoryReader;
import io.stream.quotes.store.TrackedSymbolsStore;

import java.util.List;

/**
 * Optional collaborators and production-extras for {@link HttpServer}.
 * Use {@link #defaults()} and chain the {@code withXxx} helpers to assemble
 * only the parts a given deployment / test actually needs.
 */
public record HttpServerOptions(
        QuoteHistoryReader history,
        int historyMaxLimit,
        TrackedSymbolsStore symbolsStore,
        SymbolRegistry registry,
        FilterStore filterStore,
        String adminApiKey,
        List<String> corsAllowedOrigins,
        long httpAsyncTimeoutMs
) {

    public HttpServerOptions {
        adminApiKey = adminApiKey == null ? "" : adminApiKey;
        corsAllowedOrigins = corsAllowedOrigins == null ? List.of() : List.copyOf(corsAllowedOrigins);
    }

    public static HttpServerOptions defaults() {
        return new HttpServerOptions(null, 0, null, null, null, "", List.of(), 0L);
    }

    public HttpServerOptions withHistory(QuoteHistoryReader history, int historyMaxLimit) {
        return new HttpServerOptions(history, historyMaxLimit, symbolsStore, registry,
                filterStore, adminApiKey, corsAllowedOrigins, httpAsyncTimeoutMs);
    }

    public HttpServerOptions withSymbolAdmin(TrackedSymbolsStore symbolsStore, SymbolRegistry registry) {
        return new HttpServerOptions(history, historyMaxLimit, symbolsStore, registry,
                filterStore, adminApiKey, corsAllowedOrigins, httpAsyncTimeoutMs);
    }

    public HttpServerOptions withFilterAdmin(FilterStore filterStore) {
        return new HttpServerOptions(history, historyMaxLimit, symbolsStore, registry,
                filterStore, adminApiKey, corsAllowedOrigins, httpAsyncTimeoutMs);
    }

    public HttpServerOptions withAdminApiKey(String adminApiKey) {
        return new HttpServerOptions(history, historyMaxLimit, symbolsStore, registry,
                filterStore, adminApiKey, corsAllowedOrigins, httpAsyncTimeoutMs);
    }

    public HttpServerOptions withCorsAllowedOrigins(List<String> corsAllowedOrigins) {
        return new HttpServerOptions(history, historyMaxLimit, symbolsStore, registry,
                filterStore, adminApiKey, corsAllowedOrigins, httpAsyncTimeoutMs);
    }

    public HttpServerOptions withHttpAsyncTimeoutMs(long httpAsyncTimeoutMs) {
        return new HttpServerOptions(history, historyMaxLimit, symbolsStore, registry,
                filterStore, adminApiKey, corsAllowedOrigins, httpAsyncTimeoutMs);
    }
}