package com.gregochr.goldenhour.service.runs;

import com.gregochr.goldenhour.entity.CycleType;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.model.runs.RunDeepType;

/**
 * Which population a run's medians and 90th percentile are taken over.
 *
 * <p>Each cycle type, the briefing, Ask ready answers and the sky eval are populations of their own. "Other" is
 * never one mixed population: it is split per run type and evaluation model, so Wildlife comfort (model WILDLIFE)
 * and Astro conditions (no model), which share a run type, are not averaged together.
 *
 * @param type     the display type
 * @param runType  the stored run type, for jobs; null for cycles and the sky eval
 * @param model    the evaluation model, only for the "other" jobs (null for the rest and for models not recorded)
 * @param cycle    the cycle type, for cycles; null otherwise
 */
record StatKey(RunDeepType type, RunType runType, EvaluationModel model, CycleType cycle) {

    /** The sky eval population. */
    static final StatKey SKY_EVAL = new StatKey(RunDeepType.SKY_EVAL, null, null, null);

    /**
     * The population of a cycle type.
     *
     * @param cycleType the cycle type
     * @return its key
     */
    static StatKey ofCycle(CycleType cycleType) {
        return new StatKey(RunDeepType.ofCycle(cycleType), null, null, cycleType);
    }

    /**
     * The population of a job run.
     *
     * @param run the job run
     * @return its key
     */
    static StatKey ofJob(JobRunEntity run) {
        return ofJob(run.getRunType(), run.getEvaluationModel());
    }

    /**
     * The population of a job run type.
     *
     * @param runType the run type
     * @param model   the evaluation model, only used for the "other" jobs
     * @return its key
     */
    static StatKey ofJob(RunType runType, EvaluationModel model) {
        RunDeepType type = RunDeepType.of(runType);
        return type == RunDeepType.OTHER
                ? new StatKey(type, runType, model, null)
                : new StatKey(type, runType, null, null);
    }

    /**
     * Whether a duration means anything for this population. Ask's daily run is closed at creation.
     *
     * @return false for the daily Ask run
     */
    boolean hasDuration() {
        return runType != RunType.ASK;
    }
}
