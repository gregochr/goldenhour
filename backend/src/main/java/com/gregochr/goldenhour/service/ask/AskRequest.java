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
 * @param view      {@code map} or {@code plan} ({@code coming-up} is read as {@code plan})
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
     * @param view      {@code map} or {@code plan} ({@code coming-up} is read as {@code plan})
     */
    public AskRequest(String question, String windowId, List<Long> regionIds, String view) {
        this(question, windowId, regionIds, view, null);
    }

    /**
     * The retired Coming up view. The tab folded into the plan view ({@code docs/engineering/one-screen-plan.md}
     * D8), so a client loaded before the deploy may still send it; it is accepted for one release and logged
     * as {@link #PLAN_VIEW}.
     */
    public static final String LEGACY_COMING_UP_VIEW = "coming-up";

    /** The view the retired Coming up view is read as. */
    public static final String PLAN_VIEW = "plan";

    /** The views a question may be asked from. */
    public static final Set<String> VIEWS = Set.of("map", PLAN_VIEW, LEGACY_COMING_UP_VIEW);

    /**
     * The view a question is logged and served under: {@code coming-up} (an old client) reads as {@code plan}.
     *
     * @param view a view that passed {@link #VIEWS}
     * @return the view to record
     */
    public static String canonicalView(String view) {
        return LEGACY_COMING_UP_VIEW.equals(view) ? PLAN_VIEW : view;
    }
}
