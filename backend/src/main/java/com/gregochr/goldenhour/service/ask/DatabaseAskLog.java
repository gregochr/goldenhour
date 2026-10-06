package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.repository.AskLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Set;

/**
 * Writes {@code ask_log} (plan §2.5, V167): one row per answered request.
 *
 * <p><b>It decides what is stored, not the caller.</b> {@code AskService} hands over everything it
 * knows; this class keeps only what the plan allows:
 * <ul>
 *   <li>the question — only for {@code CLAUDE_OK} and {@code CLAUDE_CANT} (a question the engine
 *       answered), at most {@value #MAX_QUESTION} characters; any other outcome stores none, and the
 *       table's check constraint refuses one even if this rule had a bug;</li>
 *   <li>{@code missing} — only for a can't-answer ({@code PREFILTER_CANT}, {@code CLAUDE_CANT}), at
 *       most {@value #MAX_MISSING} characters.</li>
 * </ul>
 * Denied requests never reach it ({@code AskService} writes no row for a refusal).
 *
 * <p><b>It never fails the response.</b> A reader has already been answered, and may have paid for
 * it; losing a log row is not a reason to lose that. Any failure is logged at ERROR (not WARN: a log
 * that silently stops filling is a fault the owner should see) and swallowed. The insert is a native
 * statement, so a failure leaves nothing half-persisted in the request's session.
 */
@Component
public class DatabaseAskLog implements AskLog {

    private static final Logger LOG = LoggerFactory.getLogger(DatabaseAskLog.class);

    /** The longest stored question, in characters (the column's width). */
    static final int MAX_QUESTION = 200;

    /** The longest stored missing phrase, in characters (the column's width). */
    static final int MAX_MISSING = 60;

    /** The outcomes whose question is stored: the ones the engine answered. */
    private static final Set<Outcome> STORES_QUESTION = Set.of(Outcome.CLAUDE_OK, Outcome.CLAUDE_CANT);

    /** The outcomes that are an honest "not in the forecast", and so carry a missing phrase. */
    private static final Set<Outcome> STORES_MISSING = Set.of(Outcome.PREFILTER_CANT,
            Outcome.CLAUDE_CANT);

    private final AskLogRepository repository;
    private final Clock clock;

    /**
     * Creates the log.
     *
     * @param repository the {@code ask_log} repository
     * @param clock      the application clock (the row's {@code created_at})
     */
    public DatabaseAskLog(AskLogRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public void record(Entry entry) {
        try {
            String question = STORES_QUESTION.contains(entry.outcome())
                    ? cap(entry.normalisedQuestion(), MAX_QUESTION) : null;
            String missing = STORES_MISSING.contains(entry.outcome())
                    ? cap(entry.missing(), MAX_MISSING) : null;
            repository.insertRow(clock.instant(), entry.userId(), entry.scopeKey(), entry.view(),
                    entry.outcome().name(), question, missing, Math.max(0L, entry.durationMs()));
        } catch (RuntimeException e) {
            // The exception's class only: a database error's text can quote the failing row, which holds
            // the question, and nothing here may carry the question or the user into a log line.
            LOG.error("[ASK] The question log could not be written (outcome {}): {}",
                    entry.outcome(), e.getClass().getSimpleName());
        }
    }

    /**
     * Trims, treats blank as absent and cuts to {@code max} characters without splitting a
     * surrogate pair.
     *
     * @param text the text, or null
     * @param max  the most characters to keep
     * @return the capped text, or null when there is none
     */
    static String cap(String text, int max) {
        if (text == null) {
            return null;
        }
        String stripped = text.strip();
        if (stripped.isEmpty()) {
            return null;
        }
        if (stripped.codePointCount(0, stripped.length()) <= max) {
            return stripped;
        }
        return stripped.substring(0, stripped.offsetByCodePoints(0, max));
    }
}
