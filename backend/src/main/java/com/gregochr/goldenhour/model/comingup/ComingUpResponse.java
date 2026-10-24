package com.gregochr.goldenhour.model.comingup;

import java.time.LocalDate;
import java.util.List;

/**
 * The wire shape of {@code GET /api/almanac} — see {@code docs/engineering/coming-up-plan.md} §13,
 * the single source of truth for this schema. A field not there does not exist.
 *
 * <p>{@code builtFor}, {@code bands}, {@code counts} and {@code entries} are the whole shape. The
 * standing-conditions strip's {@code conditions} component was removed with the Recurring
 * conditions panel (coming-up-glance plan B2).
 *
 * <p>Carries no per-user data (plan D2) — {@code isNew}, the badge and the since-line are all
 * client-derived from this user-independent payload plus the reader's own
 * {@code comingUpLastSeenDate} (D12), which is exactly what keeps this response safe to
 * ETag-revalidate and share.
 *
 * @param builtFor   the served UK civil date the feed was built for
 * @param bands      the surprise-score band edges; null until P2
 * @param counts     fixed/forecast/family counts; null until P2
 * @param entries    the chronology entries, ascending by start date then span length
 */
public record ComingUpResponse(
        LocalDate builtFor,
        ComingUpBands bands,
        ComingUpCounts counts,
        List<ComingUpEntry> entries) {

    /**
     * Normalises the collections so a consumer never has to null-check them.
     */
    public ComingUpResponse {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }
}
