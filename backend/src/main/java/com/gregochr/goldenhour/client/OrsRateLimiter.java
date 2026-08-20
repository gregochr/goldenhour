package com.gregochr.goldenhour.client;

import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * The single concurrency limit on OpenRouteService matrix calls.
 *
 * <p><strong>Why this is a bean rather than a {@code private static} field.</strong> The permit
 * count exists because the ORS free tier rate-limits, and a rate limit is a property of the
 * <em>account</em> — so it has to be shared by every caller, not held once per caller. It began as
 * a {@code private static final Semaphore(2)} inside {@link
 * com.gregochr.goldenhour.service.DriveDurationService}, which was correct while that class was the
 * only route to the API; the heat field's shared region matrix (plan P7) is the second, and copying
 * the field would have doubled the effective concurrency while every comment still said two.
 *
 * <p><strong>It guards the HTTP call and nothing else.</strong> The permit used to be held across
 * the location lookup and the transactional write as well, because it wrapped the whole refresh
 * method. Narrowing it to the request is what lets a second caller queue behind the first for the
 * duration of one matrix POST rather than for the duration of a whole sweep — and it keeps the
 * property {@code DriveDurationService} was already careful about, that no database connection is
 * pinned while a permit is waited on.
 *
 * <p>Held by {@link OpenRouteServiceClient}, which is the one place every ORS request passes
 * through, so a future third caller cannot bypass the limit by forgetting to acquire.
 */
@Component
public class OrsRateLimiter {

    /** Concurrent ORS matrix calls permitted across the whole application. */
    private static final int MAX_CONCURRENT_CALLS = 2;

    private final Semaphore permits = new Semaphore(MAX_CONCURRENT_CALLS);

    /**
     * Runs the supplied call while holding one of the permits.
     *
     * <p>An interrupt while waiting restores the thread's interrupt flag and fails loudly: a
     * caller that silently returned "no durations" would be indistinguishable from ORS answering
     * with none, and the two demand opposite responses (retry versus accept).
     *
     * @param call the ORS call to make
     * @param <T>  the call's result type
     * @return whatever {@code call} returned
     * @throws IllegalStateException if the thread is interrupted while waiting for a permit
     */
    public <T> T withPermit(Supplier<T> call) {
        try {
            permits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for an ORS permit", e);
        }
        try {
            return call.get();
        } finally {
            permits.release();
        }
    }
}
