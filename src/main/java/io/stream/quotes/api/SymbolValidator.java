package io.stream.quotes.api;

import java.util.List;
import java.util.regex.Pattern;

public final class SymbolValidator {

    private static final TickerFormatValidator DELEGATE =
            new TickerFormatValidator(Pattern.compile("^[A-Z0-9_]{2,20}USDT$"), "symbol");

    private SymbolValidator() {
    }

    public static String validate(String raw) {
        return DELEGATE.validate(raw);
    }

    public static List<String> validateAll(List<String> symbols) {
        return DELEGATE.validateAll(symbols);
    }
}