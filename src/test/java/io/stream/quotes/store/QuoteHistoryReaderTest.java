package io.stream.quotes.store;

import io.stream.quotes.model.Quote;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class QuoteHistoryReaderTest {

    private SqliteQuoteWriter writer;
    private QuoteHistoryReader reader;
    private String dbPath;

    @BeforeEach
    void setUp(@TempDir Path tmp) throws Exception {
        dbPath = tmp.resolve("quotes.db").toString();
        writer = new SqliteQuoteWriter(dbPath, 100, Duration.ofMillis(20));
        writer.start();
    }

    @AfterEach
    void tearDown() {
        if (reader != null) {
            reader.close();
        }
        if (writer != null) {
            writer.close();
        }
    }

    @Test
    void readsAllWithinTimeWindow() throws Exception {
        seedQuotes("BTCUSDT", List.of(
                quote("BTCUSDT", 1L, 1_000L),
                quote("BTCUSDT", 2L, 2_000L),
                quote("BTCUSDT", 3L, 3_000L),
                quote("BTCUSDT", 4L, 4_000L),
                quote("BTCUSDT", 5L, 5_000L)
        ));
        openReader();

        List<Quote> result = reader.read("BTCUSDT", 2_000L, 4_000L, 100);

        assertThat(result).hasSize(3);
        assertThat(result.get(0).receivedAtWallMs()).isEqualTo(4_000L);
        assertThat(result.get(1).receivedAtWallMs()).isEqualTo(3_000L);
        assertThat(result.get(2).receivedAtWallMs()).isEqualTo(2_000L);
    }

    @Test
    void respectsLimit() throws Exception {
        seedQuotes("BTCUSDT", List.of(
                quote("BTCUSDT", 1L, 100L),
                quote("BTCUSDT", 2L, 200L),
                quote("BTCUSDT", 3L, 300L),
                quote("BTCUSDT", 4L, 400L)
        ));
        openReader();

        List<Quote> result = reader.read("BTCUSDT", 0L, Long.MAX_VALUE, 2);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).receivedAtWallMs()).isEqualTo(400L);
        assertThat(result.get(1).receivedAtWallMs()).isEqualTo(300L);
    }

    @Test
    void filtersBySymbol() throws Exception {
        seedQuotes("multi", List.of(
                quote("BTCUSDT", 1L, 100L),
                quote("ETHUSDT", 1L, 100L),
                quote("BTCUSDT", 2L, 200L),
                quote("ETHUSDT", 2L, 200L)
        ));
        openReader();

        List<Quote> btc = reader.read("BTCUSDT", 0L, Long.MAX_VALUE, 100);

        assertThat(btc).hasSize(2);
        assertThat(btc).allMatch(q -> q.symbol().equals("BTCUSDT"));
    }

    @Test
    void emptyWindowReturnsEmptyList() throws Exception {
        seedQuotes("BTCUSDT", List.of(
                quote("BTCUSDT", 1L, 1_000L),
                quote("BTCUSDT", 2L, 2_000L)
        ));
        openReader();

        List<Quote> result = reader.read("BTCUSDT", 5_000L, 6_000L, 100);

        assertThat(result).isEmpty();
    }

    @Test
    void unknownSymbolReturnsEmptyList() throws Exception {
        seedQuotes("BTCUSDT", List.of(quote("BTCUSDT", 1L, 1_000L)));
        openReader();

        List<Quote> result = reader.read("UNKNOWN", 0L, Long.MAX_VALUE, 100);

        assertThat(result).isEmpty();
    }

    @Test
    void preservesBigDecimalPrecisionThroughReadBack() throws Exception {
        Quote q = new Quote("BTCUSDT",
                new BigDecimal("0.00000001"), new BigDecimal("0.00000002"),
                new BigDecimal("123456789.12345678"), new BigDecimal("0.5"),
                1L, 1_000L);
        seedQuotes("BTC", List.of(q));
        openReader();

        List<Quote> result = reader.read("BTCUSDT", 0L, Long.MAX_VALUE, 100);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).bid()).isEqualTo(new BigDecimal("0.00000001"));
        assertThat(result.get(0).ask()).isEqualTo(new BigDecimal("123456789.12345678"));
    }

    private void seedQuotes(String label, List<Quote> quotes) {
        for (Quote q : quotes) {
            writer.submit(q);
        }
        await().atMost(3, TimeUnit.SECONDS).until(() -> writer.queueSize() == 0);
        // give the writer a beat to actually commit the last batch
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void openReader() throws Exception {
        reader = new QuoteHistoryReader(dbPath);
        reader.open();
    }

    private static Quote quote(String symbol, long updateId, long receivedAtMs) {
        return new Quote(symbol,
                BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ONE,
                updateId, receivedAtMs);
    }
}