package io.stream.quotes.source;

import io.stream.quotes.model.Quote;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class BookTickerParserTest {

    private final BookTickerParser parser = new BookTickerParser();

    @Test
    void parsesSpotBookTickerFromFixture() throws IOException {
        byte[] payload = loadFixture("bookticker-spot.json");

        Optional<Quote> result = parser.parse(payload, 1715472000123L);

        assertThat(result).isPresent();
        Quote q = result.get();
        assertThat(q.symbol()).isEqualTo("BTCUSDT");
        assertThat(q.bid()).isEqualTo(new BigDecimal("50000.00"));
        assertThat(q.bidSize()).isEqualTo(new BigDecimal("1.5"));
        assertThat(q.ask()).isEqualTo(new BigDecimal("50001.00"));
        assertThat(q.askSize()).isEqualTo(new BigDecimal("2.0"));
        assertThat(q.updateId()).isEqualTo(400900217L);
        assertThat(q.receivedAtWallMs()).isEqualTo(1715472000123L);
    }

    @Test
    void parsesCombinedStreamWrapperFromFixture() throws IOException {
        byte[] payload = loadFixture("bookticker-combined.json");

        Optional<Quote> result = parser.parse(payload, 1L);

        assertThat(result).isPresent();
        assertThat(result.get().symbol()).isEqualTo("BTCUSDT");
        assertThat(result.get().updateId()).isEqualTo(400900217L);
    }

    @Test
    void preservesBigDecimalPrecisionForTinyAndLargeValues() {
        String json = """
                {"u":1,"s":"BTCUSDT","b":"0.00000001","B":"0.00000002","a":"123456789.12345678","A":"0.0"}""";

        Optional<Quote> result = parser.parse(json.getBytes(), 0L);

        assertThat(result).isPresent();
        assertThat(result.get().bid()).isEqualTo(new BigDecimal("0.00000001"));
        assertThat(result.get().bidSize()).isEqualTo(new BigDecimal("0.00000002"));
        assertThat(result.get().ask()).isEqualTo(new BigDecimal("123456789.12345678"));
    }

    @Test
    void returnsEmptyOnMalformedJson() {
        assertThat(parser.parse("not json".getBytes(), 0L)).isEmpty();
    }

    @Test
    void returnsEmptyOnEmptyPayload() {
        assertThat(parser.parse(new byte[0], 0L)).isEmpty();
    }

    @Test
    void returnsEmptyOnNullPayload() {
        assertThat(parser.parse(null, 0L)).isEmpty();
    }

    @Test
    void returnsEmptyWhenSymbolMissing() {
        String json = """
                {"u":1,"b":"1.0","B":"2.0","a":"3.0","A":"4.0"}""";
        assertThat(parser.parse(json.getBytes(), 0L)).isEmpty();
    }

    @Test
    void returnsEmptyWhenUpdateIdMissing() {
        String json = """
                {"s":"BTCUSDT","b":"1.0","B":"2.0","a":"3.0","A":"4.0"}""";
        assertThat(parser.parse(json.getBytes(), 0L)).isEmpty();
    }

    @Test
    void returnsEmptyWhenBidMissing() {
        String json = """
                {"u":1,"s":"BTCUSDT","B":"2.0","a":"3.0","A":"4.0"}""";
        assertThat(parser.parse(json.getBytes(), 0L)).isEmpty();
    }

    @Test
    void returnsEmptyWhenBidSizeMissing() {
        String json = """
                {"u":1,"s":"BTCUSDT","b":"1.0","a":"3.0","A":"4.0"}""";
        assertThat(parser.parse(json.getBytes(), 0L)).isEmpty();
    }

    @Test
    void returnsEmptyWhenAskMissing() {
        String json = """
                {"u":1,"s":"BTCUSDT","b":"1.0","B":"2.0","A":"4.0"}""";
        assertThat(parser.parse(json.getBytes(), 0L)).isEmpty();
    }

    @Test
    void returnsEmptyWhenAskSizeMissing() {
        String json = """
                {"u":1,"s":"BTCUSDT","b":"1.0","B":"2.0","a":"3.0"}""";
        assertThat(parser.parse(json.getBytes(), 0L)).isEmpty();
    }

    @Test
    void returnsEmptyOnNonBookTickerEvent() {
        String json = """
                {"e":"24hrTicker","E":12345,"s":"BTCUSDT"}""";
        assertThat(parser.parse(json.getBytes(), 0L)).isEmpty();
    }

    @Test
    void returnsEmptyOnNonNumericPriceValues() {
        String json = """
                {"u":1,"s":"BTCUSDT","b":"not-a-number","B":"2.0","a":"3.0","A":"4.0"}""";
        assertThat(parser.parse(json.getBytes(), 0L)).isEmpty();
    }

    @Test
    void writesReceivedAtWallMsExactly() {
        String json = """
                {"u":1,"s":"BTCUSDT","b":"1.0","B":"2.0","a":"3.0","A":"4.0"}""";

        Optional<Quote> result = parser.parse(json.getBytes(), 9876543210L);

        assertThat(result).isPresent();
        assertThat(result.get().receivedAtWallMs()).isEqualTo(9876543210L);
    }

    private byte[] loadFixture(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/" + name)) {
            assertThat(in).as("fixture %s exists on classpath", name).isNotNull();
            return in.readAllBytes();
        }
    }
}