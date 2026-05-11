package io.stream.quotes.api;

import io.stream.quotes.model.Quote;

public record QuoteDto(
        String symbol,
        String bid,
        String bidSize,
        String ask,
        String askSize,
        long receivedAtMs
) {

    public static QuoteDto from(Quote q) {
        return new QuoteDto(
                q.symbol(),
                q.bid().toPlainString(),
                q.bidSize().toPlainString(),
                q.ask().toPlainString(),
                q.askSize().toPlainString(),
                q.receivedAtWallMs()
        );
    }
}