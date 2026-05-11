package io.stream.quotes.api;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Validates filter tickers — base asset tickers without USDT suffix
 * (e.g. "USDT", "WBTC", "ETH"). For tracked-symbol validation use
 * {@link SymbolValidator}.
 */
public final class TickerValidator {

    private static final TickerFormatValidator DELEGATE =
            new TickerFormatValidator(Pattern.compile("^[A-Z0-9]{2,16}$"), "ticker");

    private TickerValidator() {
    }

    public static String validate(String raw) {
        return DELEGATE.validate(raw);
    }

    public static List<String> validateAll(List<String> tickers) {
        return DELEGATE.validateAll(tickers);
    }
}