package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.EvaluationModel;

import java.time.LocalDateTime;

/**
 * One point on the calibration-drift graph: a single fixture's aggregate result within a single
 * completed run. The frontend groups these by {@code fixtureName} to draw one line per fixture over
 * time (rating against its band), and by {@code model} to compare tiers.
 *
 * @param runId         the parent run id
 * @param runTimestamp  when the run was taken (x-axis)
 * @param model         the model scored with
 * @param gitCommitHash the commit the prompt was at (so a step-change can be attributed to an edit)
 * @param fixtureName   the fixture this point summarises
 * @param expectedMin   the fixture's band floor (for the shaded band on the graph)
 * @param expectedMax   the fixture's band ceiling
 * @param avgRating     mean 1–5 rating across the run's N runs of this fixture
 * @param avgFierySky   mean 0–100 fiery-sky sub-score (finer drift signal than the rating)
 * @param avgGoldenHour mean 0–100 golden-hour sub-score
 * @param runs          how many runs of this fixture this point aggregates
 * @param passes        how many of those landed in band
 */
public record SkyRatingEvalTrendPoint(
        Long runId,
        LocalDateTime runTimestamp,
        EvaluationModel model,
        String gitCommitHash,
        String fixtureName,
        int expectedMin,
        int expectedMax,
        Double avgRating,
        Double avgFierySky,
        Double avgGoldenHour,
        int runs,
        int passes) {
}
