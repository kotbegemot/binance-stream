package io.stream.quotes.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.stream.quotes.model.Quote;
import io.stream.quotes.ranking.FilterRules;
import io.stream.quotes.ranking.SymbolRegistry;
import io.stream.quotes.store.FilterStore;
import io.stream.quotes.store.LatestQuoteStore;
import io.stream.quotes.store.QuoteHistoryReader;
import io.stream.quotes.store.SqliteConnectionProvider;
import io.stream.quotes.store.SqliteQuoteWriter;
import io.stream.quotes.store.TrackedSymbolsStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.stream.quotes.support.TestSupport.quote;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class HttpServerTest {

    private LatestQuoteStore store;
    private HttpServer server;
    private HttpClient http;
    private SqliteConnectionProvider provider;
    private SqliteQuoteWriter writer;
    private QuoteHistoryReader history;
    private TrackedSymbolsStore symbolsStore;
    private SymbolRegistry registry;
    private FilterStore filterStore;
    private CountDownLatch slowHandlerLatch;

    @BeforeEach
    void setUp() {
        store = new LatestQuoteStore();
        http = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        if (slowHandlerLatch != null) {
            slowHandlerLatch.countDown();
        }
        if (server != null) {
            server.close();
        }
        if (writer != null) {
            writer.close();
        }
        if (provider != null) {
            provider.close();
        }
    }

    private void startServer(String... trackedSymbols) {
        server = new HttpServer(0, store, List.of(trackedSymbols));
        server.start();
    }

    private void openProvider(Path tmp) throws Exception {
        String dbPath = tmp.resolve("quotes.db").toString();
        provider = new SqliteConnectionProvider(dbPath);
        provider.open();
    }

    private void startServerWithHistory(Path tmp, int historyMaxLimit, String... trackedSymbols) throws Exception {
        openProvider(tmp);
        writer = new SqliteQuoteWriter(provider.quoteWriterConnection(), 100, Duration.ofMillis(20));
        writer.start();
        history = new QuoteHistoryReader(provider.historyReaderConnection());
        server = new HttpServer(0, store, List.of(trackedSymbols),
                HttpServerOptions.defaults().withHistory(history, historyMaxLimit));
        server.start();
    }

    private void startServerWithFilterAdmin(Path tmp, List<String> seedFilters) throws Exception {
        openProvider(tmp);
        filterStore = new FilterStore(provider.filterStoreConnection(), Clock.systemUTC());
        filterStore.loadInitial(new FilterRules(seedFilters, List.of()));
        server = new HttpServer(0, store, List.of("BTCUSDT"),
                HttpServerOptions.defaults().withFilterAdmin(filterStore));
        server.start();
    }

    private void startServerWithAdmin(Path tmp, String... initialSymbols) throws Exception {
        openProvider(tmp);
        symbolsStore = new TrackedSymbolsStore(provider.trackedSymbolsConnection(), Clock.systemUTC());
        List<String> initial = symbolsStore.loadInitial(List.of(initialSymbols), "test-seed");
        registry = new SymbolRegistry();
        registry.set(initial);
        server = new HttpServer(0, store, initial,
                HttpServerOptions.defaults().withSymbolAdmin(symbolsStore, registry));
        registry.addListener(server::setTrackedSymbols);
        server.start();
    }

    private void startServerWithCors(List<String> corsOrigins, String... trackedSymbols) {
        server = new HttpServer(0, store, List.of(trackedSymbols),
                HttpServerOptions.defaults().withCorsAllowedOrigins(corsOrigins));
        server.start();
    }

    private void startServerWithAdminAndApiKey(Path tmp, String apiKey, String... initialSymbols) throws Exception {
        openProvider(tmp);
        symbolsStore = new TrackedSymbolsStore(provider.trackedSymbolsConnection(), Clock.systemUTC());
        List<String> initial = symbolsStore.loadInitial(List.of(initialSymbols), "test-seed");
        registry = new SymbolRegistry();
        registry.set(initial);
        server = new HttpServer(0, store, initial,
                HttpServerOptions.defaults()
                        .withSymbolAdmin(symbolsStore, registry)
                        .withAdminApiKey(apiKey));
        registry.addListener(server::setTrackedSymbols);
        server.start();
    }

    private void startServerWithTimeout(long timeoutMs, String... trackedSymbols) {
        server = new HttpServer(0, store, List.of(trackedSymbols),
                HttpServerOptions.defaults().withHttpAsyncTimeoutMs(timeoutMs));
        server.start();
    }

    @Test
    void emptyStoreReturnsEmptyArray() throws Exception {
        startServer("BTCUSDT");

        HttpResponse<String> resp = get("/quotes/latest");

        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).isEqualTo("[]");
    }

    @Test
    void returnsSnakeCaseFlatArrayWithAllRequiredFields() throws Exception {
        startServer("BTCUSDT", "ETHUSDT");
        store.put(quote("BTCUSDT", 1L, "50000.00", "1.5", "50001.00", "2.0", 1715472000123L));
        store.put(quote("ETHUSDT", 2L, "3000.00", "10", "3001.00", "8", 1715472000456L));

        HttpResponse<String> resp = get("/quotes/latest");

        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.headers().firstValue("content-type").orElse(""))
                .contains("application/json");

        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(resp.body());
        assertThat(root.isArray()).isTrue();
        assertThat(root).hasSize(2);

        JsonNode btc = root.get(0);
        assertThat(btc.get("symbol").asText()).isEqualTo("BTCUSDT");
        assertThat(btc.get("bid").asText()).isEqualTo("50000.00");
        assertThat(btc.get("bid_size").asText()).isEqualTo("1.5");
        assertThat(btc.get("ask").asText()).isEqualTo("50001.00");
        assertThat(btc.get("ask_size").asText()).isEqualTo("2.0");
        assertThat(btc.get("received_at_ms").asLong()).isEqualTo(1715472000123L);

        assertThat(btc.has("update_id")).as("internal updateId is not exposed").isFalse();
        assertThat(btc.has("updateId")).isFalse();
    }

    @Test
    void preservesBigDecimalPrecisionAsStringInJson() throws Exception {
        startServer("BTC");
        store.put(quote("BTC", 1L, "0.00000001", "0.00000002", "123456789.12345678", "0.5", 0));

        HttpResponse<String> resp = get("/quotes/latest");

        ObjectMapper mapper = new ObjectMapper();
        JsonNode node = mapper.readTree(resp.body()).get(0);
        assertThat(node.get("bid").asText()).isEqualTo("0.00000001");
        assertThat(node.get("ask").asText()).isEqualTo("123456789.12345678");
    }

    @Test
    void symbolsWithoutDataDoNotAppearInArray() throws Exception {
        startServer("BTCUSDT", "ETHUSDT");
        store.put(quote("BTCUSDT", 1L, "1", "1", "1", "1", 0));

        HttpResponse<String> resp = get("/quotes/latest");

        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(resp.body());
        assertThat(root).hasSize(1);
        assertThat(root.get(0).get("symbol").asText()).isEqualTo("BTCUSDT");
    }

    @Test
    void perSymbolLatestReturnsObject() throws Exception {
        startServer("BTCUSDT");
        store.put(quote("BTCUSDT", 1L, "50000.00", "1.5", "50001.00", "2.0", 1715472000123L));

        HttpResponse<String> resp = get("/quotes/latest/BTCUSDT");

        assertThat(resp.statusCode()).isEqualTo(200);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode body = mapper.readTree(resp.body());
        assertThat(body.isObject()).isTrue();
        assertThat(body.get("symbol").asText()).isEqualTo("BTCUSDT");
        assertThat(body.get("bid").asText()).isEqualTo("50000.00");
        assertThat(body.get("received_at_ms").asLong()).isEqualTo(1715472000123L);
    }

    @Test
    void perSymbolLatestNotTrackedReturns404() throws Exception {
        startServer("BTCUSDT");

        HttpResponse<String> resp = get("/quotes/latest/UNKNOWN");

        assertThat(resp.statusCode()).isEqualTo(404);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode body = mapper.readTree(resp.body());
        assertThat(body.get("error").asText()).isEqualTo("symbol not tracked");
        assertThat(body.get("symbol").asText()).isEqualTo("UNKNOWN");
    }

    @Test
    void perSymbolLatestColdStartReturns404() throws Exception {
        startServer("BTCUSDT");

        HttpResponse<String> resp = get("/quotes/latest/BTCUSDT");

        assertThat(resp.statusCode()).isEqualTo(404);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode body = mapper.readTree(resp.body());
        assertThat(body.get("error").asText()).isEqualTo("no quote yet");
    }

    @Test
    void perSymbolLatestLowercasePathIsAcceptedAndUppercased() throws Exception {
        startServer("ETHUSDT");
        store.put(quote("ETHUSDT", 1L, "3000.00", "10", "3001.00", "8", 0L));

        HttpResponse<String> resp = get("/quotes/latest/ethusdt");

        assertThat(resp.statusCode()).isEqualTo(200);
        ObjectMapper mapper = new ObjectMapper();
        assertThat(mapper.readTree(resp.body()).get("symbol").asText()).isEqualTo("ETHUSDT");
    }

    @Test
    void symbolsReturnsTrackedList() throws Exception {
        startServer("BTCUSDT", "ETHUSDT", "SOLUSDT");

        HttpResponse<String> resp = get("/symbols");

        assertThat(resp.statusCode()).isEqualTo(200);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode body = mapper.readTree(resp.body());
        assertThat(body.get("count").asInt()).isEqualTo(3);
        assertThat(body.get("symbols").isArray()).isTrue();
        assertThat(body.get("symbols")).hasSize(3);
        assertThat(body.get("symbols").get(0).asText()).isEqualTo("BTCUSDT");
        assertThat(body.get("symbols").get(2).asText()).isEqualTo("SOLUSDT");
    }

    @Test
    void historyReturnsRecentQuotesDescending(@TempDir Path tmp) throws Exception {
        startServerWithHistory(tmp, 10_000, "BTCUSDT");
        writeRows(quote("BTCUSDT", 1L, "1", "1", "1", "1", 1_000L),
                  quote("BTCUSDT", 2L, "1", "1", "1", "1", 2_000L),
                  quote("BTCUSDT", 3L, "1", "1", "1", "1", 3_000L));

        HttpResponse<String> resp = get("/quotes/BTCUSDT/history?limit=10");

        assertThat(resp.statusCode()).isEqualTo(200);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode body = mapper.readTree(resp.body());
        assertThat(body.isArray()).isTrue();
        assertThat(body).hasSize(3);
        assertThat(body.get(0).get("received_at_ms").asLong()).isEqualTo(3_000L);
        assertThat(body.get(2).get("received_at_ms").asLong()).isEqualTo(1_000L);
    }

    @Test
    void historyTimeRangeNarrowsResults(@TempDir Path tmp) throws Exception {
        startServerWithHistory(tmp, 10_000, "BTCUSDT");
        writeRows(
                quote("BTCUSDT", 1L, "1", "1", "1", "1", 1_000L),
                quote("BTCUSDT", 2L, "1", "1", "1", "1", 2_000L),
                quote("BTCUSDT", 3L, "1", "1", "1", "1", 3_000L),
                quote("BTCUSDT", 4L, "1", "1", "1", "1", 4_000L));

        HttpResponse<String> resp = get("/quotes/BTCUSDT/history?from=2000&to=3500");

        ObjectMapper mapper = new ObjectMapper();
        JsonNode body = mapper.readTree(resp.body());
        assertThat(body).hasSize(2);
        assertThat(body.get(0).get("received_at_ms").asLong()).isEqualTo(3_000L);
        assertThat(body.get(1).get("received_at_ms").asLong()).isEqualTo(2_000L);
    }

    @Test
    void historyUnknownSymbolReturns404(@TempDir Path tmp) throws Exception {
        startServerWithHistory(tmp, 10_000, "BTCUSDT");

        HttpResponse<String> resp = get("/quotes/UNKNOWN/history");

        assertThat(resp.statusCode()).isEqualTo(404);
    }

    @Test
    void historyLimitAboveMaxReturns400(@TempDir Path tmp) throws Exception {
        startServerWithHistory(tmp, 100, "BTCUSDT");

        HttpResponse<String> resp = get("/quotes/BTCUSDT/history?limit=99999");

        assertThat(resp.statusCode()).isEqualTo(400);
        ObjectMapper mapper = new ObjectMapper();
        assertThat(mapper.readTree(resp.body()).get("error").asText())
                .contains("exceeds max");
    }

    @Test
    void historyBadTimeRangeReturns400(@TempDir Path tmp) throws Exception {
        startServerWithHistory(tmp, 10_000, "BTCUSDT");

        HttpResponse<String> resp = get("/quotes/BTCUSDT/history?from=200&to=100");

        assertThat(resp.statusCode()).isEqualTo(400);
    }

    @Test
    void historyDisabledReturns503WhenNoReaderConfigured() throws Exception {
        startServer("BTCUSDT");

        HttpResponse<String> resp = get("/quotes/BTCUSDT/history");

        assertThat(resp.statusCode()).isEqualTo(503);
    }

    private void writeRows(Quote... quotes) {
        for (Quote q : quotes) {
            writer.submit(q);
        }
        await().atMost(3, TimeUnit.SECONDS).until(() -> writer.queueSize() == 0);
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void adminGetSymbolsReturnsInitial(@TempDir Path tmp) throws Exception {
        startServerWithAdmin(tmp, "BTCUSDT", "ETHUSDT");

        HttpResponse<String> resp = get("/admin/symbols");

        assertThat(resp.statusCode()).isEqualTo(200);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode body = mapper.readTree(resp.body());
        assertThat(body.get("count").asInt()).isEqualTo(2);
        assertThat(body.get("symbols")).hasSize(2);
    }

    @Test
    void adminPutSymbolsReplacesWholeList(@TempDir Path tmp) throws Exception {
        startServerWithAdmin(tmp, "BTCUSDT", "ETHUSDT");

        HttpResponse<String> resp = sendJson("PUT", "/admin/symbols",
                "{\"symbols\":[\"BTCUSDT\",\"SOLUSDT\"]}");

        assertThat(resp.statusCode()).isEqualTo(200);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode body = mapper.readTree(resp.body());
        assertThat(body.get("symbols")).hasSize(2);
        assertThat(body.get("added")).hasSize(1);
        assertThat(body.get("added").get(0).asText()).isEqualTo("SOLUSDT");
        assertThat(body.get("removed")).hasSize(1);
        assertThat(body.get("removed").get(0).asText()).isEqualTo("ETHUSDT");
    }

    @Test
    void adminPutWithEmptySymbolsReturns400(@TempDir Path tmp) throws Exception {
        startServerWithAdmin(tmp, "BTCUSDT");

        HttpResponse<String> resp = sendJson("PUT", "/admin/symbols", "{\"symbols\":[]}");

        assertThat(resp.statusCode()).isEqualTo(400);
    }

    @Test
    void adminPutWithInvalidSymbolReturns400(@TempDir Path tmp) throws Exception {
        startServerWithAdmin(tmp, "BTCUSDT");

        HttpResponse<String> resp = sendJson("PUT", "/admin/symbols", "{\"symbols\":[\"NOPE\"]}");

        assertThat(resp.statusCode()).isEqualTo(400);
        assertThat(resp.body()).contains("invalid symbol");
    }

    @Test
    void adminPatchAddOnlyAdds(@TempDir Path tmp) throws Exception {
        startServerWithAdmin(tmp, "BTCUSDT");

        HttpResponse<String> resp = sendJson("PATCH", "/admin/symbols", "{\"add\":[\"ETHUSDT\"]}");

        assertThat(resp.statusCode()).isEqualTo(200);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode body = mapper.readTree(resp.body());
        assertThat(body.get("symbols")).hasSize(2);
        assertThat(body.get("added")).hasSize(1);
        assertThat(body.get("removed")).isEmpty();
    }

    @Test
    void adminPatchRemoveOnlyRemoves(@TempDir Path tmp) throws Exception {
        startServerWithAdmin(tmp, "BTCUSDT", "ETHUSDT");

        HttpResponse<String> resp = sendJson("PATCH", "/admin/symbols", "{\"remove\":[\"ETHUSDT\"]}");

        assertThat(resp.statusCode()).isEqualTo(200);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode body = mapper.readTree(resp.body());
        assertThat(body.get("symbols")).hasSize(1);
        assertThat(body.get("symbols").get(0).asText()).isEqualTo("BTCUSDT");
        assertThat(body.get("removed")).hasSize(1);
    }

    @Test
    void adminPatchAddAndRemoveAtomic(@TempDir Path tmp) throws Exception {
        startServerWithAdmin(tmp, "BTCUSDT", "ETHUSDT");

        HttpResponse<String> resp = sendJson("PATCH", "/admin/symbols",
                "{\"add\":[\"SOLUSDT\"],\"remove\":[\"ETHUSDT\"]}");

        assertThat(resp.statusCode()).isEqualTo(200);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode body = mapper.readTree(resp.body());
        assertThat(body.get("symbols")).hasSize(2);
        assertThat(body.get("added").get(0).asText()).isEqualTo("SOLUSDT");
        assertThat(body.get("removed").get(0).asText()).isEqualTo("ETHUSDT");
    }

    @Test
    void adminPatchResultingInEmptyReturns400(@TempDir Path tmp) throws Exception {
        startServerWithAdmin(tmp, "BTCUSDT");

        HttpResponse<String> resp = sendJson("PATCH", "/admin/symbols", "{\"remove\":[\"BTCUSDT\"]}");

        assertThat(resp.statusCode()).isEqualTo(400);
        assertThat(resp.body()).contains("empty");
    }

    @Test
    void adminPatchUpdatesTrackedSetForLatestEndpoint(@TempDir Path tmp) throws Exception {
        startServerWithAdmin(tmp, "BTCUSDT");

        HttpResponse<String> before = get("/quotes/latest/ETHUSDT");
        assertThat(before.statusCode()).isEqualTo(404);
        assertThat(before.body()).contains("symbol not tracked");

        sendJson("PATCH", "/admin/symbols", "{\"add\":[\"ETHUSDT\"]}");

        HttpResponse<String> after = get("/quotes/latest/ETHUSDT");
        assertThat(after.statusCode()).isEqualTo(404);
        assertThat(after.body()).contains("no quote yet");
    }

    @Test
    void adminPatchIdempotentAddExistingProducesNoChange(@TempDir Path tmp) throws Exception {
        startServerWithAdmin(tmp, "BTCUSDT");

        HttpResponse<String> resp = sendJson("PATCH", "/admin/symbols", "{\"add\":[\"BTCUSDT\"]}");

        assertThat(resp.statusCode()).isEqualTo(200);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode body = mapper.readTree(resp.body());
        assertThat(body.get("added")).isEmpty();
        assertThat(body.get("removed")).isEmpty();
        assertThat(body.get("symbols")).hasSize(1);
    }

    @Test
    void adminEndpointsAreNotRegisteredWithoutAdminContext() throws Exception {
        startServer("BTCUSDT");

        HttpResponse<String> resp = get("/admin/symbols");

        assertThat(resp.statusCode()).isEqualTo(404);
    }

    @Test
    void adminGetFiltersReturnsSeed(@TempDir Path tmp) throws Exception {
        startServerWithFilterAdmin(tmp, List.of("USDT", "USDC"));

        HttpResponse<String> resp = get("/admin/filters");

        assertThat(resp.statusCode()).isEqualTo(200);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode body = mapper.readTree(resp.body());
        assertThat(body.get("count").asInt()).isEqualTo(2);
        assertThat(body.get("filtered")).hasSize(2);
    }

    @Test
    void adminPutFiltersReplacesWholeSet(@TempDir Path tmp) throws Exception {
        startServerWithFilterAdmin(tmp, List.of("USDT", "USDC"));

        HttpResponse<String> resp = sendJson("PUT", "/admin/filters",
                "{\"filtered\":[\"USDT\",\"DAI\"]}");

        assertThat(resp.statusCode()).isEqualTo(200);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode body = mapper.readTree(resp.body());
        assertThat(body.get("filtered")).hasSize(2);
        assertThat(body.get("added")).hasSize(1);
        assertThat(body.get("added").get(0).asText()).isEqualTo("DAI");
        assertThat(body.get("removed")).hasSize(1);
        assertThat(body.get("removed").get(0).asText()).isEqualTo("USDC");
    }

    @Test
    void adminPutFiltersAcceptsEmptyArray(@TempDir Path tmp) throws Exception {
        startServerWithFilterAdmin(tmp, List.of("USDT"));

        HttpResponse<String> resp = sendJson("PUT", "/admin/filters", "{\"filtered\":[]}");

        assertThat(resp.statusCode()).isEqualTo(200);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode body = mapper.readTree(resp.body());
        assertThat(body.get("count").asInt()).isZero();
    }

    @Test
    void adminPatchFiltersAddRemoveAtomic(@TempDir Path tmp) throws Exception {
        startServerWithFilterAdmin(tmp, List.of("USDT"));

        HttpResponse<String> resp = sendJson("PATCH", "/admin/filters",
                "{\"add\":[\"WBTC\",\"DAI\"],\"remove\":[\"USDT\"]}");

        assertThat(resp.statusCode()).isEqualTo(200);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode body = mapper.readTree(resp.body());
        assertThat(body.get("filtered")).hasSize(2);
        assertThat(body.get("added")).hasSize(2);
        assertThat(body.get("removed")).hasSize(1);
    }

    @Test
    void adminFiltersInvalidTickerReturns400(@TempDir Path tmp) throws Exception {
        startServerWithFilterAdmin(tmp, List.of("USDT"));

        HttpResponse<String> resp = sendJson("PATCH", "/admin/filters",
                "{\"add\":[\"bad-ticker\"]}");

        assertThat(resp.statusCode()).isEqualTo(400);
        assertThat(resp.body()).contains("invalid ticker");
    }

    @Test
    void adminRequiresKeyWhenConfigured(@TempDir Path tmp) throws Exception {
        startServerWithAdminAndApiKey(tmp, "secret-key", "BTCUSDT");

        HttpResponse<String> resp = get("/admin/symbols");

        assertThat(resp.statusCode()).isEqualTo(401);
        assertThat(resp.body()).contains("X-Admin-Key");
    }

    @Test
    void adminAcceptsCorrectKey(@TempDir Path tmp) throws Exception {
        startServerWithAdminAndApiKey(tmp, "secret-key", "BTCUSDT");

        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + server.actualPort() + "/admin/symbols"))
                        .header("X-Admin-Key", "secret-key")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode()).isEqualTo(200);
    }

    @Test
    void adminRejectsWrongKey(@TempDir Path tmp) throws Exception {
        startServerWithAdminAndApiKey(tmp, "secret-key", "BTCUSDT");

        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + server.actualPort() + "/admin/symbols"))
                        .header("X-Admin-Key", "wrong")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode()).isEqualTo(401);
    }

    @Test
    void adminMutationRequiresKeyWhenConfigured(@TempDir Path tmp) throws Exception {
        startServerWithAdminAndApiKey(tmp, "secret-key", "BTCUSDT");

        HttpResponse<String> resp = sendJson("PATCH", "/admin/symbols",
                "{\"add\":[\"ETHUSDT\"]}");

        assertThat(resp.statusCode()).isEqualTo(401);
    }

    @Test
    void corsAllowsConfiguredOrigin() throws Exception {
        startServerWithCors(List.of("https://app.example.com"), "BTCUSDT");

        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + server.actualPort() + "/quotes/latest"))
                        .header("Origin", "https://app.example.com")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.headers().firstValue("access-control-allow-origin"))
                .hasValue("https://app.example.com");
    }

    @Test
    void corsOmitsHeaderForUnknownOrigin() throws Exception {
        startServerWithCors(List.of("https://app.example.com"), "BTCUSDT");

        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + server.actualPort() + "/quotes/latest"))
                        .header("Origin", "https://evil.example.com")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        // Server still answers (CORS is a browser-side enforcement) but
        // does not send the Access-Control-Allow-Origin header → browser
        // would block the response. That absence is the test.
        assertThat(resp.headers().firstValue("access-control-allow-origin"))
                .isEmpty();
    }

    @Test
    void corsDisabledByDefaultEmitsNoHeader() throws Exception {
        startServer("BTCUSDT");

        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + server.actualPort() + "/quotes/latest"))
                        .header("Origin", "https://any.example.com")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(resp.headers().firstValue("access-control-allow-origin"))
                .isEmpty();
    }

    @Test
    void slowAsyncHandlerHitsAsyncTimeout() throws Exception {
        slowHandlerLatch = new CountDownLatch(1);
        startServerWithTimeout(80, "BTCUSDT");
        server.registerTestRouteAsync("/slow",
                () -> slowHandlerLatch.await(5, TimeUnit.SECONDS));

        HttpResponse<String> resp = get("/slow");

        // Timeout path: runAsync.exceptionally maps TimeoutException to 408.
        assertThat(resp.statusCode()).isEqualTo(408);
    }

    @Test
    void fastAsyncHandlerCompletesBeforeTimeout() throws Exception {
        startServerWithTimeout(2_000, "BTCUSDT");
        server.registerTestRouteAsync("/fast", () -> Thread.sleep(30));

        HttpResponse<String> resp = get("/fast");

        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).isEqualTo("done");
    }

    @Test
    void adminFilterEndpointsAbsentWithoutFilterStore() throws Exception {
        startServer("BTCUSDT");

        HttpResponse<String> resp = get("/admin/filters");

        assertThat(resp.statusCode()).isEqualTo(404);
    }

    private HttpResponse<String> sendJson(String method, String path, String body) throws Exception {
        return http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + server.actualPort() + path))
                        .method(method, HttpRequest.BodyPublishers.ofString(body))
                        .header("Content-Type", "application/json")
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + server.actualPort() + path))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

}