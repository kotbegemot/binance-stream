package io.stream.quotes.ranking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class StaticRanker implements InstrumentRanker {

    private static final Logger log = LoggerFactory.getLogger(StaticRanker.class);
    public static final String DEFAULT_RESOURCE = "instruments-fallback.yaml";

    private final List<String> symbols;
    private final FilterRules filterRules;

    public StaticRanker(List<String> symbols, FilterRules filterRules) {
        if (symbols == null || symbols.isEmpty()) {
            throw new IllegalArgumentException("symbols must not be empty");
        }
        this.symbols = List.copyOf(symbols);
        this.filterRules = filterRules;
    }

    public static StaticRanker fromClasspath() {
        return fromClasspath(DEFAULT_RESOURCE);
    }

    public static StaticRanker fromClasspath(String resource) {
        try (InputStream in = StaticRanker.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("classpath resource not found: " + resource);
            }
            return load(in);
        } catch (IOException e) {
            throw new IllegalStateException("failed to read " + resource, e);
        }
    }

    public static StaticRanker load(InputStream in) {
        Yaml yaml = new Yaml();
        Map<String, Object> root = yaml.load(in);
        if (root == null) {
            throw new IllegalStateException("empty YAML");
        }
        List<String> symbols = asStringList(root.get("symbols"));
        List<String> stablecoins = asStringList(root.get("stablecoin_filter"));
        List<String> wrapped = asStringList(root.get("wrapped_filter"));
        FilterRules rules = new FilterRules(stablecoins, wrapped);
        log.info("loaded {} fallback symbols, {} stablecoins, {} wrapped tickers",
                symbols.size(), rules.stablecoins().size(), rules.wrapped().size());
        return new StaticRanker(symbols, rules);
    }

    @Override
    public List<String> top10Symbols() {
        return symbols.subList(0, Math.min(10, symbols.size()));
    }

    public FilterRules filterRules() {
        return filterRules;
    }

    @SuppressWarnings("unchecked")
    private static List<String> asStringList(Object value) {
        if (value == null) {
            return Collections.emptyList();
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalStateException("expected list, got " + value.getClass());
        }
        List<String> out = new ArrayList<>(list.size());
        for (Object item : list) {
            out.add(String.valueOf(item));
        }
        return out;
    }
}