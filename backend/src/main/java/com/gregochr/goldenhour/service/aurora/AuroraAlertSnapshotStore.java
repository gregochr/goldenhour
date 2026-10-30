package com.gregochr.goldenhour.service.aurora;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.AuroraAlertSnapshotEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.AuroraForecastScore;
import com.gregochr.goldenhour.repository.AuroraAlertSnapshotRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Persists the standing real aurora alert in the one-row {@code aurora_alert_snapshot} table (V203), so
 * a restart can restore it rather than start IDLE and re-buy its scoring.
 * See {@code docs/engineering/aurora-restart-restore-plan.md}.
 *
 * <p>Persistence is an optimisation: a database failure in {@link #save}, {@link #confirm},
 * {@link #hold} or {@link #clear} is logged at WARN and swallowed, and a {@link #load} failure restores
 * nothing, which is the behaviour before the table existed. Nothing here may fail a poll or an admin
 * request. The methods are deliberately not {@code @Transactional}: a caught exception inside a proxied
 * transaction still marks it rollback-only, and the caller would then see an
 * {@code UnexpectedRollbackException}. The repository's own transactions are relied on instead.
 */
@Component
public class AuroraAlertSnapshotStore {

    private static final Logger LOG = LoggerFactory.getLogger(AuroraAlertSnapshotStore.class);

    private static final TypeReference<List<ScoreRow>> SCORE_ROWS = new TypeReference<>() { };

    /**
     * A persisted alert: the state to restore, the night it was last confirmed for, and the hold (if any)
     * a night poll had placed on it.
     *
     * @param view        the alert to restore
     * @param nightDate   UTC date of the dusk of the night that last raised, escalated or confirmed it
     * @param heldLevel   the level the alert was being held for, or {@code null}
     * @param heldTrigger the hold's trigger, or {@code null}
     */
    public record Snapshot(AuroraStateCache.RealAlertView view, LocalDate nightDate,
            AlertLevel heldLevel, TriggerType heldTrigger) {
    }

    /** The JSON shape of one score: ids rather than the entity {@link AuroraForecastScore} holds. */
    private record ScoreRow(long locationId, int stars, String alertLevel, int cloudPercent,
            String summary, String detail) {
    }

    private final AuroraAlertSnapshotRepository repository;
    private final LocationRepository locationRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * The night last written or confirmed by this JVM, so repeat {@link #confirm} calls for it skip the
     * database. Dropped by anything that changes the row another way ({@link #hold}, {@link #clear}).
     */
    private volatile LocalDate lastConfirmedNight;

    /**
     * Constructs the store.
     *
     * @param repository         the snapshot repository
     * @param locationRepository resolves the stored location ids
     * @param objectMapper       the internal (Jackson 2) mapper
     * @param clock              stamps {@code saved_at}
     */
    public AuroraAlertSnapshotStore(AuroraAlertSnapshotRepository repository,
            LocationRepository locationRepository, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.locationRepository = locationRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Upserts row 1 from {@code view}, with no hold.
     *
     * @param view  the standing real alert
     * @param night UTC date of the dusk of the night being saved for
     */
    public void save(AuroraStateCache.RealAlertView view, LocalDate night) {
        try {
            AuroraAlertSnapshotEntity row = new AuroraAlertSnapshotEntity();
            row.setNightDate(night);
            row.setAlertLevel(view.level().name());
            row.setActiveSince(view.activeSince());
            row.setTriggerType(view.triggerType() == null ? null : view.triggerType().name());
            row.setTriggerKp(view.triggerKp());
            row.setDarkSkyCount(view.darkSkyCount());
            row.setClearCount(view.clearCount());
            row.setScores(objectMapper.writeValueAsString(view.scores().stream()
                    .map(AuroraAlertSnapshotStore::toRow).toList()));
            row.setSavedAt(clock.instant());
            repository.save(row);
            lastConfirmedNight = night;
        } catch (Exception e) {
            lastConfirmedNight = null;
            LOG.warn("Aurora snapshot not saved — a restart will not restore this alert: {}",
                    e.getMessage());
        }
    }

    /**
     * Confirms the standing alert for {@code night}: moves {@code night_date} forward and drops any hold.
     * Writes at most once a night. If row 1 is missing (an earlier save failed) it upserts from the
     * supplied view, which is the only time the supplier is called.
     *
     * @param night  UTC date of the dusk of the night being confirmed
     * @param viewer supplies the current real alert; read only when a missing row must be rebuilt
     */
    public void confirm(LocalDate night, Supplier<Optional<AuroraStateCache.RealAlertView>> viewer) {
        if (night.equals(lastConfirmedNight)) {
            return;
        }
        try {
            if (repository.confirmNight(night) == 0 && !repository.existsById(AuroraAlertSnapshotEntity.ONLY_ID)) {
                viewer.get().ifPresent(view -> save(view, night));
                return;
            }
            lastConfirmedNight = night;
        } catch (Exception e) {
            LOG.warn("Aurora snapshot not confirmed: {}", e.getMessage());
        }
    }

    /**
     * Records that a night poll is holding the alert for a reading, so a restart before the first
     * daylight poll still makes the deferred dawn CLEAR. A no-op when there is no row.
     *
     * @param level   the level the alert is held for
     * @param trigger the hold's trigger
     */
    public void hold(AlertLevel level, TriggerType trigger) {
        lastConfirmedNight = null;
        try {
            repository.setHold(level.name(), trigger.name());
        } catch (Exception e) {
            LOG.warn("Aurora snapshot hold not recorded: {}", e.getMessage());
        }
    }

    /** Deletes row 1 if it exists. */
    public void clear() {
        lastConfirmedNight = null;
        try {
            if (repository.existsById(AuroraAlertSnapshotEntity.ONLY_ID)) {
                repository.deleteById(AuroraAlertSnapshotEntity.ONLY_ID);
            }
        } catch (Exception e) {
            LOG.warn("Aurora snapshot not cleared: {}", e.getMessage());
        }
    }

    /**
     * Reads row 1 back. A score whose location no longer exists is dropped; a disabled location is kept,
     * as the in-memory cache would.
     *
     * @return the stored alert, or empty when there is none or it could not be read
     */
    public Optional<Snapshot> load() {
        try {
            Optional<AuroraAlertSnapshotEntity> found = repository.findById(AuroraAlertSnapshotEntity.ONLY_ID);
            if (found.isEmpty()) {
                return Optional.empty();
            }
            AuroraAlertSnapshotEntity row = found.get();
            List<ScoreRow> rows = objectMapper.readValue(row.getScores(), SCORE_ROWS);
            Map<Long, LocationEntity> locations = locationRepository
                    .findAllById(rows.stream().map(ScoreRow::locationId).toList()).stream()
                    .collect(Collectors.toMap(LocationEntity::getId, l -> l, (a, b) -> a));
            List<AuroraForecastScore> scores = new ArrayList<>();
            for (ScoreRow r : rows) {
                LocationEntity location = locations.get(r.locationId());
                if (location != null) {
                    scores.add(new AuroraForecastScore(location, r.stars(), AlertLevel.valueOf(r.alertLevel()),
                            r.cloudPercent(), r.summary(), r.detail()));
                }
            }
            AuroraStateCache.RealAlertView view = new AuroraStateCache.RealAlertView(
                    AlertLevel.valueOf(row.getAlertLevel()), row.getActiveSince(),
                    row.getTriggerType() == null ? null : TriggerType.valueOf(row.getTriggerType()),
                    row.getTriggerKp(), row.getDarkSkyCount(), row.getClearCount(), scores);
            AlertLevel heldLevel = row.getHeldLevel() == null ? null : AlertLevel.valueOf(row.getHeldLevel());
            TriggerType heldTrigger = row.getHeldTrigger() == null
                    ? null : TriggerType.valueOf(row.getHeldTrigger());
            return Optional.of(new Snapshot(view, row.getNightDate(), heldLevel, heldTrigger));
        } catch (Exception e) {
            LOG.warn("Aurora snapshot could not be read — nothing restored: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private static ScoreRow toRow(AuroraForecastScore score) {
        return new ScoreRow(score.location().getId(), score.stars(), score.alertLevel().name(),
                score.cloudPercent(), score.summary(), score.detail());
    }
}
