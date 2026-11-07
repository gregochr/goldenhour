package com.gregochr.goldenhour.entity;

/**
 * How one calendar year's fetch ended in a run of the IMO calendar refresh ({@code imo_calendar_fetch.outcome}).
 */
public enum ImoCalendarFetchOutcome {

    /** The PDF parsed and validated, and at least one stored row was written or changed. */
    STORED,

    /** The PDF parsed and validated and the stored rows already matched (or only gained their hash). */
    UNCHANGED,

    /** Next year's calendar is not published yet (HTTP 404). */
    NOT_PUBLISHED,

    /** The PDF could not be fetched (a transport error, an unexpected status, a moved URL). */
    FAILED,

    /** The PDF was fetched but did not parse and validate, so nothing was written. */
    INVALID
}
