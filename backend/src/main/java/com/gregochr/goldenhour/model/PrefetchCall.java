package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.ServiceName;

import java.time.LocalDateTime;

/**
 * The outcome of one Open-Meteo call made by the batch weather pre-fetch, captured as a value
 * because the job run it belongs to does not exist yet when the weather is fetched.
 *
 * @param service         the Open-Meteo service called
 * @param label           human-readable call label, e.g. {@code batch-prefetch forecast chunk 2/5}
 * @param coordsRequested coordinates sent in the call
 * @param coordsReturned  coordinates that came back with data
 * @param calledAt        UTC time the call started
 * @param durationMs      call duration in milliseconds, including its own retries
 * @param statusCode      HTTP status, or {@code null} when none could be determined
 * @param succeeded       whether every requested coordinate came back
 * @param error           failure description, or {@code null} on success
 * @param attempts        HTTP attempts made (1 plus the chunk retries used)
 */
public record PrefetchCall(
        ServiceName service,
        String label,
        int coordsRequested,
        int coordsReturned,
        LocalDateTime calledAt,
        long durationMs,
        Integer statusCode,
        boolean succeeded,
        String error,
        int attempts) {
}
