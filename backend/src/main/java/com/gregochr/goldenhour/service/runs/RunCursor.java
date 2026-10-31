package com.gregochr.goldenhour.service.runs;

import com.gregochr.goldenhour.model.runs.RunKind;
import com.gregochr.goldenhour.model.runs.RunListItem;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;

/**
 * The opaque position of a row in the Runs list, for Load more.
 *
 * <p>The list is ordered newest first, ties broken by kind then id, both descending. A page asked for
 * "before" a cursor returns the rows strictly after that position in this order, so rows arriving at the
 * head between two requests never shift the next page.
 *
 * @param startedAt the row's start instant
 * @param kind      the row's kind
 * @param id        the row's id
 */
public record RunCursor(Instant startedAt, RunKind kind, long id) {

    /** The list order: newest first, then kind descending, then id descending. */
    public static final Comparator<RunListItem> ORDER = Comparator
            .comparing(RunListItem::startedAt, Comparator.reverseOrder())
            .thenComparing(RunListItem::kind, Comparator.reverseOrder())
            .thenComparing(RunListItem::id, Comparator.reverseOrder());

    private static final String SEPARATOR = "|";
    private static final int PART_COUNT = 3;

    /**
     * The cursor pointing at a row.
     *
     * @param item the row
     * @return its cursor
     */
    public static RunCursor of(RunListItem item) {
        return new RunCursor(item.startedAt(), item.kind(), item.id());
    }

    /**
     * Encodes the cursor as an opaque URL-safe string.
     *
     * @return the encoded cursor
     */
    public String encode() {
        String raw = startedAt + SEPARATOR + kind + SEPARATOR + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Decodes a cursor produced by {@link #encode()}.
     *
     * @param value the encoded cursor
     * @return the cursor
     * @throws IllegalArgumentException if the value is not a cursor this class produced
     */
    public static RunCursor decode(String value) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\" + SEPARATOR, -1);
            if (parts.length != PART_COUNT) {
                throw new IllegalArgumentException("Invalid cursor");
            }
            return new RunCursor(Instant.parse(parts[0]), RunKind.valueOf(parts[1]), Long.parseLong(parts[2]));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid cursor", e);
        }
    }

    /**
     * Whether a row comes strictly after this position in the list order (that is, is older, or ties and
     * ranks lower).
     *
     * @param item the row
     * @return true if the row sorts after the cursor
     */
    public boolean isFollowedBy(RunListItem item) {
        int byTime = item.startedAt().compareTo(startedAt);
        if (byTime != 0) {
            return byTime < 0;
        }
        int byKind = item.kind().compareTo(kind);
        if (byKind != 0) {
            return byKind < 0;
        }
        return item.id() < id;
    }
}
