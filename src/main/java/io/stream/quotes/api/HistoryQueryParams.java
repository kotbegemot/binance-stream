package io.stream.quotes.api;

public record HistoryQueryParams(long fromMs, long toMs, int limit) {

    public static final int DEFAULT_LIMIT = 100;

    public static HistoryQueryParams parse(String from, String to, String limit, int maxLimit) {
        long fromMs = parseLong(from, "from", 0L);
        long toMs = parseLong(to, "to", Long.MAX_VALUE);
        int limitValue = limit == null || limit.isBlank()
                ? Math.min(DEFAULT_LIMIT, maxLimit)
                : (int) parseLong(limit, "limit", DEFAULT_LIMIT);

        if (limitValue < 1) {
            throw new BadParamException("limit must be >= 1, got " + limitValue);
        }
        if (limitValue > maxLimit) {
            throw new BadParamException("limit " + limitValue + " exceeds max " + maxLimit);
        }
        if (fromMs > toMs) {
            throw new BadParamException("from " + fromMs + " > to " + toMs);
        }

        return new HistoryQueryParams(fromMs, toMs, limitValue);
    }

    private static long parseLong(String value, String name, long defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new BadParamException(name + " must be a number, got " + value);
        }
    }

    static final class BadParamException extends RuntimeException {
        BadParamException(String message) {
            super(message);
        }
    }
}