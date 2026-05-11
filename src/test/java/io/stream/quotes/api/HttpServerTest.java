package io.stream.quotes.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.stream.quotes.model.Quote;
import io.stream.quotes.store.LatestQuoteStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HttpServerTest {

    private LatestQuoteStore store;
    private HttpServer server;
    private HttpClient http;

    @BeforeEach
    void setUp() {
        store = new LatestQuoteStore();
        http = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    private void startServer(String... trackedSymbols) {
        server = new HttpServer(0, store, List.of(trackedSymbols));
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

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + server.actualPort() + path))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static Quote quote(String symbol, long updateId, String bid, String bidSize,
                                String ask, String askSize, long receivedAtMs) {
        return new Quote(symbol,
                new BigDecimal(bid), new BigDecimal(bidSize),
                new BigDecimal(ask), new BigDecimal(askSize),
                updateId, receivedAtMs);
    }
}