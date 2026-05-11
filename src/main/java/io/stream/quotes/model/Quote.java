package io.stream.quotes.model;

import java.math.BigDecimal;
import java.util.Objects;

public record Quote(
        String symbol,
        BigDecimal bid,
        BigDecimal bidSize,
        BigDecimal ask,
        BigDecimal askSize,
        long updateId,
        long receivedAtWallMs
) {

    public Quote {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(bid, "bid");
        Objects.requireNonNull(bidSize, "bidSize");
        Objects.requireNonNull(ask, "ask");
        Objects.requireNonNull(askSize, "askSize");
    }
}