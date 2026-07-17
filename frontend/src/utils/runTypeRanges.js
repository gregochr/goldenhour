/**
 * The forecast horizon each run type covers.
 *
 * <p>Mirrors the backend's `RunType.defaultDateRange` — the single source of truth. These
 * numbers drive admin cost estimates for real Claude spend, so they must not drift from it:
 * the Prompt Test and Model Selection views each used to carry their own copy, and when the
 * backend narrowed LONG_TERM to the T+5 horizon the Prompt Test copy was left quoting
 * "T+3 to T+7" and 5 days — a 67% over-estimate on the dialog that gates the run.
 *
 * <p>Only the run types the admin tools can trigger are listed; the batch/briefing types have
 * no date-range picker.
 */
export const RUN_TYPE_RANGES = {
  VERY_SHORT_TERM: { days: 2, dateRange: 'T, T+1' },
  SHORT_TERM: { days: 3, dateRange: 'T to T+2' },
  LONG_TERM: { days: 3, dateRange: 'T+3 to T+5' },
};

/**
 * Number of target dates a run type sweeps.
 *
 * @param {string} runType - The run type key.
 * @returns {number|undefined} Day count, or undefined for run types without a range.
 */
export function daysForRunType(runType) {
  return RUN_TYPE_RANGES[runType]?.days;
}
