package com.gregochr.goldenhour.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link OrsRateLimiter}.
 *
 * <p><b>What breaks if these fail.</b> The permit count is the only thing standing between two
 * nightly sweeps — the per-user one and the region-base one — and an ORS rate-limit rejection that
 * would leave both matrices half-written. The count being <em>two</em> is the behaviour under test,
 * not an implementation detail: the whole reason this class exists rather than a {@code private
 * static} field in each caller is that two copies would silently permit four.
 */
class OrsRateLimiterTest {

    /** Long enough that a third caller genuinely blocks; short enough not to slow the suite. */
    private static final long LATCH_TIMEOUT_SECONDS = 5;

    @Test
    @DisplayName("returns the call's own result")
    void withPermit_returnsSuppliedValue() {
        assertThat(new OrsRateLimiter().withPermit(() -> "durations")).isEqualTo("durations");
    }

    @Test
    @DisplayName("admits exactly two concurrent calls and makes the third wait")
    void withPermit_admitsTwoConcurrently_andBlocksTheThird() throws Exception {
        OrsRateLimiter limiter = new OrsRateLimiter();
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch twoInside = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch thirdDone = new CountDownLatch(1);

        Runnable holder = () -> limiter.withPermit(() -> {
            peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            twoInside.countDown();
            try {
                release.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            inFlight.decrementAndGet();
            return null;
        });

        Thread first = new Thread(holder);
        Thread second = new Thread(holder);
        first.start();
        second.start();
        assertThat(twoInside.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

        Thread third = new Thread(() -> limiter.withPermit(() -> {
            peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            inFlight.decrementAndGet();
            thirdDone.countDown();
            return null;
        }));
        third.start();

        // The third must NOT get in while both permits are held. A pass here is the absence of an
        // event, so it is asserted as a timeout on its own latch rather than by sleeping.
        assertThat(thirdDone.await(200, TimeUnit.MILLISECONDS))
                .as("third call ran while both permits were held")
                .isFalse();

        release.countDown();
        assertThat(thirdDone.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        first.join();
        second.join();
        third.join();

        assertThat(peak.get()).as("concurrent ORS calls").isEqualTo(2);
    }

    @Test
    @DisplayName("releases the permit when the call throws, so a failure cannot leak one")
    void withPermit_releasesOnException() {
        OrsRateLimiter limiter = new OrsRateLimiter();

        for (int attempt = 0; attempt < 3; attempt++) {
            assertThatThrownBy(() -> limiter.withPermit(() -> {
                throw new IllegalStateException("ORS 503");
            })).isInstanceOf(IllegalStateException.class);
        }

        // Three failures have gone through two permits. If any leaked, this call blocks forever.
        assertThat(limiter.withPermit(() -> "still open")).isEqualTo("still open");
    }

    @Test
    @DisplayName("a thread already interrupted on entry fails loudly, even with permits free")
    void withPermit_interruptedOnEntry_throwsAndReinterrupts() {
        // `Semaphore.acquire()` throws on entry when the calling thread's interrupt flag is
        // already set, whether or not a permit is available — so this is the cheap deterministic
        // half of the contract, and the blocked case below is the other half.
        OrsRateLimiter limiter = new OrsRateLimiter();
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> limiter.withPermit(() -> "never reached"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Interrupted");
            assertThat(Thread.currentThread().isInterrupted())
                    .as("interrupt flag restored").isTrue();
        } finally {
            // Clears the flag whatever the assertions did, so a failure here cannot leak an
            // interrupted thread into whatever the runner schedules next.
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("an interrupt while waiting fails loudly and restores the interrupt flag")
    void withPermit_interruptedWhileWaiting_throwsAndReinterrupts() throws Exception {
        OrsRateLimiter limiter = new OrsRateLimiter();
        CountDownLatch bothHeld = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        Runnable holder = () -> limiter.withPermit(() -> {
            bothHeld.countDown();
            try {
                release.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        });
        Thread first = new Thread(holder);
        Thread second = new Thread(holder);
        first.start();
        second.start();
        assertThat(bothHeld.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

        AtomicInteger failures = new AtomicInteger();
        AtomicInteger flagRestored = new AtomicInteger();
        Thread waiter = new Thread(() -> {
            try {
                limiter.withPermit(() -> "never reached");
            } catch (IllegalStateException e) {
                failures.incrementAndGet();
                if (Thread.currentThread().isInterrupted()) {
                    flagRestored.incrementAndGet();
                }
            }
        });
        waiter.start();
        // ⚠️ BOUNDED. Unbounded, this loop turns the one mutation the class exists to catch — a
        // limiter that stops blocking — into a Maven run that never returns, in CI, with no output:
        // the waiter acquires immediately, finishes, and its state is TERMINATED forever. With a
        // deadline the same mutation fails as a failure.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(LATCH_TIMEOUT_SECONDS);
        while (waiter.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(waiter.getState())
                .as("waiter blocked on acquire — a limiter that stopped blocking fails here")
                .isEqualTo(Thread.State.WAITING);
        waiter.interrupt();
        waiter.join(TimeUnit.SECONDS.toMillis(LATCH_TIMEOUT_SECONDS));

        release.countDown();
        first.join();
        second.join();

        assertThat(failures.get()).as("interrupted acquire threw").isEqualTo(1);
        assertThat(flagRestored.get()).as("interrupt flag restored").isEqualTo(1);
    }
}
