package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.client.ImoCalendarClient;
import com.gregochr.goldenhour.client.ImoCalendarException;
import com.gregochr.goldenhour.entity.ImoCalendarFetchEntity;
import com.gregochr.goldenhour.entity.ImoCalendarFetchOutcome;
import com.gregochr.goldenhour.entity.MeteorShowerPeakEntity;
import com.gregochr.goldenhour.entity.SchedulerFireStatus;
import com.gregochr.goldenhour.repository.ImoCalendarFetchRepository;
import com.gregochr.goldenhour.repository.MeteorShowerPeakRepository;
import com.gregochr.goldenhour.service.ImoCalendarParser.ParsedPeak;
import com.gregochr.goldenhour.service.notification.AdminAlertService;
import com.gregochr.goldenhour.util.ForecastHorizon;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The weekly job that keeps {@code meteor_shower_peak} current from the IMO Meteor Shower Calendar
 * (meteor-showers-plan Phase 4), registered as {@value #JOB_KEY} with {@link DynamicSchedulerService}.
 *
 * <p>For this UK calendar year and the next it fetches {@code cal{year}.pdf}, extracts it with PDFBox, parses
 * Table 5 ({@link ImoCalendarParser}), validates, and upserts the year's rows. The PDF is <b>always parsed</b>, with
 * no SHA short-circuit, so a corrected parser can re-apply to an unchanged PDF; {@code UNCHANGED} means the diff
 * against the stored rows found nothing. The first run over the migration-seeded rows (hash NULL, no diff) only
 * fills {@code source_sha256}.
 *
 * <p><b>Failure handling.</b> A 404 is {@code NOT_PUBLISHED} only for next year; for this year it is {@code FAILED},
 * so a moved URL is noticed. An invalid parse writes nothing. A failure never throws out of the job.
 *
 * <p><b>Alerts</b> (read from {@code imo_calendar_fetch}, nothing in memory), sent after the run's writes have
 * committed and the calendar has been reloaded: a revision of an existing row's night or rate (before and after);
 * an INVALID parse, once per distinct PDF; the third consecutive FAILED run for a year; and, once, next year's
 * calendar still unpublished on or after 1 October. A first fill of a new year is reported in the run summary,
 * not emailed.
 *
 * <p><b>Transactions.</b> The upsert, the diff read and the STORED/UNCHANGED log row share one
 * {@link TransactionTemplate} (a self-invoked {@code @Transactional} would bypass the proxy). FAILED, INVALID and
 * NOT_PUBLISHED rows are written in their own {@code REQUIRES_NEW} transaction. An {@link AtomicBoolean} guard
 * stops a Run Now and a cron fire from overlapping.
 */
@Service
public class ImoCalendarRefreshService {

    /** The {@code scheduler_job_config.job_key} this service registers under. */
    public static final String JOB_KEY = "imo_calendar_refresh";

    private static final Logger LOG = LoggerFactory.getLogger(ImoCalendarRefreshService.class);

    private static final int FAILED_RUNS_BEFORE_ALERT = 3;

    private static final int DETAIL_MAX = 500;

    private static final int HTTP_OK = 200;

    private static final int HTTP_NOT_FOUND = 404;

    /** From this month of the current year, an unpublished next-year calendar is worth one alert. */
    private static final Month NEXT_YEAR_EXPECTED_BY = Month.OCTOBER;

    private static final EnumSet<ImoCalendarFetchOutcome> PARSED_OUTCOMES = EnumSet.of(
            ImoCalendarFetchOutcome.INVALID, ImoCalendarFetchOutcome.STORED, ImoCalendarFetchOutcome.UNCHANGED);

    private final ImoCalendarClient client;
    private final MeteorShowerPeakRepository peaks;
    private final ImoCalendarFetchRepository fetchLog;
    private final MeteorPeakCalendar peakCalendar;
    private final AdminAlertService alerts;
    private final DynamicSchedulerService scheduler;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final TransactionTemplate isolated;
    private final AtomicBoolean running = new AtomicBoolean();

    /**
     * Constructs the service.
     *
     * @param client             fetches the calendar PDFs
     * @param peaks              the per-year peak rows
     * @param fetchLog           the refresh log the alert rules read
     * @param peakCalendar       reloaded after a write commits
     * @param alerts             sends the admin emails
     * @param scheduler          the dynamic scheduler the job registers with
     * @param clock              supplies "now" and the UK year
     * @param transactionManager opens the upsert transaction and the isolated log transactions
     */
    public ImoCalendarRefreshService(ImoCalendarClient client, MeteorShowerPeakRepository peaks,
            ImoCalendarFetchRepository fetchLog, MeteorPeakCalendar peakCalendar, AdminAlertService alerts,
            DynamicSchedulerService scheduler, Clock clock, PlatformTransactionManager transactionManager) {
        this.client = client;
        this.peaks = peaks;
        this.fetchLog = fetchLog;
        this.peakCalendar = peakCalendar;
        this.alerts = alerts;
        this.scheduler = scheduler;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
        this.isolated = new TransactionTemplate(transactionManager);
        this.isolated.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Registers the job with the dynamic scheduler. */
    @PostConstruct
    void registerJob() {
        scheduler.registerJobTarget(JOB_KEY, this::refresh);
    }

    /** One email to send once the run's writes are done. */
    private record Alert(String subject, String body) { }

    /** What a year's attempt came to. */
    private record YearResult(int year, ImoCalendarFetchOutcome outcome, boolean firstFill) { }

    /** What the upsert transaction did. */
    private record Stored(ImoCalendarFetchOutcome outcome, List<String> revisions, boolean firstFill) { }

    /**
     * Runs the refresh for this UK year and the next, reports the outcome to the scheduler, and never throws.
     * Does nothing (SKIPPED) when a run is already in progress.
     */
    public void refresh() {
        if (!running.compareAndSet(false, true)) {
            LOG.warn("IMO calendar refresh already running — skipping concurrent trigger");
            SchedulerFireOutcome.report(SchedulerFireStatus.SKIPPED, "Already running — skipped");
            return;
        }
        try {
            doRefresh();
        } catch (RuntimeException e) {
            LOG.error("IMO calendar refresh failed unexpectedly", e);
            SchedulerFireOutcome.report(SchedulerFireStatus.FAILED,
                    "Unexpected error: " + e.getClass().getSimpleName());
        } finally {
            running.set(false);
        }
    }

    private void doRefresh() {
        LocalDate today = ForecastHorizon.today(clock);
        Instant now = Instant.now(clock);
        List<Alert> pending = new ArrayList<>();
        List<YearResult> results = new ArrayList<>();
        for (int year = today.getYear(); year <= today.getYear() + 1; year++) {
            results.add(processYear(year, year == today.getYear(), today, now, pending));
        }
        for (Alert alert : pending) {
            sendAlert(alert);
        }
        report(results);
    }

    private YearResult processYear(int year, boolean currentYear, LocalDate today, Instant now, List<Alert> out) {
        String url = client.urlFor(year);
        ImoCalendarClient.Fetched fetched;
        try {
            fetched = client.fetch(year);
        } catch (ImoCalendarException e) {
            return failed(year, now, e.getHttpStatus(), e.getMessage(), url, out);
        } catch (RuntimeException e) {
            return failed(year, now, null, url + " could not be fetched: " + e, url, out);
        }
        if (!fetched.found()) {
            if (currentYear) {
                return failed(year, now, HTTP_NOT_FOUND,
                        url + " answered 404 for the current year (the URL may have moved)", url, out);
            }
            return notPublished(year, today, now, url, out);
        }
        String sha = sha256(fetched.pdf());
        Map<String, ParsedPeak> parsed;
        List<String> problems;
        try {
            parsed = ImoCalendarParser.parse(ImoCalendarPdfExtractor.extract(fetched.pdf()), year);
            problems = ImoCalendarParser.problems(parsed, year);
        } catch (IOException | RuntimeException e) {
            return invalid(year, now, sha, List.of("the PDF could not be read: " + e.getMessage()), url, out);
        }
        if (!problems.isEmpty()) {
            return invalid(year, now, sha, problems, url, out);
        }
        Stored stored;
        try {
            stored = tx.execute(status -> store(year, url, sha, parsed, now));
        } catch (RuntimeException e) {
            return failed(year, now, HTTP_OK, "the calendar was read but could not be stored: " + e, url, out);
        }
        if (stored.outcome() == ImoCalendarFetchOutcome.STORED) {
            peakCalendar.reload();
        }
        if (!stored.revisions().isEmpty()) {
            out.add(revisionAlert(year, url, stored.revisions()));
        }
        return new YearResult(year, stored.outcome(), stored.firstFill());
    }

    /** The upsert, the diff and the STORED/UNCHANGED log row, all in the caller's one transaction. */
    private Stored store(int year, String url, String sha, Map<String, ParsedPeak> parsed, Instant now) {
        Map<String, MeteorShowerPeakEntity> existing = new HashMap<>();
        for (MeteorShowerPeakEntity row : peaks.findAll()) {
            if (row.getCalendarYear() == year) {
                existing.put(row.getShowerCode(), row);
            }
        }
        boolean firstFill = existing.isEmpty();
        List<String> revisions = new ArrayList<>();
        int written = 0;
        for (Map.Entry<String, ParsedPeak> entry : parsed.entrySet()) {
            String code = entry.getKey();
            ParsedPeak peak = entry.getValue();
            MeteorShowerPeakEntity row = existing.get(code);
            if (row == null) {
                peaks.save(new MeteorShowerPeakEntity(code, year, peak.night(), peak.maxUtc(), peak.zhr(), url, sha,
                        now));
                written++;
                continue;
            }
            boolean moved = !row.getPeakNight().equals(peak.night()) || row.getZhr() != peak.zhr();
            boolean timeChanged = !Objects.equals(row.getMaxUtc(), peak.maxUtc());
            if (moved) {
                revisions.add(code + ": night " + row.getPeakNight() + " -> " + peak.night() + ", ZHR "
                        + row.getZhr() + " -> " + peak.zhr());
            }
            if (moved || timeChanged) {
                row.setPeakNight(peak.night());
                row.setMaxUtc(peak.maxUtc());
                row.setZhr(peak.zhr());
                row.setSourceUrl(url);
                row.setSourceSha256(sha);
                row.setFetchedAt(now);
                peaks.save(row);
                written++;
            } else if (!sha.equals(row.getSourceSha256())) {
                row.setSourceSha256(sha);
                peaks.save(row);
            }
        }
        ImoCalendarFetchOutcome outcome = written > 0
                ? ImoCalendarFetchOutcome.STORED : ImoCalendarFetchOutcome.UNCHANGED;
        fetchLog.save(logRow(year, now, outcome, HTTP_OK, sha,
                written > 0 ? written + " row(s) written" : "no change"));
        return new Stored(outcome, revisions, firstFill && written > 0);
    }

    private YearResult failed(int year, Instant now, Integer status, String detail, String url, List<Alert> out) {
        LOG.warn("IMO calendar {}: fetch failed: {}", year, detail);
        record(logRow(year, now, ImoCalendarFetchOutcome.FAILED, status, null, detail));
        try {
            List<ImoCalendarFetchEntity> latest = fetchLog.findTop4ByCalendarYearOrderByAttemptedAtDescIdDesc(year);
            int run = 0;
            while (run < latest.size() && latest.get(run).getOutcome() == ImoCalendarFetchOutcome.FAILED) {
                run++;
            }
            if (run == FAILED_RUNS_BEFORE_ALERT) {
                out.add(new Alert("PhotoCast: the IMO " + year + " meteor calendar has failed to load three runs "
                        + "in a row",
                        "The weekly meteor calendar refresh could not fetch " + url + " on three runs in a row "
                                + "(Run Now counts as a run). Latest: " + detail + "\n\nThe stored peak table is "
                                + "unchanged and the app keeps using it. The usual cause is a moved URL "
                                + "(photocast.meteor.imo.base-url) or the host not being reachable from "
                                + "production.\n"));
            }
        } catch (RuntimeException e) {
            LOG.warn("IMO calendar {}: could not read the failure history for the alert rule", year, e);
        }
        return new YearResult(year, ImoCalendarFetchOutcome.FAILED, false);
    }

    private YearResult invalid(int year, Instant now, String sha, List<String> problems, String url,
            List<Alert> out) {
        String detail = String.join("; ", problems);
        LOG.warn("IMO calendar {}: not usable, nothing written: {}", year, detail);
        boolean alert = true;
        try {
            alert = fetchLog.findFirstByCalendarYearAndOutcomeInOrderByAttemptedAtDescIdDesc(year, PARSED_OUTCOMES)
                    .map(prev -> !(prev.getOutcome() == ImoCalendarFetchOutcome.INVALID
                            && sha.equals(prev.getSha256())))
                    .orElse(true);
        } catch (RuntimeException e) {
            LOG.warn("IMO calendar {}: could not read the INVALID history, alerting", year, e);
        }
        record(logRow(year, now, ImoCalendarFetchOutcome.INVALID, HTTP_OK, sha, detail));
        if (alert) {
            out.add(new Alert("PhotoCast: the IMO " + year + " meteor calendar could not be read",
                    "The IMO " + year + " calendar at " + url + " was fetched but did not parse and validate, "
                            + "so nothing was written and the stored " + year + " rows are unchanged.\n\n"
                            + "Problems:\n- " + String.join("\n- ", problems) + "\n\nThis usually means IMO changed "
                            + "the layout of Table 5 or its prose. The parser is ImoCalendarParser.\n"));
        }
        return new YearResult(year, ImoCalendarFetchOutcome.INVALID, false);
    }

    private YearResult notPublished(int year, LocalDate today, Instant now, String url, List<Alert> out) {
        boolean alert = false;
        try {
            if (!today.isBefore(LocalDate.of(today.getYear(), NEXT_YEAR_EXPECTED_BY, 1))) {
                Instant since = ForecastHorizon.ukDayStartUtc(LocalDate.of(today.getYear(), NEXT_YEAR_EXPECTED_BY, 1))
                        .toInstant(ZoneOffset.UTC);
                alert = !fetchLog.existsByCalendarYearAndOutcomeAndAttemptedAtGreaterThanEqual(
                        year, ImoCalendarFetchOutcome.NOT_PUBLISHED, since);
            }
        } catch (RuntimeException e) {
            LOG.warn("IMO calendar {}: could not read the not-published history", year, e);
        }
        record(logRow(year, now, ImoCalendarFetchOutcome.NOT_PUBLISHED, HTTP_NOT_FOUND, null,
                "not published yet"));
        if (alert) {
            out.add(new Alert("PhotoCast: the IMO " + year + " meteor calendar is not published yet",
                    "It is " + today + " and " + url + " still answers 404. IMO normally publishes next year's "
                            + "calendar by the autumn. Until it does, " + year + " meteor peaks use the code's "
                            + "fixed dates and rates. This alert is sent once.\n"));
        }
        return new YearResult(year, ImoCalendarFetchOutcome.NOT_PUBLISHED, false);
    }

    private static Alert revisionAlert(int year, String url, List<String> revisions) {
        return new Alert("PhotoCast: IMO revised its " + year + " meteor calendar",
                "The IMO " + year + " calendar at " + url + " now differs from the peaks stored for " + year
                        + " (IMO revised it). The new values are already stored.\n\nBefore -> after:\n- "
                        + String.join("\n- ", revisions) + "\n");
    }

    private ImoCalendarFetchEntity logRow(int year, Instant now, ImoCalendarFetchOutcome outcome, Integer status,
            String sha, String detail) {
        ImoCalendarFetchEntity row = new ImoCalendarFetchEntity();
        row.setCalendarYear(year);
        row.setAttemptedAt(now);
        row.setOutcome(outcome);
        row.setHttpStatus(status);
        row.setSha256(sha);
        row.setDetail(detail == null || detail.length() <= DETAIL_MAX ? detail : detail.substring(0, DETAIL_MAX));
        return row;
    }

    /** Writes a log row in its own transaction; a failure is logged and never propagates. */
    private void record(ImoCalendarFetchEntity row) {
        try {
            isolated.executeWithoutResult(status -> fetchLog.save(row));
        } catch (RuntimeException e) {
            LOG.warn("Could not record the IMO calendar {} {} attempt", row.getCalendarYear(), row.getOutcome(), e);
        }
    }

    private void sendAlert(Alert alert) {
        try {
            alerts.sendMeteorCalendarAlert(alert.subject(), alert.body());
        } catch (RuntimeException e) {
            LOG.warn("Could not send the meteor calendar alert '{}' — the refresh is unaffected", alert.subject(), e);
        }
    }

    private static void report(List<YearResult> results) {
        List<String> parts = new ArrayList<>();
        int bad = 0;
        int good = 0;
        for (YearResult r : results) {
            String word = r.outcome().name().toLowerCase(Locale.ROOT).replace('_', ' ');
            parts.add(r.year() + " " + word + (r.firstFill() ? " (first fill)" : ""));
            if (r.outcome() == ImoCalendarFetchOutcome.FAILED || r.outcome() == ImoCalendarFetchOutcome.INVALID) {
                bad++;
            } else {
                good++;
            }
        }
        SchedulerFireStatus status = bad == 0 ? SchedulerFireStatus.SUCCEEDED
                : good == 0 ? SchedulerFireStatus.FAILED : SchedulerFireStatus.PARTIAL;
        SchedulerFireOutcome.report(status, String.join(" · ", parts));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
