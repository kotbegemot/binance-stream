package io.stream.quotes.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class QuoteTest {

    @Test
    void rejectsNullSymbol() {
        assertThatNullPointerException().isThrownBy(() ->
                new Quote(null, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, 1L, 1L));
    }

    @Test
    void rejectsNullBid() {
        assertThatNullPointerException().isThrownBy(() ->
                new Quote("X", null, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, 1L, 1L));
    }

    @Test
    void rejectsNullAsk() {
        assertThatNullPointerException().isThrownBy(() ->
                new Quote("X", BigDecimal.ONE, BigDecimal.ONE, null, BigDecimal.ONE, 1L, 1L));
    }
}