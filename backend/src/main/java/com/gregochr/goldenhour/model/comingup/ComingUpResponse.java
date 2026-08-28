package com.gregochr.goldenhour.model.comingup;

import java.time.LocalDate;
import java.util.List;

/**
 * The wire shape of {@code GET /api/almanac} — see {@code docs/engineering/coming-up-plan.md} §13,
 * the single source of truth for this schema. A field not there does not exist.
 *
 * <p>P1 populates {@code builtFor} and {@code entries}; {@code conditions} ships an empty list until
 * P4 builds the standing-conditions strip; {@code bands} and {@code counts} are null until P2. All
 * five components are declared now, in this phase, so a later phase never has to add one to an
 * already-shipped record.
 *
 * <p>Carries no per-user data (plan D2) — {@code isNew}, the badge and the since-line are all
 * client-derived from this user-independent payload plus the reader's own
 * {@code comingUpLastSeenDate} (D12), which is exactly what keeps this response safe to
 * ETag-revalidate and share.
 *
 * @param builtFor   the served UK civil date the feed was built for
 * @param bands      the surprise-score band edges; null until P2
 * @param counts     fixed/forecast/family counts; null until P2
 * @param conditions the standing conditions strip; empty until P4
 * @param entries    the chronology entries, ascending by start date then span length
 */
public record ComingUpResponse(
        LocalDate builtFor,
        ComingUpBands bands,
        ComingUpCounts counts,
        List<ComingUpCondition> conditions,
        List<ComingUpEntry> entries) {

    /**
     * Normalises the collections so a consumer never has to null-check them.
     */
    public ComingUpResponse {
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
        entries = entries == null ? List.of() : List.copyOf(entries);
    }
}
