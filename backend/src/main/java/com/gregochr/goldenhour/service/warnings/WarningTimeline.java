package com.gregochr.goldenhour.service.warnings;

import com.gregochr.goldenhour.entity.WeatherWarningVersionEntity;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Decides which warning versions are in force "now" for the injected {@link Clock}. Pure: it reads nothing but its
 * arguments and the clock, so a rewound admin (whose clock is the rewound instant) sees the warnings as we had them
 * then.
 *
 * <p>Active at T is the latest version (by version number) with {@code received_at <= T}, whose status is not
 * CANCELLED and whose {@code valid_to > T}. A warning whose only stored version is a cancellation shows nothing.
 * The latest version whose level rank rose over its predecessor's raises the flag ({@code upgradedFrom} is the
 * predecessor's level, {@code upgradedAt} the raising version's {@code received_at}) for {@link #UPGRADE_FLAG_HOURS}
 * after that receipt. A later area- or time-only version keeps the flag; a later downgrade below the raised level
 * clears it. Downgrades and edits alone never raise it. Simulated rows are ignored altogether unless the caller
 * asks for them.
 *
 * <p>Versions are decimals ("3.0") and are compared numerically. Warnings are ordered as the Met Office style guide
 * requires (plan section 12): level (Red, Amber, Yellow), then impact, then likelihood (both highest first, a
 * missing value last), then {@code validFrom} (earliest first), then warning id for a stable order. A warning is
 * "updated" when its version in force is 2.0 or later (an ISSUED warning that has been re-issued), which is separate
 * from the upgrade flag (a level rise within 24 hours).
 */
@Component
public class WarningTimeline {

    /** How long after receipt an upgrade is flagged. */
    static final long UPGRADE_FLAG_HOURS = 24;

    private static final Duration UPGRADE_FLAG_WINDOW = Duration.ofHours(UPGRADE_FLAG_HOURS);

    private static final BigDecimal FIRST_UPDATE_VERSION = new BigDecimal("2.0");

    private static final int RANK_YELLOW = 1;
    private static final int RANK_AMBER = 2;
    private static final int RANK_RED = 3;

    /**
     * A warning version in force, with its upgrade flag when it has one.
     *
     * @param version      the version in force
     * @param upgradedFrom the previous level when the level rose within the flag window, else null
     * @param upgradedAt   when the upgrading version was received, else null
     */
    public record ActiveWarning(WeatherWarningVersionEntity version, String upgradedFrom, Instant upgradedAt) {

        /**
         * Whether the version in force is an update (version 2.0 or later); the style guide requires such a warning
         * to be labelled updated.
         *
         * @return true when the version is at least 2.0
         */
        public boolean updated() {
            return version.getWarningVersion().compareTo(FIRST_UPDATE_VERSION) >= 0;
        }
    }

    private final Clock clock;

    /**
     * Creates the timeline.
     *
     * @param clock the application clock; a rewound admin request sees the rewound instant
     */
    public WarningTimeline(Clock clock) {
        this.clock = clock;
    }

    /**
     * The warnings in force at the clock's instant, most severe first.
     *
     * @param versions         every stored version of the warnings of interest
     * @param includeSimulated whether admin-simulated rows count
     * @return the active warnings; never null
     */
    public List<ActiveWarning> active(Collection<WeatherWarningVersionEntity> versions, boolean includeSimulated) {
        Instant now = clock.instant();
        Map<String, List<WeatherWarningVersionEntity>> byId = new LinkedHashMap<>();
        for (WeatherWarningVersionEntity v : versions) {
            if ((includeSimulated || !v.isSimulated()) && !v.getReceivedAt().isAfter(now)) {
                byId.computeIfAbsent(v.getWarningId(), k -> new ArrayList<>()).add(v);
            }
        }
        List<ActiveWarning> out = new ArrayList<>();
        for (List<WeatherWarningVersionEntity> history : byId.values()) {
            history.sort(Comparator.comparing(WeatherWarningVersionEntity::getWarningVersion));
            activeOf(history, now).ifPresent(out::add);
        }
        out.sort(Comparator
                .comparingInt((ActiveWarning a) -> levelRank(a.version().getWarningLevel())).reversed()
                .thenComparing(Comparator.comparingInt((ActiveWarning a) -> orZero(a.version().getImpact()))
                        .reversed())
                .thenComparing(Comparator.comparingInt((ActiveWarning a) -> orZero(a.version().getLikelihood()))
                        .reversed())
                .thenComparing(a -> a.version().getValidFrom(), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(a -> a.version().getWarningId()));
        return out;
    }

    /**
     * Ranks a level word for comparison.
     *
     * @param level a level as received (Yellow, Amber, Red); case ignored
     * @return 3 Red, 2 Amber, 1 Yellow, 0 for anything else or null
     */
    public static int levelRank(String level) {
        if (level == null) {
            return 0;
        }
        return switch (level.trim().toLowerCase(Locale.ROOT)) {
            case "yellow" -> RANK_YELLOW;
            case "amber" -> RANK_AMBER;
            case "red" -> RANK_RED;
            default -> 0;
        };
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    private static Optional<ActiveWarning> activeOf(List<WeatherWarningVersionEntity> history, Instant now) {
        WeatherWarningVersionEntity latest = history.get(history.size() - 1);
        if (WeatherWarningVersionEntity.STATUS_CANCELLED.equals(latest.getStatus())
                || latest.getValidTo() == null || !latest.getValidTo().isAfter(now)) {
            return Optional.empty();
        }
        for (int i = history.size() - 1; i > 0; i--) {
            WeatherWarningVersionEntity raising = history.get(i);
            WeatherWarningVersionEntity before = history.get(i - 1);
            if (levelRank(raising.getWarningLevel()) > levelRank(before.getWarningLevel())) {
                boolean fresh = now.isBefore(raising.getReceivedAt().plus(UPGRADE_FLAG_WINDOW));
                boolean held = levelRank(latest.getWarningLevel()) >= levelRank(raising.getWarningLevel());
                if (fresh && held) {
                    return Optional.of(new ActiveWarning(latest, before.getWarningLevel(),
                            raising.getReceivedAt()));
                }
                break;
            }
        }
        return Optional.of(new ActiveWarning(latest, null, null));
    }
}
