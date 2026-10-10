package com.gregochr.goldenhour.service.ask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * The body of {@code POST /api/ask} (plan §2.9).
 *
 * @param question  the question as typed
 * @param windowId  the context window ({@code yyyy-MM-dd_sunrise|sunset}), optional; one that is not
 *                  in the window set is ignored, not refused
 * @param regionIds the regions asked about; null or empty means every region
 * @param view      {@code map}, {@code plan} or {@code coming-up}
 * @param thread    the session's earlier exchanges, oldest first, or null/empty for a fresh question
 *                  ({@code docs/engineering/ask-thread-plan.md} §2.1); validated by the service, so a
 *                  null entry is kept here to be refused as {@code INVALID}
 */
public record AskRequest(String question, String windowId, List<Long> regionIds, String view,
        List<ThreadExchange> thread) {

    /** Canonical constructor: an unmodifiable copy of the thread (null entries kept); null reads as none. */
    public AskRequest {
        thread = thread == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(thread));
    }

    /**
     * A fresh question, with no thread.
     *
     * @param question  the question as typed
     * @param windowId  the context window, optional
     * @param regionIds the regions asked about; null or empty means every region
     * @param view      {@code map}, {@code plan} or {@code coming-up}
     */
    public AskRequest(String question, String windowId, List<Long> regionIds, String view) {
        this(question, windowId, regionIds, view, null);
    }

    /** The views a question may be asked from. */
    public static final Set<String> VIEWS = Set.of("map", "plan", "coming-up");
}
