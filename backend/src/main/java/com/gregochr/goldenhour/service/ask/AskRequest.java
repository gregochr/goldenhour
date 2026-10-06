package com.gregochr.goldenhour.service.ask;

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
 */
public record AskRequest(String question, String windowId, List<Long> regionIds, String view) {

    /** The views a question may be asked from. */
    public static final Set<String> VIEWS = Set.of("map", "plan", "coming-up");
}
