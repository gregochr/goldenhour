package com.gregochr.goldenhour.model;

import org.springframework.context.ApplicationEvent;

/**
 * Spring application event fired on each per-location state transition during a forecast run.
 *
 * <p>Published by {@code ForecastService} at phase boundaries and consumed by
 * {@code RunProgressTracker} for SSE broadcasting.
 */
public class LocationTaskEvent extends ApplicationEvent {

    private final long jobRunId;
    private final String taskKey;
    private final String locationName;
    private final String targetDate;
    private final String targetType;
    private final LocationTaskState state;
    private final String errorMessage;
    private final String failedStep;

    /**
     * Constructs a location task event.
     *
     * @param source       the event source
     * @param jobRunId     the parent job run ID
     * @param taskKey      unique key (locationName|targetDate|targetType)
     * @param locationName the location name
     * @param targetDate   the target date (ISO format)
     * @param targetType   SUNRISE, SUNSET, or HOURLY
     * @param state        the new state
     * @param errorMessage error message if FAILED, otherwise null
     * @param failedStep   the step that failed (e.g. "FETCHING_WEATHER"), otherwise null
     */
    public LocationTaskEvent(Object source, long jobRunId, String taskKey,
            String locationName, String targetDate, String targetType,
            LocationTaskState state, String errorMessage, String failedStep) {
        super(source);
        this.jobRunId = jobRunId;
        this.taskKey = taskKey;
        this.locationName = locationName;
        this.targetDate = targetDate;
        this.targetType = targetType;
        this.state = state;
        this.errorMessage = errorMessage;
        this.failedStep = failedStep;
    }

    /**
     * Returns the parent job run ID.
     *
     * @return the job run ID
     */
    public long getJobRunId() {
        return jobRunId;
    }

    /**
     * Returns the unique task key.
     *
     * @return the task key (locationName|targetDate|targetType)
     */
    public String getTaskKey() {
        return taskKey;
    }

    /**
     * Returns the location name.
     *
     * @return the location name
     */
    public String getLocationName() {
        return locationName;
    }

    /**
     * Returns the target date in ISO format.
     *
     * @return the target date string
     */
    public String getTargetDate() {
        return targetDate;
    }

    /**
     * Returns the target type (SUNRISE, SUNSET, HOURLY).
     *
     * @return the target type string
     */
    public String getTargetType() {
        return targetType;
    }

    /**
     * Returns the new state.
     *
     * @return the location task state
     */
    public LocationTaskState getState() {
        return state;
    }

    /**
     * Returns the error message, or null if not FAILED.
     *
     * @return the error message, or null
     */
    public String getErrorMessage() {
        return errorMessage;
    }

    /**
     * Returns the step that failed, or null if not FAILED.
     *
     * @return the failed step name, or null
     */
    public String getFailedStep() {
        return failedStep;
    }
}
