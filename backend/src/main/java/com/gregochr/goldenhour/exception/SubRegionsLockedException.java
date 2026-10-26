package com.gregochr.goldenhour.exception;

import java.time.LocalDate;

/**
 * A reader's sub-region change is refused because the change rule's lock has not ended (sub-regions-plan
 * §4.3). Mapped to HTTP 409 with code {@code SUB_REGIONS_LOCKED} and {@code nextChangeOn} by
 * {@link com.gregochr.goldenhour.controller.GlobalExceptionHandler}. A {@link RuntimeException}, so the
 * transaction that threw it rolls back.
 */
public class SubRegionsLockedException extends RuntimeException {

    /** The error code the client switches on. */
    public static final String CODE = "SUB_REGIONS_LOCKED";

    private static final long serialVersionUID = 1L;

    private final LocalDate nextChangeOn;

    /**
     * Constructs the exception.
     *
     * @param nextChangeOn the first UK civil date the reader may make this change
     */
    public SubRegionsLockedException(LocalDate nextChangeOn) {
        super("Your sub-regions can next be changed on " + nextChangeOn);
        this.nextChangeOn = nextChangeOn;
    }

    /**
     * The first UK civil date the change is allowed.
     *
     * @return the date
     */
    public LocalDate getNextChangeOn() {
        return nextChangeOn;
    }
}
