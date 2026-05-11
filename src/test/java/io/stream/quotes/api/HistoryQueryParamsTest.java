package io.stream.quotes.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HistoryQueryParamsTest {

    private static final int MAX_LIMIT = 10_000;

    @Test
    void defaultsWhenAllParamsMissing() {
        HistoryQueryParams params = HistoryQueryParams.parse(null, null, null, MAX_LIMIT);

        assertThat(params.fromMs()).isEqualTo(0L);
        assertThat(params.toMs()).isEqualTo(Long.MAX_VALUE);
        assertThat(params.limit()).isEqualTo(HistoryQueryParams.DEFAULT_LIMIT);
    }

    @Test
    void parsesAllThreeParams() {
        HistoryQueryParams params = HistoryQueryParams.parse(
                "1715472000000", "1715475600000", "500", MAX_LIMIT);

        assertThat(params.fromMs()).isEqualTo(1715472000000L);
        assertThat(params.toMs()).isEqualTo(1715475600000L);
        assertThat(params.limit()).isEqualTo(500);
    }

    @Test
    void blankParamsTreatedAsMissing() {
        HistoryQueryParams params = HistoryQueryParams.parse("  ", "", null, MAX_LIMIT);

        assertThat(params.fromMs()).isEqualTo(0L);
        assertThat(params.toMs()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void rejectsLimitAboveMax() {
        assertThatThrownBy(() ->
                HistoryQueryParams.parse(null, null, "99999", MAX_LIMIT))
                .isInstanceOf(HistoryQueryParams.BadParamException.class)
                .hasMessageContaining("exceeds max");
    }

    @Test
    void rejectsLimitZeroOrNegative() {
        assertThatThrownBy(() ->
                HistoryQueryParams.parse(null, null, "0", MAX_LIMIT))
                .isInstanceOf(HistoryQueryParams.BadParamException.class)
                .hasMessageContaining(">= 1");
        assertThatThrownBy(() ->
                HistoryQueryParams.parse(null, null, "-5", MAX_LIMIT))
                .isInstanceOf(HistoryQueryParams.BadParamException.class);
    }

    @Test
    void rejectsFromGreaterThanTo() {
        assertThatThrownBy(() ->
                HistoryQueryParams.parse("200", "100", null, MAX_LIMIT))
                .isInstanceOf(HistoryQueryParams.BadParamException.class)
                .hasMessageContaining(">");
    }

    @Test
    void rejectsNonNumericValues() {
        assertThatThrownBy(() ->
                HistoryQueryParams.parse("abc", null, null, MAX_LIMIT))
                .isInstanceOf(HistoryQueryParams.BadParamException.class)
                .hasMessageContaining("from");
        assertThatThrownBy(() ->
                HistoryQueryParams.parse(null, null, "ten", MAX_LIMIT))
                .isInstanceOf(HistoryQueryParams.BadParamException.class)
                .hasMessageContaining("limit");
    }

    @Test
    void defaultLimitCappedByMaxLimit() {
        HistoryQueryParams params = HistoryQueryParams.parse(null, null, null, 25);

        assertThat(params.limit()).isEqualTo(25);
    }
}