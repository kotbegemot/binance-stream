package io.stream.quotes.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class TickerValidatorTest {

    @Test
    void acceptsValidTickerWithoutSuffix() {
        assertThat(TickerValidator.validate("USDT")).isEqualTo("USDT");
        assertThat(TickerValidator.validate("WBTC")).isEqualTo("WBTC");
        assertThat(TickerValidator.validate("ETH")).isEqualTo("ETH");
    }

    @Test
    void uppercasesLowercaseInput() {
        assertThat(TickerValidator.validate("usdt")).isEqualTo("USDT");
    }

    @Test
    void rejectsEmptyAndOneChar() {
        assertThatIllegalArgumentException().isThrownBy(() -> TickerValidator.validate(""));
        assertThatIllegalArgumentException().isThrownBy(() -> TickerValidator.validate("A"));
    }

    @Test
    void rejectsSpecialChars() {
        assertThatIllegalArgumentException().isThrownBy(() -> TickerValidator.validate("BTC-USD"));
        assertThatIllegalArgumentException().isThrownBy(() -> TickerValidator.validate("BTC USDT"));
    }

    @Test
    void rejectsNull() {
        assertThatIllegalArgumentException().isThrownBy(() -> TickerValidator.validate(null));
    }

    @Test
    void validateAllMaps() {
        assertThat(TickerValidator.validateAll(List.of("usdt", "wbtc")))
                .containsExactly("USDT", "WBTC");
    }

    @Test
    void validateAllNullReturnsEmpty() {
        assertThat(TickerValidator.validateAll(null)).isEmpty();
    }
}