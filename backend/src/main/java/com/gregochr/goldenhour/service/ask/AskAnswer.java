package com.gregochr.goldenhour.service.ask;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * A validated answer.
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)}: a stored Ready answer is read back by
 * {@link AskReadyStore}'s plain mapper, which throws on a property it does not know. A field added
 * after this one is read fail-soft by a build that has this annotation, so a rollback never drops a
 * stored row for a property it merely does not know (the build that introduced {@code windows} is the
 * first to tolerate; any earlier build still drops such a row until the next precompute rewrites it).
 *
 * @param answerable false when the forecast cannot answer; picks and events are then empty
 * @param summary    the cleaned, word-capped summary; never blank
 * @param picks      at most three picks, ranked
 * @param events     the event cards
 * @param missing    what PhotoCast does not have, when not answerable; otherwise null
 * @param windows    the rating distribution of every window whose figures the answer's prose may have
 *                   quoted: each window {@code list_windows} returned in the conversation, each pick's
 *                   solar window and, for a Ready question, each window the question covers. Built by
 *                   the validator from the live snapshot (never by the model), served beside the
 *                   summary and compared on every serve ({@link AskReadyFreshness}); empty on an answer
 *                   with none and on a row written before the field existed, which reads as "unknown,
 *                   not stale"
 * @param warnings   the Met Office warnings the answer mentions, or null (omitted from JSON); set only by the
 *                   validator, never by the model
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AskAnswer(boolean answerable, String summary, List<AskPick> picks,
        List<AskEvent> events, String missing, List<WindowSpread> windows,
        @JsonInclude(JsonInclude.Include.NON_NULL) WarningsCard warnings) {

    /**
     * The Met Office warnings an answer mentions, as the server holds them (weather-warnings-plan.md section 14,
     * W6): attached only to an answer whose prose mentions a warning the model was shown, read per request and never
     * stored (such an answer is not cached and Ready answers never carry one). The client prints the level word, the
     * phrase and the attribution from this, never from the model's sentence.
     *
     * @param stale       whether the feed may be out of date, so the answer must not read as complete
     * @param attribution the attribution line the licence requires
     * @param url         the Met Office warnings page
     * @param items       the warnings shown to the model, most severe first
     * @param checkedAt   when the Met Office feed was last read successfully for these facts (ISO instant, the same
     *                    clock as {@code GET /api/warnings}' {@code lastCheckedAt}), so a client only judges the
     *                    facts stale against a list at least that new; omitted when unknown
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WarningsCard(boolean stale, String attribution, String url, List<WarningCard> items,
            @JsonInclude(JsonInclude.Include.NON_NULL) String checkedAt) {

        /**
         * Facts with no feed-check time.
         *
         * @param stale       whether the feed may be out of date
         * @param attribution the attribution line
         * @param url         the Met Office page
         * @param items       the warnings
         */
        public WarningsCard(boolean stale, String attribution, String url, List<WarningCard> items) {
            this(stale, attribution, url, items, null);
        }

        /** Canonical constructor: takes an immutable copy; null reads as empty. */
        public WarningsCard {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    /**
     * One warning as served: the Met Office's level, types, validity and headline, never reworded.
     *
     * @param id        the Met Office warning id
     * @param version   the version in force
     * @param level     the level word as served (upper case)
     * @param weatherTypes the weather types as served
     * @param phrase    the level and weather types, "Amber warning of snow"
     * @param validFrom start of validity (ISO instant), or null
     * @param validTo   end of validity (ISO instant), or null
     * @param headline  the Met Office headline, or null
     * @param updated   whether this is an update (the style guide's "Updated" label)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WarningCard(String id, String version, String level, List<String> weatherTypes, String phrase,
            String validFrom, String validTo, String headline, boolean updated) {

        /** Canonical constructor: takes an immutable copy; null reads as empty. */
        public WarningCard {
            weatherTypes = weatherTypes == null ? List.of() : List.copyOf(weatherTypes);
        }
    }

    /**
     * An answer that carries no warnings.
     *
     * @param answerable false when the forecast cannot answer
     * @param summary    the cleaned summary
     * @param picks      the picks
     * @param events     the event cards
     * @param missing    what PhotoCast does not have, or null
     * @param windows    the carried windows
     */
    public AskAnswer(boolean answerable, String summary, List<AskPick> picks, List<AskEvent> events,
            String missing, List<WindowSpread> windows) {
        this(answerable, summary, picks, events, missing, windows, null);
    }

    /**
     * One carried window: its id and the whole spread the answer was given against.
     *
     * @param windowId the solar window id
     * @param spread   the window's rating distribution over the question's scope
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WindowSpread(String windowId, AskSnapshot.Spread spread) {
    }

    /** Canonical constructor: takes immutable copies; a null list reads as empty. */
    public AskAnswer {
        picks = picks == null ? List.of() : List.copyOf(picks);
        events = events == null ? List.of() : List.copyOf(events);
        windows = windows == null ? List.of() : List.copyOf(windows);
    }

    /**
     * An answer that carries no window figures.
     *
     * @param answerable false when the forecast cannot answer
     * @param summary    the cleaned summary
     * @param picks      the picks
     * @param events     the event cards
     * @param missing    what PhotoCast does not have, or null
     */
    public AskAnswer(boolean answerable, String summary, List<AskPick> picks, List<AskEvent> events,
            String missing) {
        this(answerable, summary, picks, events, missing, List.of());
    }

    /**
     * The same answer carrying the given window figures.
     *
     * @param newWindows the carried windows
     * @return a copy with {@code windows} replaced
     */
    public AskAnswer withWindows(List<WindowSpread> newWindows) {
        return new AskAnswer(answerable, summary, picks, events, missing, newWindows, warnings);
    }

    /**
     * The same answer carrying the Met Office warnings it mentions.
     *
     * @param card the warnings, or null for none
     * @return a copy with {@code warnings} replaced
     */
    public AskAnswer withWarnings(WarningsCard card) {
        return new AskAnswer(answerable, summary, picks, events, missing, windows, card);
    }
}
