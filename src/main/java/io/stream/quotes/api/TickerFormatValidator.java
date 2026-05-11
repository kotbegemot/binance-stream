package io.stream.quotes.api;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Generic regex-based validator shared by {@link SymbolValidator} (Binance
 * pair tickers, e.g. {@code BTCUSDT}) and {@link TickerValidator} (base
 * asset tickers used in the filter list, e.g. {@code USDT}).
 *
 * <p>Both wrap an instance of this class instead of duplicating the
 * {@code null check → uppercase → regex match → throw} pipeline. Public API
 * of the two callers is preserved.
 */
public final class TickerFormatValidator {

    private final Pattern pattern;
    private final String label;

    public TickerFormatValidator(Pattern pattern, String label) {
        this.pattern = pattern;
        this.label = label;
    }

    public String validate(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException(label + " must not be null");
        }
        String upper = raw.toUpperCase(Locale.ROOT);
        if (!pattern.matcher(upper).matches()) {
            throw new IllegalArgumentException("invalid " + label + ": " + raw);
        }
        return upper;
    }

    public List<String> validateAll(List<String> input) {
        if (input == null) {
            return List.of();
        }
        return input.stream().map(this::validate).toList();
    }
}