package io.stream.quotes.api.admin;

import java.util.List;

public final class SymbolsAdminDtos {

    private SymbolsAdminDtos() {
    }

    public record PutSymbolsRequest(List<String> symbols) {
    }

    public record PatchSymbolsRequest(List<String> add, List<String> remove) {
    }

    public record SymbolsResponse(
            List<String> symbols,
            int count,
            List<String> added,
            List<String> removed
    ) {
        public static SymbolsResponse of(List<String> symbols, List<String> added, List<String> removed) {
            return new SymbolsResponse(symbols, symbols.size(), added, removed);
        }
    }
}