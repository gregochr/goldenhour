package com.gregochr.goldenhour.integration;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.CiSpendModelUsageEntity;
import com.gregochr.goldenhour.entity.CiSpendRunEntity;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.CiSpendRunRepository;
import com.gregochr.goldenhour.repository.JobRunRepository;
import com.gregochr.goldenhour.service.JwtService;
import com.gregochr.goldenhour.util.ForecastHorizon;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The isolation guarantee of the CI spend series (ci-spend-plan.md invariant 4), proved through the real endpoints:
 * adding {@code ci_spend_run} rows changes <em>nothing</em> in what the app's Runs views serve except the new
 * {@code testing} and {@code promptTestRuns} fields.
 *
 * <p>Each endpoint (Cost trends for 30 days and for all time, the Day view of a day that has both app and CI runs
 * including its {@code filterCounts}, {@code earliestDay} and {@code summary}, the calendar month, and a scheduler
 * job's Claude spend) is read twice against a real Postgres through the full security filter chain: once with CI rows
 * and app job runs in place, once with the CI rows deleted. The two bodies, minus those two fields, must be equal.
 * The CI rows are real: one priced, one with unknown usage, and a re-run (attempt 2).
 *
 * <p><b>CI-only.</b> It extends {@link HttpIntegrationTestBase}, which starts a Postgres Testcontainer, and there is
 * no Docker on the development machine (see CLAUDE.md), so it cannot run locally.
 */
class CiSpendIsolationIntegrationTest extends HttpIntegrationTestBase {

    private static final String ADMIN = "ci-spend-isolation-admin";
    private static final String URL = "https://github.com/gregochr/goldenhour/actions/runs/";

    @Autowired
    private JobRunRepository jobRuns;

    @Autowired
    private CiSpendRunRepository ciRuns;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private JwtService jwtService;

    private LocalDate yesterday;
    private final List<Long> jobRunIds = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        users.save(AppUserEntity.builder().username(ADMIN).password("never-authenticates").role(UserRole.ADMIN)
                .enabled(true).createdAt(LocalDateTime.now()).build());
        yesterday = ForecastHorizon.today(Clock.systemUTC()).minusDays(1);
        // Two completed app runs with a priced cost, one yesterday and one ten days earlier.
        jobRunIds.add(appRun(yesterday, 3_000_000L));
        jobRunIds.add(appRun(yesterday.minusDays(10), 1_500_000L));
        // CI: a priced sky run and an unknown ask run yesterday, a re-run of sky, and one ten days back.
        ciRun(1L, 1, "sky", yesterday.atTime(10, 0), CiSpendRunEntity.STATE_KNOWN, 2_000_000L, 0.79, true);
        ciRun(1L, 1, "ask", yesterday.atTime(10, 5), CiSpendRunEntity.STATE_UNKNOWN, null, null, false);
        ciRun(1L, 2, "sky", yesterday.atTime(14, 0), CiSpendRunEntity.STATE_KNOWN, 500_000L, 0.79, true);
        ciRun(2L, 1, "ask", yesterday.minusDays(10).atTime(9, 0), CiSpendRunEntity.STATE_KNOWN, 800_000L, 0.79, true);
    }

    @AfterEach
    void tearDown() {
        ciRuns.deleteAll();
        jobRuns.deleteAllById(jobRunIds);
        users.findByUsername(ADMIN).ifPresent(users::delete);
    }

    private long appRun(LocalDate day, long micro) {
        LocalDateTime start = day.atTime(3, 0);
        return jobRuns.save(JobRunEntity.builder().runType(RunType.TIDE).startedAt(start)
                .completedAt(start.plusMinutes(2)).durationMs(120_000L).locationsProcessed(3).succeeded(3).failed(0)
                .triggeredManually(false).totalCostMicroDollars(micro).exchangeRateGbpPerUsd(0.79).build()).getId();
    }

    private void ciRun(long runId, int attempt, String suite, LocalDateTime startedUtc, String state, Long micro,
            Double rate, boolean withModel) {
        CiSpendRunEntity row = new CiSpendRunEntity();
        row.setGithubRunId(runId);
        row.setRunAttempt(attempt);
        row.setSuite(suite);
        row.setEvent("pull_request");
        row.setPrNumber(1912);
        row.setPrTitle("fix: valley mist wording");
        row.setHtmlUrl(URL + runId + "/attempts/" + attempt);
        row.setStartedAt(startedUtc.toInstant(ZoneOffset.UTC));
        row.setConclusion("success");
        row.setCases(10);
        row.setFailed(0);
        row.setUsageState(state);
        row.setCostMicroDollars(micro);
        row.setExchangeRateGbpPerUsd(rate);
        row.setFetchedAt(Instant.now());
        if (withModel) {
            CiSpendModelUsageEntity model = new CiSpendModelUsageEntity();
            model.setModel("claude-sonnet-5-5");
            model.setCalls(10);
            model.setInputTokens(100_000L);
            model.setOutputTokens(10_000L);
            model.setCacheReadTokens(50_000L);
            model.setCostMicroDollars(micro);
            row.addModel(model);
        }
        ciRuns.save(row);
    }

    private String body(String url) throws Exception {
        return mockMvc.perform(get(url).header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + jwtService.generateAccessToken(ADMIN, UserRole.ADMIN)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    /** The body as a map with the CI-only fields removed. */
    private static Map<String, Object> withoutCiFields(String body) {
        Map<String, Object> map = new LinkedHashMap<>(JsonPath.<Map<String, Object>>read(body, "$"));
        map.remove("testing");
        map.remove("promptTestRuns");
        return map;
    }

    private List<String> urls() {
        return List.of(
                "/api/admin/runs/cost?days=30",
                "/api/admin/runs/cost?days=all",
                "/api/admin/runs/day?date=" + yesterday,
                "/api/admin/runs/day?date=" + yesterday.minusDays(10),
                "/api/admin/runs/calendar?month=" + YearMonth.from(yesterday),
                "/api/admin/scheduler/jobs/near_term_batch_evaluation/detail");
    }

    @Test
    @DisplayName("CI rows change nothing the app serves except testing and promptTestRuns, on every Runs endpoint")
    void ciRowsNeverTouchAnAppFigure() throws Exception {
        List<String> urls = urls();
        List<String> with = new java.util.ArrayList<>();
        for (String url : urls) {
            with.add(body(url));
        }
        ciRuns.deleteAll();
        for (int i = 0; i < urls.size(); i++) {
            String without = body(urls.get(i));
            assertThat(withoutCiFields(with.get(i))).as(urls.get(i)).isEqualTo(withoutCiFields(without));
        }
    }

    @Test
    @DisplayName("the CI rows are served in their own fields: testing on cost, promptTestRuns on the day, and "
            + "nothing of either once the rows are gone")
    void ciRowsAreServedApart() throws Exception {
        String cost = body("/api/admin/runs/cost?days=30");
        String all = body("/api/admin/runs/cost?days=all");
        String day = body("/api/admin/runs/day?date=" + yesterday);

        assertThat(JsonPath.<Double>read(cost, "$.totals.lastGbp")).isGreaterThan(0.0);
        assertThat(JsonPath.<Integer>read(cost, "$.testing.totals.runs")).isEqualTo(4);
        assertThat(JsonPath.<Integer>read(cost, "$.testing.totals.unknownRuns")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(cost, "$.testing.days.length()"))
                .isEqualTo(JsonPath.<Integer>read(cost, "$.days.length()"));
        assertThat(JsonPath.<List<String>>read(cost, "$.testing.types[*].suite")).containsExactly("SKY", "ASK");
        assertThat(JsonPath.<Integer>read(all, "$.testing.totals.runs")).isEqualTo(4);
        assertThat(JsonPath.<Integer>read(day, "$.promptTestRuns.length()")).isEqualTo(3);
        assertThat(JsonPath.<List<Integer>>read(day, "$.promptTestRuns[*].attempt")).containsExactly(1, 1, 2);
        assertThat(JsonPath.<List<String>>read(day, "$.promptTestRuns[*].usageState"))
                .containsExactly("KNOWN", "UNKNOWN", "KNOWN");
        assertThat(JsonPath.<Integer>read(day, "$.filterCounts.ALL")).isGreaterThanOrEqualTo(1);

        ciRuns.deleteAll();
        assertThat(JsonPath.<Object>read(body("/api/admin/runs/cost?days=30"), "$.testing")).isNull();
        assertThat(JsonPath.<Integer>read(body("/api/admin/runs/day?date=" + yesterday),
                "$.promptTestRuns.length()")).isZero();
    }
}
