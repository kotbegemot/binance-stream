package io.stream.quotes.source;

import java.time.Duration;
import java.util.Random;

public final class BackoffPolicy {

    private final Duration initial;
    private final Duration max;
    private final double multiplier;
    private final double jitterFraction;
    private final Random random;

    private Duration current;

    public BackoffPolicy(Duration initial, Duration max, double multiplier, double jitterFraction, Random random) {
        if (initial.isNegative() || initial.isZero()) {
            throw new IllegalArgumentException("initial must be positive");
        }
        if (max.compareTo(initial) < 0) {
            throw new IllegalArgumentException("max must be >= initial");
        }
        if (multiplier <= 1.0) {
            throw new IllegalArgumentException("multiplier must be > 1.0");
        }
        if (jitterFraction < 0.0 || jitterFraction >= 1.0) {
            throw new IllegalArgumentException("jitterFraction must be in [0, 1)");
        }
        this.initial = initial;
        this.max = max;
        this.multiplier = multiplier;
        this.jitterFraction = jitterFraction;
        this.random = random;
        this.current = initial;
    }

    public static BackoffPolicy defaultPolicy() {
        return new BackoffPolicy(
                Duration.ofMillis(500),
                Duration.ofSeconds(30),
                2.0,
                0.2,
                new Random());
    }

    public synchronized Duration next() {
        Duration result = applyJitter(current);
        long nextMs = Math.min(max.toMillis(), (long) (current.toMillis() * multiplier));
        current = Duration.ofMillis(nextMs);
        return result;
    }

    public synchronized void reset() {
        current = initial;
    }

    private Duration applyJitter(Duration base) {
        if (jitterFraction == 0.0) {
            return base;
        }
        double factor = 1.0 + (random.nextDouble() * 2.0 - 1.0) * jitterFraction;
        long jitteredMs = Math.max(1L, (long) (base.toMillis() * factor));
        return Duration.ofMillis(jitteredMs);
    }
}