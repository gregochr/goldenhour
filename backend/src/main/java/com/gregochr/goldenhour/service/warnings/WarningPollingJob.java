package com.gregochr.goldenhour.service.warnings;

import com.gregochr.goldenhour.client.MetOfficeWarningClient;
import com.gregochr.goldenhour.config.WarningsProperties;
import com.gregochr.goldenhour.entity.SchedulerFireStatus;
import com.gregochr.goldenhour.entity.WeatherWarningVersionEntity;
import com.gregochr.goldenhour.repository.WeatherWarningVersionRepository;
import com.gregochr.goldenhour.service.DynamicSchedulerService;
import com.gregochr.goldenhour.service.SchedulerFireOutcome;
import com.gregochr.goldenhour.service.ServedBriefingEpoch;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Polls the Met Office NSWWS Atom feed and stores every warning version it has not seen.
 *
 * <p>Runs on the {@code weather_warning_poll} scheduler row (fixed delay) and self-gates on
 * {@code photocast.warnings.enabled}. One poll reads the feed, then fetches the detail of only the entries whose
 * (warning id, version) is not stored, and inserts each in its own transaction. The unique key makes a repeat a
 * no-op: an entry already stored is skipped, and a race that loses to the unique key is counted as already stored.
 *
 * <p>What it never does: delete, update, or infer a cancellation from an entry's absence from the feed (a
 * cancellation is a stored CANCELLED version). A detail that answers 404 or 410 (the Met Office expires updates
 * after about a day) and a detail that cannot be read are each skipped and counted; neither fails the poll. A bad
 * key (401, 403), a rate limit (429), an unreachable feed or a feed that is not Atom fail the poll, are recorded for
 * Operations, and leave every stored warning untouched.
 *
 * <p>{@link ServedBriefingEpoch#bump()} is called once, after the last insert has committed, and only when a
 * version was stored. The key is never logged.
 */
@Component
public class WarningPollingJob {

    private static final Logger LOG = LoggerFactory.getLogger(WarningPollingJob.class);

    /** The scheduler row this job runs on. */
    public static final String JOB_KEY = "weather_warning_poll";

    static final String REASON_NOT_CONFIGURED = "NOT_CONFIGURED";
    static final String REASON_UNAUTHORISED = "UNAUTHORISED";
    static final String REASON_RATE_LIMITED = "RATE_LIMITED";
    static final String REASON_UNREACHABLE = "UNREACHABLE";
    static final String REASON_MALFORMED_FEED = "MALFORMED_FEED";
    static final String REASON_HTTP_PREFIX = "HTTP_";

    private static final int HTTP_UNAUTHORISED = 401;
    private static final int HTTP_FORBIDDEN = 403;
    private static final int HTTP_NOT_FOUND = 404;
    private static final int HTTP_GONE = 410;
    private static final int HTTP_TOO_MANY = 429;

    private final MetOfficeWarningClient client;
    private final WarningFeedParser parser;
    private final WeatherWarningVersionRepository repository;
    private final WarningsProperties properties;
    private final WarningPollStatus status;
    private final ServedBriefingEpoch epoch;
    private final DynamicSchedulerService scheduler;
    private final Clock clock;
    private final TransactionTemplate transactions;

    /** Guards against overlap: the schedule and Run Now can both call {@link #poll()}. */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Creates the job.
     *
     * @param client       the NSWWS client
     * @param parser       the feed parser
     * @param repository   the insert-only warning store
     * @param properties   warnings configuration
     * @param status       the in-memory poll record
     * @param epoch        the serve epoch, bumped when something was stored
     * @param scheduler    the dynamic scheduler the job registers with
     * @param clock        the real clock (the poller is never rewound)
     * @param transactionManager the transaction manager, for one transaction per entry
     */
    public WarningPollingJob(MetOfficeWarningClient client, WarningFeedParser parser,
            WeatherWarningVersionRepository repository, WarningsProperties properties, WarningPollStatus status,
            ServedBriefingEpoch epoch, DynamicSchedulerService scheduler, Clock clock,
            PlatformTransactionManager transactionManager) {
        this.client = client;
        this.parser = parser;
        this.repository = repository;
        this.properties = properties;
        this.status = status;
        this.epoch = epoch;
        this.scheduler = scheduler;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** Registers the job with the dynamic scheduler. */
    @PostConstruct
    void registerJob() {
        scheduler.registerJobTarget(JOB_KEY, this::poll);
    }

    /**
     * The scheduler's target: one poll, unless the flag is off or a poll is already running.
     */
    public void poll() {
        if (!properties.isEnabled()) {
            SchedulerFireOutcome.report(SchedulerFireStatus.SKIPPED, "Weather warnings are switched off");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            LOG.warn("Weather warning poll skipped: a poll is already running");
            SchedulerFireOutcome.report(SchedulerFireStatus.SKIPPED, "Already running, skipped");
            return;
        }
        try {
            runPoll();
        } finally {
            running.set(false);
        }
    }

    private void runPoll() {
        Instant started = clock.instant();
        String feedUrl = properties.getBaseUrl();
        if (feedUrl == null || feedUrl.isBlank()) {
            fail(started, REASON_NOT_CONFIGURED, new Tally());
            return;
        }
        String feedBody;
        try {
            feedBody = client.getFeed(URI.create(feedUrl));
        } catch (RestClientResponseException e) {
            fail(started, reasonFor(e.getStatusCode().value()), new Tally());
            return;
        } catch (RuntimeException e) {
            fail(started, REASON_UNREACHABLE, new Tally());
            return;
        }
        WarningFeedParser.FeedParse feed;
        try {
            feed = parser.parseFeed(feedBody);
        } catch (WarningFeedException e) {
            fail(started, REASON_MALFORMED_FEED, new Tally());
            return;
        }
        Tally tally = new Tally();
        tally.feedEntries = feed.entries().size() + feed.malformed();
        tally.malformed = feed.malformed();
        String failure = null;
        for (WarningFeedParser.FeedEntry entry : feed.entries()) {
            if (repository.existsByWarningIdAndWarningVersion(entry.warningId(), entry.warningVersion())) {
                tally.alreadyStored++;
                continue;
            }
            String entryFailure = ingest(entry, tally);
            if (entryFailure != null) {
                failure = failure == null ? entryFailure : failure;
                if (isAccessFailure(entryFailure)) {
                    break;
                }
            }
        }
        if (tally.stored > 0) {
            epoch.bump();
        }
        if (failure != null) {
            fail(started, failure, tally);
        } else {
            status.record(new WarningPollStatus.Attempt(started, true, null, tally.feedEntries, tally.stored,
                    tally.alreadyStored, tally.gone, tally.malformed));
            SchedulerFireOutcome.report(SchedulerFireStatus.SUCCEEDED, summary(tally));
        }
    }

    /** Fetches, parses and stores one entry; returns a failure reason when the poll should count as failed. */
    private String ingest(WarningFeedParser.FeedEntry entry, Tally tally) {
        String body;
        try {
            body = client.getWarning(entry.detailUri());
        } catch (RestClientResponseException e) {
            int code = e.getStatusCode().value();
            if (code == HTTP_NOT_FOUND || code == HTTP_GONE) {
                tally.gone++;
                return null;
            }
            return reasonFor(code);
        } catch (RuntimeException e) {
            return REASON_UNREACHABLE;
        }
        WeatherWarningVersionEntity row;
        try {
            row = parser.parseDetail(body, entry);
        } catch (WarningFeedException e) {
            LOG.warn("Skipping malformed warning detail {} v{}: {}", entry.warningId(), entry.warningVersion(),
                    e.getMessage());
            tally.malformed++;
            return null;
        }
        row.setSimulated(false);
        row.setReceivedAt(clock.instant());
        try {
            transactions.execute(s -> repository.save(row));
        } catch (DataIntegrityViolationException e) {
            tally.alreadyStored++;
            return null;
        }
        tally.stored++;
        for (String type : WarningFeedParser.stringList(row.getWeatherTypes())) {
            if (!WarningFeedParser.isKnownType(type)) {
                status.countUnknownType(type);
            }
        }
        return null;
    }

    private void fail(Instant started, String reason, Tally tally) {
        LOG.warn("Weather warning poll failed: {}", reason);
        status.record(new WarningPollStatus.Attempt(started, false, reason, tally.feedEntries, tally.stored,
                tally.alreadyStored, tally.gone, tally.malformed));
        SchedulerFireOutcome.report(SchedulerFireStatus.FAILED, "Met Office warnings poll failed: " + reason);
    }

    private static boolean isAccessFailure(String reason) {
        return REASON_UNAUTHORISED.equals(reason) || REASON_RATE_LIMITED.equals(reason);
    }

    private static String reasonFor(int httpStatus) {
        if (httpStatus == HTTP_UNAUTHORISED || httpStatus == HTTP_FORBIDDEN) {
            return REASON_UNAUTHORISED;
        }
        if (httpStatus == HTTP_TOO_MANY) {
            return REASON_RATE_LIMITED;
        }
        return REASON_HTTP_PREFIX + httpStatus;
    }

    private static String summary(Tally t) {
        return SchedulerFireOutcome.count(t.stored, "new warning version", "new warning versions") + " stored, "
                + t.alreadyStored + " already held, " + t.gone + " expired, " + t.malformed + " unreadable";
    }

    /** Counters for one poll. */
    private static final class Tally {
        private int feedEntries;
        private int stored;
        private int alreadyStored;
        private int gone;
        private int malformed;
    }
}
