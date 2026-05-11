package io.stream.quotes.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.stream.quotes.model.Quote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

public final class BookTickerParser {

    private static final Logger log = LoggerFactory.getLogger(BookTickerParser.class);
    private static final int LOG_PAYLOAD_TRUNCATE = 200;

    private final ObjectMapper mapper;

    public BookTickerParser() {
        this(new ObjectMapper());
    }

    public BookTickerParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Optional<Quote> parse(byte[] payload, long receivedWallMs) {
        if (payload == null || payload.length == 0) {
            return Optional.empty();
        }
        try {
            JsonNode root = mapper.readTree(payload);
            JsonNode data = root.has("data") && root.has("stream") ? root.get("data") : root;
            return parseBookTicker(data, receivedWallMs, payload);
        } catch (Exception e) {
            log.warn("failed to parse payload (truncated): {}", truncate(payload));
            return Optional.empty();
        }
    }

    private Optional<Quote> parseBookTicker(JsonNode data, long receivedWallMs, byte[] payload) {
        JsonNode symbolNode = data.get("s");
        JsonNode updateIdNode = data.get("u");
        JsonNode bidNode = data.get("b");
        JsonNode bidSizeNode = data.get("B");
        JsonNode askNode = data.get("a");
        JsonNode askSizeNode = data.get("A");

        if (symbolNode == null || !symbolNode.isTextual()
                || updateIdNode == null || !updateIdNode.canConvertToLong()
                || bidNode == null || bidSizeNode == null
                || askNode == null || askSizeNode == null) {
            log.warn("missing required bookTicker fields in payload: {}", truncate(payload));
            return Optional.empty();
        }

        try {
            return Optional.of(new Quote(
                    symbolNode.asText(),
                    new BigDecimal(bidNode.asText()),
                    new BigDecimal(bidSizeNode.asText()),
                    new BigDecimal(askNode.asText()),
                    new BigDecimal(askSizeNode.asText()),
                    updateIdNode.asLong(),
                    receivedWallMs
            ));
        } catch (NumberFormatException e) {
            log.warn("failed to parse numbers in bookTicker payload: {}", truncate(payload));
            return Optional.empty();
        }
    }

    private static String truncate(byte[] payload) {
        String s = new String(payload, StandardCharsets.UTF_8);
        return s.length() <= LOG_PAYLOAD_TRUNCATE ? s : s.substring(0, LOG_PAYLOAD_TRUNCATE) + "...";
    }
}