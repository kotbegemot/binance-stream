package io.stream.quotes.ranking;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

public final class FilterRules {

    private final Set<String> stablecoins;
    private final Set<String> wrapped;

    public FilterRules(List<String> stablecoins, List<String> wrapped) {
        this.stablecoins = normalize(stablecoins);
        this.wrapped = normalize(wrapped);
    }

    public boolean isStablecoin(String ticker) {
        return stablecoins.contains(ticker.toLowerCase(Locale.ROOT));
    }

    public boolean isWrapped(String ticker) {
        return wrapped.contains(ticker.toLowerCase(Locale.ROOT));
    }

    public boolean isFiltered(String ticker) {
        return isStablecoin(ticker) || isWrapped(ticker);
    }

    public Set<String> stablecoins() {
        return stablecoins;
    }

    public Set<String> wrapped() {
        return wrapped;
    }

    private static Set<String> normalize(List<String> input) {
        if (input == null) {
            return Set.of();
        }
        return input.stream()
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }
}