package com.gregochr.goldenhour.service.ask;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The wire shape of {@code GET /api/ask/ready} (plan §2.9).
 *
 * <p>A pick here carries no {@code ratingAtAnswer} or {@code verdictAtAnswer}: those are the
 * server's freshness record, and a card's figures are joined by the client from the briefing it
 * already holds. Every other field is the live snapshot's, never the model's text.
 *
 * @param scope     {@code all}, or the region id as text
 * @param questions the fresh questions, in catalogue order; empty when none are
 */
public record AskReadyResponse(String scope, List<Question> questions) {

    /**
     * One Ready question with its answer.
     *
     * @param id          the question's name, e.g. {@code BEST_NEXT}
     * @param text        the question text, fixed when the answer was written
     * @param tabs        the tabs the question is offered on
     * @param generatedAt when the briefing the answer was built from was generated (UTC)
     * @param runLabel    {@code HH:mm} Europe/London of {@code generatedAt}
     * @param answer      the answer
     * @param nightVersion the opaque night-forecast version ({@link AskNightVersion}) the answer was
     *                    written under, present only on a night-bound question (user-less, so it has no
     *                    aurora segment); the client echoes it on the exchange a tap opens, exactly as
     *                    it echoes a typed answer's, so a Ready-origin exchange resets the thread by the
     *                    same rule. Omitted from the wire when null
     */
    public record Question(String id, String text, List<String> tabs, LocalDateTime generatedAt,
            String runLabel, Answer answer, @JsonInclude(JsonInclude.Include.NON_NULL) String nightVersion) {

        /**
         * A question that is not night-bound: no night version.
         *
         * @param id          the question's name
         * @param text        the question text
         * @param tabs        the tabs the question is offered on
         * @param generatedAt when the briefing the answer was built from was generated (UTC)
         * @param runLabel    {@code HH:mm} Europe/London of {@code generatedAt}
         * @param answer      the answer
         */
        public Question(String id, String text, List<String> tabs, LocalDateTime generatedAt,
                String runLabel, Answer answer) {
            this(id, text, tabs, generatedAt, runLabel, answer, null);
        }
    }

    /**
     * An answer as the client reads it.
     *
     * @param answerable always true for a stored Ready answer
     * @param kind       always {@code ready}
     * @param summary    the cleaned, word-capped summary
     * @param picks      the picks, ranked
     * @param events     the event cards, each carrying its safety note when the served topic has one
     * @param missing    always null for a stored Ready answer
     * @param tryThese   up to two other fresh Ready questions for the scope, chosen by the server,
     *                   serialised as {@code try}
     */
    public record Answer(boolean answerable, String kind, String summary, List<Pick> picks,
            List<AskEvent> events, @JsonInclude(JsonInclude.Include.ALWAYS) String missing,
            @JsonProperty("try") List<Suggestion> tryThese) {
    }

    /**
     * A pick card's server-side identity.
     *
     * @param rank         1-based rank
     * @param locationId   the location id
     * @param locationName the live location name
     * @param regionName   the live region name
     * @param date         the window's date
     * @param targetType   the window's kind (SUNRISE, SUNSET, ASTRO or AURORA)
     * @param windowId     the window id
     * @param why          the model's reason, cleaned
     */
    public record Pick(int rank, long locationId, String locationName, String regionName,
            LocalDate date, AskWindowKind targetType, String windowId, String why) {

        /**
         * Drops the freshness record from a validated pick.
         *
         * @param pick the pick, re-decorated from live data
         * @return its wire form
         */
        static Pick of(AskPick pick) {
            return new Pick(pick.rank(), pick.locationId(), pick.locationName(), pick.regionName(),
                    pick.date(), pick.targetType(), pick.windowId(), pick.why());
        }
    }

    /**
     * Another Ready question to try.
     *
     * @param id   the question's name
     * @param text the question text
     */
    public record Suggestion(String id, String text) {
    }
}
