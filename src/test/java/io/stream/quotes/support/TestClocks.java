package io.stream.quotes.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/** Shared test clocks so each store-test doesn't redeclare its own. */
public final class TestClocks {

    private TestClocks() {
    }

    public static Clock fixedClock() {
        return Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC);
    }
}