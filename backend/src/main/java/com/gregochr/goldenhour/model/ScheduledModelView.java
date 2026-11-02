package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.EvaluationModel;

/**
 * One row of the weekly Sky-Rating Eval schedule: a selectable model and whether the weekly run
 * includes it.
 *
 * @param model     the model
 * @param scheduled true when the weekly run scores this model
 */
public record ScheduledModelView(EvaluationModel model, boolean scheduled) {
}
