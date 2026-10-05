package com.gregochr.goldenhour.service.ask;

/**
 * The result of one engine run.
 *
 * @param status   how it ended
 * @param answer   the validated answer; null unless {@code status} is OK or CANT
 * @param personal true when the conversation used the asker's own drive times, so the answer must
 *                 not be shared
 * @param turns    how many model turns it took
 */
public record AskOutcome(Status status, AskAnswer answer, boolean personal, int turns) {

    /** How an engine run ended. */
    public enum Status {
        /** A validated, answerable reply. */
        OK,
        /** The forecast cannot answer the question. */
        CANT,
        /** The run failed (timeout, refusal, no submitted answer, a discarded answer). */
        FAILED
    }
}
