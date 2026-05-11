package io.stream.quotes.store;

import java.util.List;

public record Diff(List<String> added, List<String> removed) {
}
