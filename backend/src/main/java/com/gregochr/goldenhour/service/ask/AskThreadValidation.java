package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.service.ask.ThreadExchange.EventRef;
import com.gregochr.goldenhour.service.ask.ThreadExchange.PickRef;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Turns the {@code thread} of a {@code POST /api/ask} body into a validated {@link AskThread}
 * (plan §2.1), or refuses it as {@code 400 INVALID}.
 *
 * <p>Everything in a thread is client-supplied, so everything is checked here and nothing is passed
 * through: the exchange count against {@code photocast.ask.thread.max-exchanges}; each question
 * through the live question's own {@link AskQuestionSanitiser#sanitiseTyped} rules; each summary
 * through {@link AskQuestionSanitiser#sanitiseThreadSummary}; each pick as a positive location id and
 * a well-formed {@link AskWindowId}; each event as a plain type token and a date; and a
 * {@code generatedAt} on every exchange. A null entry anywhere is refused too. The refusals say
 * <em>which kind</em> of thing was wrong and never echo any of the text.
 */
final class AskThreadValidation {

    /** The most picks one exchange may carry: an answer never carries more. */
    static final int MAX_PICKS_PER_EXCHANGE = AskAnswerValidator.MAX_PICKS;

    /** The most event cards one exchange may carry. */
    static final int MAX_EVENTS_PER_EXCHANGE = 10;

    /** The longest event type accepted. */
    static final int MAX_EVENT_TYPE_LENGTH = 64;

    private static final Pattern EVENT_TYPE = Pattern.compile("[A-Za-z0-9_-]+");

    private static final String EMPTY_ENTRY = "The conversation held an empty entry.";
    private static final String BAD_QUESTION = "An earlier question in the conversation could not be used.";
    private static final String BAD_REFERENCES = "An earlier answer in the conversation named a place, "
            + "window or event that could not be read.";
    private static final String NO_TIME = "An earlier answer in the conversation is missing the time "
            + "it was given.";

    private AskThreadValidation() {
    }

    /**
     * Validates and cleans a thread.
     *
     * @param requested    the exchanges as sent, oldest first; null or empty is no thread
     * @param maxExchanges {@code photocast.ask.thread.max-exchanges}
     * @return the cleaned thread; {@link AskThread#EMPTY} when none was sent
     * @throws AskRefusal {@code INVALID} for any exchange or entry that cannot be used
     */
    static AskThread validate(List<ThreadExchange> requested, int maxExchanges) {
        if (requested == null || requested.isEmpty()) {
            return AskThread.EMPTY;
        }
        if (requested.size() > maxExchanges) {
            throw invalid("The conversation can carry at most " + maxExchanges + " earlier questions.");
        }
        List<ThreadExchange> cleaned = new ArrayList<>(requested.size());
        for (ThreadExchange exchange : requested) {
            if (exchange == null) {
                throw invalid(EMPTY_ENTRY);
            }
            cleaned.add(clean(exchange));
        }
        return new AskThread(cleaned);
    }

    private static ThreadExchange clean(ThreadExchange exchange) {
        AskQuestionSanitiser.Result question = AskQuestionSanitiser.sanitiseTyped(exchange.question());
        if (!question.ok()) {
            throw invalid(BAD_QUESTION);
        }
        AskQuestionSanitiser.Result summary = AskQuestionSanitiser.sanitiseThreadSummary(exchange.summary());
        if (!summary.ok()) {
            throw invalid(summary.error());
        }
        if (exchange.generatedAt() == null) {
            throw invalid(NO_TIME);
        }
        return new ThreadExchange(question.sanitised(), summary.sanitised(), picks(exchange.picks()),
                events(exchange.events()), exchange.generatedAt(), exchange.ready());
    }

    private static List<PickRef> picks(List<PickRef> picks) {
        if (picks.size() > MAX_PICKS_PER_EXCHANGE) {
            throw invalid(BAD_REFERENCES);
        }
        List<PickRef> kept = new ArrayList<>(picks.size());
        for (PickRef pick : picks) {
            if (pick == null) {
                throw invalid(EMPTY_ENTRY);
            }
            if (pick.locationId() == null || pick.locationId() <= 0
                    || AskWindowId.parse(pick.windowId()).isEmpty()) {
                throw invalid(BAD_REFERENCES);
            }
            kept.add(pick);
        }
        return kept;
    }

    private static List<EventRef> events(List<EventRef> events) {
        if (events.size() > MAX_EVENTS_PER_EXCHANGE) {
            throw invalid(BAD_REFERENCES);
        }
        List<EventRef> kept = new ArrayList<>(events.size());
        for (EventRef event : events) {
            if (event == null) {
                throw invalid(EMPTY_ENTRY);
            }
            if (event.type() == null || event.type().length() > MAX_EVENT_TYPE_LENGTH
                    || !EVENT_TYPE.matcher(event.type()).matches() || event.date() == null) {
                throw invalid(BAD_REFERENCES);
            }
            kept.add(event);
        }
        return kept;
    }

    private static AskRefusal invalid(String message) {
        return new AskRefusal(AskErrorCode.INVALID, message);
    }
}
