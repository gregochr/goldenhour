package com.gregochr.goldenhour.service.aurora;

import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.AuroraForecastScore;
import com.gregochr.solarutils.LunarCalculator;
import com.gregochr.solarutils.LunarPhase;
import com.gregochr.solarutils.LunarPosition;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/**
 * Scores aurora photography conditions for a set of candidate locations.
 *
 * <p>Produces 1–5 star ratings using four factors:
 * <ol>
 *   <li><strong>Alert level</strong> — base score (AMBER = 3, RED = 4)</li>
 *   <li><strong>Cloud cover</strong> — northward transect average; clear skies add up to +1,
 *       overcast removes up to −1.5</li>
 *   <li><strong>Moon</strong> — uses {@link LunarPosition#auroraPenalty()} which accounts for
 *       altitude, illumination, and whether the moon is in the northern sky</li>
 *   <li><strong>Bortle class</strong> — dark-sky quality at the location</li>
 * </ol>
 *
 * <p>Summaries and detail text are template-based — no Claude API call is made.
 * The factors are discrete and well-defined, so templates are more reliable and
 * significantly lower latency for a time-sensitive evening notification.
 */
@Component
public class AuroraScorer {

    private static final ZoneId UK_ZONE = ZoneId.of("Europe/London");

    /** Maximum star rating (inclusive). */
    private static final int MAX_STARS = 5;

    /** Minimum star rating (inclusive). */
    private static final int MIN_STARS = 1;

    private final LunarCalculator lunarCalculator;

    /**
     * Constructs the scorer with the injected {@link LunarCalculator}.
     *
     * @param lunarCalculator solar-utils lunar calculator
     */
    public AuroraScorer(LunarCalculator lunarCalculator) {
        this.lunarCalculator = lunarCalculator;
    }

    /**
     * Scores all candidate locations and returns the results as an immutable list.
     *
     * @param level         the alert level that triggered this scoring pass
     * @param candidates    eligible locations (already filtered by Bortle threshold)
     * @param transectCloud map from location name to average northward cloud cover (0–100)
     * @return list of scored locations, one per candidate
     */
    public List<AuroraForecastScore> score(AlertLevel level,
            List<LocationEntity> candidates,
            Map<String, Integer> transectCloud) {
        ZonedDateTime now = ZonedDateTime.now(UK_ZONE);
        return candidates.stream()
                .map(loc -> scoreLocation(loc, level, transectCloud, now))
                .toList();
    }

    /**
     * Scores a single location for aurora photography conditions.
     *
     * @param location      the location to score
     * @param level         the current alert level
     * @param transectCloud map from location name to cloud percent
     * @param now           the current time in UK timezone
     * @return the computed {@link AuroraForecastScore}
     */
    private AuroraForecastScore scoreLocation(LocationEntity location, AlertLevel level,
            Map<String, Integer> transectCloud, ZonedDateTime now) {
        LunarPosition moon = lunarCalculator.calculate(now, location.getLat(), location.getLon());
        int cloud = transectCloud.getOrDefault(location.getName(), 50);

        double base = (level == AlertLevel.RED) ? 4.0 : 3.0;
        double cloudMod = cloudModifier(cloud);
        double moonMod = moonModifier(moon);
        double bortleMod = bortleModifier(location.getBortleClass());

        double rawScore = base + cloudMod + moonMod + bortleMod;
        int stars = (int) Math.round(Math.min(MAX_STARS, Math.max(MIN_STARS, rawScore)));

        String alertDetail = alertDetail(level);
        String cloudDetail = cloudDetail(cloud);
        String moonDetail = moonDetail(moon);
        String bortleDetail = bortleDetail(location.getBortleClass());

        String detail = alertDetail + "\n" + cloudDetail + "\n" + moonDetail + "\n" + bortleDetail;
        String summary = buildSummary(stars, level, cloud, moon);

        return new AuroraForecastScore(location, stars, level, cloud, summary, detail);
    }

    /**
     * Cloud cover modifier based on the northern transect average.
     *
     * @param cloudPercent cloud cover 0–100
     * @return score modifier in range −1.5 to +1.0
     */
    double cloudModifier(int cloudPercent) {
        if (cloudPercent < 20) {
            return 1.0;
        }
        if (cloudPercent < 40) {
            return 0.5;
        }
        if (cloudPercent < 60) {
            return 0.0;
        }
        if (cloudPercent < 80) {
            return -1.0;
        }
        return -1.5;
    }

    /**
     * Moon penalty modifier using {@link LunarPosition#auroraPenalty()}.
     *
     * @param moon the computed lunar position
     * @return score modifier in range −1.0 to +0.5
     */
    double moonModifier(LunarPosition moon) {
        double penalty = moon.auroraPenalty();
        if (penalty == 0.0) {
            return 0.5;   // below horizon
        }
        if (penalty < 0.15) {
            return 0.25;  // negligible
        }
        if (penalty < 0.35) {
            return 0.0;   // moderate
        }
        if (penalty < 0.65) {
            return -0.5;  // significant
        }
        return -1.0;      // severe
    }

    /**
     * Bortle class modifier for dark-sky quality.
     *
     * @param bortleClass Bortle class 1–9, or {@code null} if unknown
     * @return score modifier in range −0.5 to +0.5
     */
    double bortleModifier(Integer bortleClass) {
        if (bortleClass == null) {
            return 0.0;
        }
        if (bortleClass <= 2) {
            return 0.5;
        }
        if (bortleClass <= 4) {
            return 0.0;
        }
        return -0.5;
    }

    /**
     * Builds the one-line push-notification style summary.
     */
    private String buildSummary(int stars, AlertLevel level, int cloud, LunarPosition moon) {
        String starStr = "★".repeat(stars) + "☆".repeat(MAX_STARS - stars);
        StringBuilder sb = new StringBuilder(starStr).append(" ");

        if (stars >= 4) {
            sb.append("Conditions look great — ");
        } else if (stars == 3) {
            sb.append("Moderate conditions — ");
        } else {
            sb.append("Challenging conditions — ");
        }

        sb.append(level == AlertLevel.RED ? "Red alert" : "Amber alert");
        sb.append(", ");
        sb.append(cloud < 40 ? "clear skies" : cloud < 70 ? "partly cloudy" : "cloudy");
        sb.append(moon.isAboveHorizon()
                ? String.format(", moon %d%% illuminated",
                        Math.round(moon.illuminationPercent()))
                : ", moon below horizon");
        return sb.toString();
    }

    private String alertDetail(AlertLevel level) {
        String icon = (level == AlertLevel.RED) ? "✓" : "–";
        return icon + " Geomagnetic activity: " + level.description();
    }

    private String cloudDetail(int cloud) {
        String icon = cloud < 40 ? "✓" : cloud < 70 ? "–" : "✗";
        String description;
        if (cloud < 20) {
            description = "Clear skies to the north — excellent visibility";
        } else if (cloud < 40) {
            description = "Mostly clear to the north — good visibility";
        } else if (cloud < 60) {
            description = "Partly cloudy to the north — moderate visibility";
        } else if (cloud < 80) {
            description = "Considerable cloud to the north — poor visibility";
        } else {
            description = "Overcast to the north — aurora likely obscured";
        }
        return icon + " Cloud cover: " + description;
    }

    private String moonDetail(LunarPosition moon) {
        String phaseName = displayName(moon.phase());
        int pct = (int) Math.round(moon.illuminationPercent());

        if (!moon.isAboveHorizon()) {
            return "✓ Moonlight: Moon is below the horizon — no interference. "
                    + phaseName + ", " + pct + "% illuminated";
        }

        double penalty = moon.auroraPenalty();
        String icon = penalty < 0.35 ? "–" : "✗";
        String dirNote = moon.isInNorthernSky() ? " in the northern sky — competes with the aurora" : "";
        String severityNote;
        if (penalty < 0.15) {
            severityNote = "low impact";
        } else if (penalty < 0.35) {
            severityNote = "moderate impact";
        } else if (penalty < 0.65) {
            severityNote = "significant interference";
        } else {
            severityNote = "severe light pollution";
        }
        return icon + " Moonlight: Moon is above the horizon" + dirNote + " — " + severityNote
                + ". " + phaseName + ", " + pct + "% illuminated";
    }

    private String bortleDetail(Integer bortleClass) {
        if (bortleClass == null) {
            return "– Dark skies: No dark sky data available for this location";
        }
        String icon = bortleClass <= 4 ? "✓" : "–";
        String description;
        if (bortleClass <= 2) {
            description = "Excellent dark sky site — faint aurora detail and colour will show well";
        } else if (bortleClass <= 4) {
            description = "Moderate light pollution — bright aurora will be visible, faint detail may be lost";
        } else {
            description = "Light-polluted site — only the brightest aurora displays will be visible";
        }
        return icon + " Dark skies: Bortle " + bortleClass + " — " + description;
    }

    /**
     * Returns the display name for a {@link LunarPhase}.
     *
     * @param phase the lunar phase
     * @return human-readable phase name
     */
    private String displayName(LunarPhase phase) {
        return switch (phase) {
            case NEW_MOON       -> "New Moon";
            case WAXING_CRESCENT -> "Waxing Crescent";
            case FIRST_QUARTER  -> "First Quarter";
            case WAXING_GIBBOUS -> "Waxing Gibbous";
            case FULL_MOON      -> "Full Moon";
            case WANING_GIBBOUS -> "Waning Gibbous";
            case LAST_QUARTER   -> "Last Quarter";
            case WANING_CRESCENT -> "Waning Crescent";
        };
    }
}
