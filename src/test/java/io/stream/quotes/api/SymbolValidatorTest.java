package io.stream.quotes.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class SymbolValidatorTest {

    @Test
    void acceptsValidUsdtSymbol() {
        assertThat(SymbolValidator.validate("BTCUSDT")).isEqualTo("BTCUSDT");
    }

    @Test
    void uppercaseLowercaseInput() {
        assertThat(SymbolValidator.validate("btcusdt")).isEqualTo("BTCUSDT");
    }

    @Test
    void rejectsNonUsdtSuffix() {
        assertThatIllegalArgumentException().isThrownBy(() -> SymbolValidator.validate("BTCBUSD"));
        assertThatIllegalArgumentException().isThrownBy(() -> SymbolValidator.validate("BTC"));
        assertThatIllegalArgumentException().isThrownBy(() -> SymbolValidator.validate("USDT"));
    }

    @Test
    void acceptsUnderscoreInBaseTicker() {
        // CoinGecko returns tickers like "figr_heloc". After mapping to
        // <ASSET>USDT we get FIGR_HELOCUSDT, which an admin must be able to
        // PATCH-remove. Regex tolerates underscore for that reason.
        assertThat(SymbolValidator.validate("FIGR_HELOCUSDT")).isEqualTo("FIGR_HELOCUSDT");
        assertThat(SymbolValidator.validate("figr_helocusdt")).isEqualTo("FIGR_HELOCUSDT");
    }

    @Test
    void rejectsLowercaseSpecialCharsAndEmpty() {
        assertThatIllegalArgumentException().isThrownBy(() -> SymbolValidator.validate(""));
        assertThatIllegalArgumentException().isThrownBy(() -> SymbolValidator.validate("BTC-USDT"));
        assertThatIllegalArgumentException().isThrownBy(() -> SymbolValidator.validate("BTC USDT"));
    }

    @Test
    void rejectsNull() {
        assertThatIllegalArgumentException().isThrownBy(() -> SymbolValidator.validate(null));
    }

    @Test
    void validateAllReturnsListInOrder() {
        List<String> result = SymbolValidator.validateAll(List.of("btcusdt", "ETHUSDT", "solusdt"));
        assertThat(result).containsExactly("BTCUSDT", "ETHUSDT", "SOLUSDT");
    }

    @Test
    void validateAllReturnsEmptyForNullInput() {
        assertThat(SymbolValidator.validateAll(null)).isEmpty();
    }

    @Test
    void validateAllFailsOnAnyInvalid() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                SymbolValidator.validateAll(List.of("BTCUSDT", "INVALID", "ETHUSDT")));
    }
}