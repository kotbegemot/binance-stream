package io.stream.quotes.api.admin;

import java.util.List;

public final class FiltersAdminDtos {

    private FiltersAdminDtos() {
    }

    public record PutFiltersRequest(List<String> filtered) {
    }

    public record PatchFiltersRequest(List<String> add, List<String> remove) {
    }

    public record FiltersResponse(
            List<String> filtered,
            int count,
            List<String> added,
            List<String> removed
    ) {
        public static FiltersResponse of(List<String> filtered, List<String> added, List<String> removed) {
            return new FiltersResponse(filtered, filtered.size(), added, removed);
        }
    }
}