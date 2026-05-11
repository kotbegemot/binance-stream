package io.stream.quotes.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.javalin.Javalin;
import io.javalin.http.HttpStatus;
import io.javalin.json.JavalinJackson;
import io.stream.quotes.api.admin.FiltersAdminDtos;
import io.stream.quotes.api.admin.SymbolsAdminDtos;
import io.stream.quotes.ranking.SymbolRegistry;
import io.stream.quotes.store.Diff;
import io.stream.quotes.store.FilterStore;
import io.stream.quotes.store.LatestQuoteStore;
import io.stream.quotes.store.QuoteHistoryReader;
import io.stream.quotes.store.TrackedSymbolsStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

public final class HttpServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HttpServer.class);

    private final int port;
    private final LatestQuoteStore store;
    private final QuoteHistoryReader history;
    private final int historyMaxLimit;
    private final TrackedSymbolsStore symbolsStore;
    private final SymbolRegistry registry;
    private final FilterStore filterStore;
    private final String adminApiKey;
    private final List<String> corsAllowedOrigins;
    private final long httpAsyncTimeoutMs;
    private final AtomicReference<Set<String>> trackedSymbols;
    private final ExecutorService blockingExecutor;
    private final Javalin app;

    public HttpServer(int port, LatestQuoteStore store, List<String> trackedSymbols) {
        this(port, store, trackedSymbols, HttpServerOptions.defaults());
    }

    public HttpServer(int port,
                      LatestQuoteStore store,
                      List<String> trackedSymbols,
                      HttpServerOptions options) {
        this.port = port;
        this.store = store;
        this.history = options.history();
        this.historyMaxLimit = options.historyMaxLimit();
        this.symbolsStore = options.symbolsStore();
        this.registry = options.registry();
        this.filterStore = options.filterStore();
        this.adminApiKey = options.adminApiKey();
        this.corsAllowedOrigins = options.corsAllowedOrigins();
        this.httpAsyncTimeoutMs = options.httpAsyncTimeoutMs();
        this.trackedSymbols = new AtomicReference<>(new LinkedHashSet<>(trackedSymbols));
        this.blockingExecutor = Executors.newVirtualThreadPerTaskExecutor();
        ObjectMapper mapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        this.app = Javalin.create(config -> {
            config.jsonMapper(new JavalinJackson(mapper, false));
            if (!this.corsAllowedOrigins.isEmpty()) {
                config.bundledPlugins.enableCors(cors -> cors.addRule(rule ->
                        rule.allowHost(this.corsAllowedOrigins.get(0),
                                this.corsAllowedOrigins.subList(1, this.corsAllowedOrigins.size())
                                        .toArray(new String[0]))));
            }
        });
        registerRoutes();
    }

    @FunctionalInterface
    private interface AsyncHandler {
        void handle(io.javalin.http.Context ctx) throws Exception;
    }

    /**
     * Run {@code body} on the blocking executor and bind the resulting future
     * to the request. If {@code httpAsyncTimeoutMs > 0} the future is cut off
     * with {@link CompletableFuture#orTimeout(long, TimeUnit)} and we map
     * {@link TimeoutException} → 408 ourselves, so the surfaced status code
     * doesn't depend on Jetty's default error page behaviour.
     * <p>
     * The body runs as a {@link Future} (not {@code CompletableFuture.runAsync})
     * so that {@link Future#cancel(boolean) cancel(true)} actually interrupts
     * the virtual thread on timeout — preventing a late {@code ctx.json(...)}
     * from racing against the already-committed 408 response.
     */
    private void runAsync(io.javalin.http.Context ctx, AsyncHandler body) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        Future<?> task = blockingExecutor.submit(() -> {
            try {
                body.handle(ctx);
                done.complete(null);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                done.completeExceptionally(e);
            } catch (Exception e) {
                done.completeExceptionally(e);
            }
        });
        CompletableFuture<Void> bounded = httpAsyncTimeoutMs > 0
                ? done.orTimeout(httpAsyncTimeoutMs, TimeUnit.MILLISECONDS)
                : done;
        CompletableFuture<Void> resilient = bounded.exceptionally(throwable -> {
            task.cancel(true);
            Throwable cause = (throwable instanceof CompletionException && throwable.getCause() != null)
                    ? throwable.getCause()
                    : throwable;
            try {
                if (cause instanceof TimeoutException) {
                    ctx.status(HttpStatus.REQUEST_TIMEOUT);
                    ctx.json(Map.of("error", "request timed out"));
                } else {
                    log.error("async handler failed", cause);
                    ctx.status(HttpStatus.INTERNAL_SERVER_ERROR);
                    ctx.json(Map.of("error", "internal: " + cause.getClass().getSimpleName()));
                }
            } catch (Exception suppress) {
                log.warn("could not write error response (already committed?)", suppress);
            }
            return null;
        });
        ctx.future(() -> resilient);
    }

    private void registerRoutes() {
        app.get("/quotes/latest", ctx -> runAsync(ctx, c -> {
            List<QuoteDto> body = store.snapshot().values().stream()
                    .map(QuoteDto::from)
                    .sorted(Comparator.comparing(QuoteDto::symbol))
                    .toList();
            c.json(body);
        }));

        app.get("/quotes/latest/{symbol}", ctx -> runAsync(ctx, c -> {
            String symbol = c.pathParam("symbol").toUpperCase(Locale.ROOT);
            if (!trackedSymbols.get().contains(symbol)) {
                c.status(HttpStatus.NOT_FOUND);
                c.json(Map.of("error", "symbol not tracked", "symbol", symbol));
                return;
            }
            store.get(symbol)
                    .map(QuoteDto::from)
                    .ifPresentOrElse(c::json, () -> {
                        c.status(HttpStatus.NOT_FOUND);
                        c.json(Map.of("error", "no quote yet", "symbol", symbol));
                    });
        }));

        app.get("/quotes/{symbol}/history", ctx -> runAsync(ctx, c -> {
            if (history == null) {
                c.status(HttpStatus.SERVICE_UNAVAILABLE);
                c.json(Map.of("error", "history is not enabled"));
                return;
            }
            String symbol = c.pathParam("symbol").toUpperCase(Locale.ROOT);
            if (!trackedSymbols.get().contains(symbol)) {
                c.status(HttpStatus.NOT_FOUND);
                c.json(Map.of("error", "symbol not tracked", "symbol", symbol));
                return;
            }
            HistoryQueryParams params;
            try {
                params = HistoryQueryParams.parse(
                        c.queryParam("from"),
                        c.queryParam("to"),
                        c.queryParam("limit"),
                        historyMaxLimit);
            } catch (HistoryQueryParams.BadParamException e) {
                replyBadRequest(c, e.getMessage());
                return;
            }
            List<QuoteDto> body = history.read(symbol, params.fromMs(), params.toMs(), params.limit())
                    .stream()
                    .map(QuoteDto::from)
                    .toList();
            c.json(body);
        }));

        app.get("/symbols", ctx -> runAsync(ctx, c -> {
            Set<String> current = trackedSymbols.get();
            c.json(Map.of("symbols", current, "count", current.size()));
        }));

        boolean hasAdminRoutes = (symbolsStore != null && registry != null) || filterStore != null;
        if (hasAdminRoutes && !adminApiKey.isEmpty()) {
            app.before("/admin/*", ctx -> {
                if (!keyMatches(adminApiKey, ctx.header("X-Admin-Key"))) {
                    ctx.status(HttpStatus.UNAUTHORIZED);
                    ctx.json(Map.of("error", "missing or invalid X-Admin-Key"));
                    ctx.skipRemainingHandlers();
                }
            });
        }
        if (symbolsStore != null && registry != null) {
            registerAdminSymbolRoutes();
        }
        if (filterStore != null) {
            registerAdminFilterRoutes();
        }
    }

    private void registerAdminSymbolRoutes() {
        app.get("/admin/symbols", ctx -> runAsync(ctx, c -> {
            try {
                List<String> current = symbolsStore.current();
                c.json(Map.of("symbols", current, "count", current.size()));
            } catch (SQLException e) {
                replyServerError(c, e);
            }
        }));

        app.put("/admin/symbols", ctx -> runAsync(ctx, c -> {
            SymbolsAdminDtos.PutSymbolsRequest req;
            try {
                req = c.bodyAsClass(SymbolsAdminDtos.PutSymbolsRequest.class);
            } catch (Exception e) {
                replyBadRequest(c, "invalid request body: " + e.getMessage());
                return;
            }
            if (req.symbols() == null || req.symbols().isEmpty()) {
                replyBadRequest(c, "symbols must not be empty");
                return;
            }
            List<String> validated;
            try {
                validated = SymbolValidator.validateAll(req.symbols());
            } catch (IllegalArgumentException e) {
                replyBadRequest(c, e.getMessage());
                return;
            }
            try {
                Diff diff = symbolsStore.replace(validated, "admin");
                List<String> after = symbolsStore.current();
                registry.set(after);
                c.json(SymbolsAdminDtos.SymbolsResponse.of(after, diff.added(), diff.removed()));
            } catch (SQLException e) {
                replyServerError(c, e);
            }
        }));

        app.patch("/admin/symbols", ctx -> runAsync(ctx, c -> {
            SymbolsAdminDtos.PatchSymbolsRequest req;
            try {
                req = c.bodyAsClass(SymbolsAdminDtos.PatchSymbolsRequest.class);
            } catch (Exception e) {
                replyBadRequest(c, "invalid request body: " + e.getMessage());
                return;
            }
            List<String> add;
            List<String> remove;
            try {
                add = SymbolValidator.validateAll(req.add());
                remove = SymbolValidator.validateAll(req.remove());
            } catch (IllegalArgumentException e) {
                replyBadRequest(c, e.getMessage());
                return;
            }
            try {
                Set<String> future = new HashSet<>(symbolsStore.current());
                future.addAll(add);
                future.removeAll(remove);
                if (future.isEmpty()) {
                    replyBadRequest(c, "resulting symbol list must not be empty");
                    return;
                }
                Diff diff = symbolsStore.applyPatch(add, remove);
                List<String> after = symbolsStore.current();
                registry.set(after);
                c.json(SymbolsAdminDtos.SymbolsResponse.of(after, diff.added(), diff.removed()));
            } catch (SQLException e) {
                replyServerError(c, e);
            }
        }));
    }

    private void registerAdminFilterRoutes() {
        app.get("/admin/filters", ctx -> runAsync(ctx, c -> {
            try {
                List<String> filtered = sortedList(filterStore.currentSetUpperCase());
                c.json(Map.of("filtered", filtered, "count", filtered.size()));
            } catch (SQLException e) {
                replyServerError(c, e);
            }
        }));

        app.put("/admin/filters", ctx -> runAsync(ctx, c -> {
            FiltersAdminDtos.PutFiltersRequest req;
            try {
                req = c.bodyAsClass(FiltersAdminDtos.PutFiltersRequest.class);
            } catch (Exception e) {
                replyBadRequest(c, "invalid request body: " + e.getMessage());
                return;
            }
            List<String> validated;
            try {
                validated = TickerValidator.validateAll(req.filtered());
            } catch (IllegalArgumentException e) {
                replyBadRequest(c, e.getMessage());
                return;
            }
            try {
                Diff diff = filterStore.replace(validated, "admin");
                List<String> after = sortedList(filterStore.currentSetUpperCase());
                c.json(FiltersAdminDtos.FiltersResponse.of(
                        after, toUpper(diff.added()), toUpper(diff.removed())));
            } catch (SQLException e) {
                replyServerError(c, e);
            }
        }));

        app.patch("/admin/filters", ctx -> runAsync(ctx, c -> {
            FiltersAdminDtos.PatchFiltersRequest req;
            try {
                req = c.bodyAsClass(FiltersAdminDtos.PatchFiltersRequest.class);
            } catch (Exception e) {
                replyBadRequest(c, "invalid request body: " + e.getMessage());
                return;
            }
            List<String> add;
            List<String> remove;
            try {
                add = TickerValidator.validateAll(req.add());
                remove = TickerValidator.validateAll(req.remove());
            } catch (IllegalArgumentException e) {
                replyBadRequest(c, e.getMessage());
                return;
            }
            try {
                Diff diff = filterStore.applyPatch(add, remove);
                List<String> after = sortedList(filterStore.currentSetUpperCase());
                c.json(FiltersAdminDtos.FiltersResponse.of(
                        after, toUpper(diff.added()), toUpper(diff.removed())));
            } catch (SQLException e) {
                replyServerError(c, e);
            }
        }));
    }

    /**
     * Test-only hook: register an async route whose body may block. Used by
     * {@code HttpServerTest} to verify asyncTimeout fires on slow handlers.
     * Package-private to keep production surface minimal.
     */
    void registerTestRouteAsync(String path, ThrowingRunnable body) {
        app.get(path, ctx -> runAsync(ctx, c -> {
            body.run();
            c.result("done");
        }));
    }

    @FunctionalInterface
    interface ThrowingRunnable {
        void run() throws Exception;
    }

    /**
     * Constant-time compare of the configured admin key against the header
     * value. Note: the {@code null}-check leaks the "header missing" vs
     * "header wrong" distinction (no MessageDigest cost on absent header) —
     * acceptable for a shared-secret model.
     */
    private static boolean keyMatches(String configured, String provided) {
        return provided != null
                && MessageDigest.isEqual(
                        configured.getBytes(StandardCharsets.UTF_8),
                        provided.getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> toUpper(List<String> source) {
        return source.stream().map(s -> s.toUpperCase(Locale.ROOT)).toList();
    }

    private static List<String> sortedList(Set<String> source) {
        return source.stream().sorted().toList();
    }

    public void setTrackedSymbols(List<String> newSymbols) {
        trackedSymbols.set(new LinkedHashSet<>(newSymbols));
    }

    private static void replyServerError(io.javalin.http.Context ctx, SQLException e) {
        log.error("admin endpoint store error", e);
        ctx.status(HttpStatus.INTERNAL_SERVER_ERROR);
        ctx.json(Map.of("error", "store error: " + e.getClass().getSimpleName()));
    }

    private static void replyBadRequest(io.javalin.http.Context ctx, String msg) {
        ctx.status(HttpStatus.BAD_REQUEST);
        ctx.json(Map.of("error", msg));
    }

    public void start() {
        app.start(port);
        log.info("http server listening on :{}", actualPort());
    }

    int actualPort() {
        return app.port();
    }

    @Override
    public void close() {
        app.stop();
        blockingExecutor.shutdown();
        try {
            if (!blockingExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("blocking executor did not terminate within 5s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}