package com.gregochr.goldenhour.model;

import java.time.Instant;

/**
 * Serialisable snapshot of a single location task's current state, sent via SSE.
 *
 * @param taskKey      unique key (locationName|targetDate|targetType)
 * @param locationName the location name
 * @param targetDate   the target date (ISO format)
 * @param targetType   SUNRISE, SUNSET, or HOURLY
 * @param state        current state in the FSM
 * @param errorMessage error message if FAILED, otherwise null
 * @param failedStep   the step that failed, otherwise null
 * @param lastUpdated  when this snapshot was last updated
 */
public record LocationTaskSnapshot(
        String taskKey,
        String locationName,
        String targetDate,
        String targetType,
        LocationTaskState state,
        String errorMessage,
        String failedStep,
        Instant lastUpdated
) {

    /**
     * Creates a snapshot from a {@link LocationTaskEvent}.
     *
     * @param event the event to convert
     * @return the snapshot
     */
    public static LocationTaskSnapshot fromEvent(LocationTaskEvent event) {
        return new LocationTaskSnapshot(
                event.getTaskKey(),
                event.getLocationName(),
                event.getTargetDate(),
                event.getTargetType(),
                event.getState(),
                event.getErrorMessage(),
                event.getFailedStep(),
                Instant.now()
        );
    }
}
