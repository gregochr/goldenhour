package com.gregochr.goldenhour.model.runs;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One CI prompt-regression run (one suite of one attempt) as the Day view lists it.
 *
 * @param prNumber   the pull request, or null for a manual run
 * @param prTitle    the pull request title, or null
 * @param suite      the suite
 * @param attempt    the run attempt (1 for the first)
 * @param conclusion the job's conclusion as GitHub reported it, or null while running
 * @param htmlUrl    the run's GitHub URL, null unless it is under this repository's {@code actions/runs}
 * @param startedAt  when the suite's job started
 * @param cases      test cases run, null when unknown
 * @param failed     test cases that failed, null when unknown
 * @param input      uncached input tokens, null when usage is unknown
 * @param output     output tokens, null when usage is unknown
 * @param cacheRead  cache-read tokens, null when usage is unknown
 * @param costGbp    pounds, null unless the usage is known and priced
 * @param costUsd    dollars, null unless the usage is known and priced
 * @param usageState {@code KNOWN}, {@code UNKNOWN} or {@code UNPRICED}
 * @param inputTotal every input token read ({@code input + cacheRead}), null when usage is unknown
 * @param cachedPercent {@code cacheRead} as a whole percent of {@code inputTotal}, null when that is zero or unknown
 */
public record PromptTestRunRow(Integer prNumber, String prTitle, CiSuite suite, int attempt, String conclusion,
        String htmlUrl, Instant startedAt, Integer cases, Integer failed, Long input, Long output, Long cacheRead,
        BigDecimal costGbp, BigDecimal costUsd, String usageState, Long inputTotal, Integer cachedPercent) {
}
