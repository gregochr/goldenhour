package com.gregochr.goldenhour.client;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

/**
 * Declarative HTTP interface for the read-only slice of the GitHub Actions REST API that {@code ci_spend_poll} uses
 * (docs/engineering/ci-spend-plan.md, C2).
 *
 * <p>The base URL, {@code Authorization: Bearer}, {@code Accept: application/vnd.github+json} and
 * {@code X-GitHub-Api-Version} are set once on the underlying {@code RestClient} (see {@code GitHubActionsConfig}),
 * so the token is never a parameter and never appears in a log line. That client never follows redirects: the
 * artifact-zip call answers 302 and the signed blob URL is fetched elsewhere, without the token. JSON bodies come
 * back raw so that all parsing and validation live in the poller. A non-2xx answer surfaces as a
 * {@code RestClientResponseException} carrying the status.
 */
@HttpExchange
public interface GitHubActionsClient {

    /**
     * Lists a workflow's runs created on or after a date. No {@code status} filter: a run whose attempt 2 is in
     * progress reports {@code in_progress}, and its attempt 1 must still be seen.
     *
     * @param owner    repository owner
     * @param repo     repository name
     * @param workflow the workflow file name
     * @param created  the {@code created} filter, for example {@code >=2026-11-13}
     * @param perPage  page size (max 100)
     * @param page     1-based page number
     * @return the raw JSON ({@code total_count}, {@code workflow_runs})
     */
    @GetExchange("/repos/{owner}/{repo}/actions/workflows/{workflow}/runs")
    String listWorkflowRuns(@PathVariable("owner") String owner, @PathVariable("repo") String repo,
            @PathVariable("workflow") String workflow, @RequestParam("created") String created,
            @RequestParam("per_page") int perPage, @RequestParam("page") int page);

    /**
     * Reads one attempt of a workflow run.
     *
     * @param owner   repository owner
     * @param repo    repository name
     * @param runId   the workflow run id
     * @param attempt the attempt number
     * @return the raw run JSON
     */
    @GetExchange("/repos/{owner}/{repo}/actions/runs/{runId}/attempts/{attempt}")
    String getRunAttempt(@PathVariable("owner") String owner, @PathVariable("repo") String repo,
            @PathVariable("runId") long runId, @PathVariable("attempt") int attempt);

    /**
     * Lists the jobs of one attempt, with their steps.
     *
     * @param owner   repository owner
     * @param repo    repository name
     * @param runId   the workflow run id
     * @param attempt the attempt number
     * @param perPage page size (max 100)
     * @return the raw JSON ({@code jobs})
     */
    @GetExchange("/repos/{owner}/{repo}/actions/runs/{runId}/attempts/{attempt}/jobs")
    String listAttemptJobs(@PathVariable("owner") String owner, @PathVariable("repo") String repo,
            @PathVariable("runId") long runId, @PathVariable("attempt") int attempt,
            @RequestParam("per_page") int perPage);

    /**
     * Lists a run's artifacts, filtered by exact name.
     *
     * @param owner   repository owner
     * @param repo    repository name
     * @param runId   the workflow run id
     * @param name    the artifact name
     * @param perPage page size (max 100)
     * @return the raw JSON ({@code artifacts})
     */
    @GetExchange("/repos/{owner}/{repo}/actions/runs/{runId}/artifacts")
    String listRunArtifacts(@PathVariable("owner") String owner, @PathVariable("repo") String repo,
            @PathVariable("runId") long runId, @RequestParam("name") String name,
            @RequestParam("per_page") int perPage);

    /**
     * Asks for an artifact's zip. GitHub answers 302 with a short-lived signed {@code Location}; this client does
     * not follow it (that would send the token to the blob host), so the caller reads the header and fetches the
     * URL with a client that has no credentials.
     *
     * @param owner      repository owner
     * @param repo       repository name
     * @param artifactId the artifact id
     * @return the bodyless response, whose {@code Location} header is the signed URL
     */
    @GetExchange("/repos/{owner}/{repo}/actions/artifacts/{artifactId}/zip")
    ResponseEntity<Void> getArtifactZip(@PathVariable("owner") String owner, @PathVariable("repo") String repo,
            @PathVariable("artifactId") long artifactId);

    /**
     * Lists the pull requests a commit belongs to (the fallback when a run carries none, for example after the
     * branch was deleted).
     *
     * @param owner repository owner
     * @param repo  repository name
     * @param sha   the commit SHA
     * @return the raw JSON array of pull requests
     */
    @GetExchange("/repos/{owner}/{repo}/commits/{sha}/pulls")
    String listCommitPulls(@PathVariable("owner") String owner, @PathVariable("repo") String repo,
            @PathVariable("sha") String sha);
}
