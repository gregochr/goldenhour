package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.EvaluationModel;

/**
 * Side-by-side result of an admin advisor replay: the picks the current live prompt produced and,
 * when a candidate prompt was supplied, the picks it produced — for a by-eye before/after diff.
 *
 * <p>Comparison is deliberately left to the human: both pick-sets (with their
 * {@link BestBetStatus}) are returned and the reader diffs them. No automated pick comparison.
 *
 * @param model     the model both replays were run against
 * @param current   the outcome under the current live system prompt
 * @param candidate the outcome under the supplied candidate prompt, or null when none was supplied
 */
public record AdvisorReplayResponse(EvaluationModel model, BestBetResult current,
        BestBetResult candidate) {
}
