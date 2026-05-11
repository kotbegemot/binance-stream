package io.stream.quotes.source;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class BackoffPolicyTest {

    @Test
    void sequenceDoublesUpToCap() {
        BackoffPolicy policy = new BackoffPolicy(
                Duration.ofMillis(500),
                Duration.ofSeconds(30),
                2.0,
                0.0,
                new Random(0));

        assertThat(policy.next()).isEqualTo(Duration.ofMillis(500));
        assertThat(policy.next()).isEqualTo(Duration.ofSeconds(1));
        assertThat(policy.next()).isEqualTo(Duration.ofSeconds(2));
        assertThat(policy.next()).isEqualTo(Duration.ofSeconds(4));
        assertThat(policy.next()).isEqualTo(Duration.ofSeconds(8));
        assertThat(policy.next()).isEqualTo(Duration.ofSeconds(16));
        assertThat(policy.next()).isEqualTo(Duration.ofSeconds(30));
        assertThat(policy.next()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void resetReturnsToInitial() {
        BackoffPolicy policy = new BackoffPolicy(
                Duration.ofMillis(500),
                Duration.ofSeconds(30),
                2.0,
                0.0,
                new Random(0));

        policy.next();
        policy.next();
        policy.reset();

        assertThat(policy.next()).isEqualTo(Duration.ofMillis(500));
    }

    @Test
    void jitterStaysWithinFractionOfBase() {
        BackoffPolicy policy = new BackoffPolicy(
                Duration.ofMillis(1000),
                Duration.ofSeconds(30),
                2.0,
                0.2,
                new Random(42));

        for (int i = 0; i < 1000; i++) {
            policy.reset();
            Duration d = policy.next();
            assertThat(d.toMillis()).isBetween(800L, 1200L);
        }
    }

    @Test
    void independentInstancesDoNotShareState() {
        BackoffPolicy a = new BackoffPolicy(
                Duration.ofMillis(500), Duration.ofSeconds(30), 2.0, 0.0, new Random(0));
        BackoffPolicy b = new BackoffPolicy(
                Duration.ofMillis(500), Duration.ofSeconds(30), 2.0, 0.0, new Random(0));

        a.next();
        a.next();

        assertThat(a.next()).isEqualTo(Duration.ofSeconds(2));
        assertThat(b.next()).isEqualTo(Duration.ofMillis(500));
    }

    @Test
    void defaultPolicyMatchesDocumentedDefaults() {
        BackoffPolicy policy = BackoffPolicy.defaultPolicy();
        Duration first = policy.next();

        assertThat(first.toMillis()).isBetween(400L, 600L);
    }

    @Test
    void rejectsInvalidInitial() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new BackoffPolicy(Duration.ZERO, Duration.ofSeconds(1), 2.0, 0.0, new Random()));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new BackoffPolicy(Duration.ofMillis(-1), Duration.ofSeconds(1), 2.0, 0.0, new Random()));
    }

    @Test
    void rejectsMaxLessThanInitial() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new BackoffPolicy(Duration.ofSeconds(5), Duration.ofSeconds(1), 2.0, 0.0, new Random()));
    }

    @Test
    void rejectsNonGrowingMultiplier() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new BackoffPolicy(Duration.ofMillis(500), Duration.ofSeconds(30), 1.0, 0.0, new Random()));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new BackoffPolicy(Duration.ofMillis(500), Duration.ofSeconds(30), 0.5, 0.0, new Random()));
    }

    @Test
    void rejectsInvalidJitter() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new BackoffPolicy(Duration.ofMillis(500), Duration.ofSeconds(30), 2.0, -0.1, new Random()));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new BackoffPolicy(Duration.ofMillis(500), Duration.ofSeconds(30), 2.0, 1.0, new Random()));
    }
}