package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LunarTideType;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Deterministic lunar phase and tide classification service.
 *
 * <p>Computes the Moon's phase (lunation fraction) and perigee proximity from a given date
 * using fixed-period approximations. Accurate to ±1 day for new/full moon detection —
 * sufficient for spring/king tide classification in a photography context.
 *
 * <p>Key constants:
 * <ul>
 *   <li>Synodic month: 29.53059 days (new moon → new moon)</li>
 *   <li>Anomalistic month: 27.55455 days (perigee → perigee)</li>
 *   <li>New/full moon window: ±0.034 of the lunation cycle (~±1 day)</li>
 *   <li>Perigee window: ±0.5 days of the anomalistic cycle</li>
 * </ul>
 */
@Service
public class LunarPhaseService {

    /** Mean synodic month in days (new moon → new moon). */
    static final double SYNODIC_MONTH = 29.53059;

    /** Mean anomalistic month in days (perigee → perigee). */
    static final double ANOMALISTIC_MONTH = 27.55455;

    /** Known new moon date (2000-01-06). */
    static final LocalDate REFERENCE_NEW_MOON = LocalDate.of(2000, 1, 6);

    /** Known perigee date (2025-01-04). */
    static final LocalDate REFERENCE_PERIGEE = LocalDate.of(2025, 1, 4);

    /**
     * Window around new/full moon expressed as a fraction of the synodic month.
     * 0.034 × 29.53 ≈ 1.0 day either side.
     */
    static final double NEW_FULL_MOON_WINDOW = 0.034;

    /** Window around perigee in days (±0.5 days). */
    static final double PERIGEE_WINDOW_DAYS = 0.5;

    /** Number of named moon phases. */
    private static final int PHASE_COUNT = 8;

    /** Boundary for distinguishing quadrant phases (1/8 of cycle). */
    private static final double PHASE_BOUNDARY = 1.0 / PHASE_COUNT;

    /** Half of a phase boundary, used for centering the window around 0.0 and 0.5. */
    private static final double HALF_PHASE = PHASE_BOUNDARY / 2;

    private static final String[] PHASE_NAMES = {
        "New Moon", "Waxing Crescent", "First Quarter", "Waxing Gibbous",
        "Full Moon", "Waning Gibbous", "Last Quarter", "Waning Crescent"
    };

    /**
     * Returns the lunation fraction for a date (0.0 = new moon, 0.5 = full moon).
     *
     * @param date the calendar date
     * @return fraction in [0.0, 1.0)
     */
    public double getLunationFraction(LocalDate date) {
        long daysSinceRef = ChronoUnit.DAYS.between(REFERENCE_NEW_MOON, date);
        double cycles = daysSinceRef / SYNODIC_MONTH;
        double fraction = cycles - Math.floor(cycles);
        return fraction < 0 ? fraction + 1.0 : fraction;
    }

    /**
     * Returns the human-readable moon phase name.
     *
     * @param date the calendar date
     * @return one of: "New Moon", "Waxing Crescent", "First Quarter", "Waxing Gibbous",
     *         "Full Moon", "Waning Gibbous", "Last Quarter", "Waning Crescent"
     */
    public String getMoonPhase(LocalDate date) {
        double fraction = getLunationFraction(date);

        // New Moon is centred on 0.0 (wraps around from ~0.9375 to ~0.0625)
        if (fraction < HALF_PHASE || fraction >= 1.0 - HALF_PHASE) {
            return PHASE_NAMES[0];
        }

        // Remaining 7 phases span equally from HALF_PHASE to 1.0 - HALF_PHASE
        int index = (int) ((fraction - HALF_PHASE) / PHASE_BOUNDARY) + 1;
        return PHASE_NAMES[Math.min(index, PHASE_NAMES.length - 1)];
    }

    /**
     * Returns true if the date falls within ±1 day of a new moon.
     *
     * @param date the calendar date
     * @return true if near a new moon
     */
    public boolean isNewMoon(LocalDate date) {
        double fraction = getLunationFraction(date);
        return fraction < NEW_FULL_MOON_WINDOW || fraction > 1.0 - NEW_FULL_MOON_WINDOW;
    }

    /**
     * Returns true if the date falls within ±1 day of a full moon.
     *
     * @param date the calendar date
     * @return true if near a full moon
     */
    public boolean isFullMoon(LocalDate date) {
        double fraction = getLunationFraction(date);
        return Math.abs(fraction - 0.5) < NEW_FULL_MOON_WINDOW;
    }

    /**
     * Returns true if the date falls within ±1 day of a new or full moon.
     *
     * @param date the calendar date
     * @return true if near a new or full moon
     */
    public boolean isNewOrFullMoon(LocalDate date) {
        return isNewMoon(date) || isFullMoon(date);
    }

    /**
     * Returns true if the Moon is near perigee (closest approach) on the given date.
     *
     * @param date the calendar date
     * @return true if within ±0.5 days of perigee
     */
    public boolean isMoonAtPerigee(LocalDate date) {
        long daysSinceRef = ChronoUnit.DAYS.between(REFERENCE_PERIGEE, date);
        double cycles = daysSinceRef / ANOMALISTIC_MONTH;
        double fractionalCycle = cycles - Math.floor(cycles);
        if (fractionalCycle < 0) {
            fractionalCycle += 1.0;
        }
        double daysFromPerigee = fractionalCycle * ANOMALISTIC_MONTH;
        // Check proximity to nearest perigee (either side of the cycle)
        return daysFromPerigee < PERIGEE_WINDOW_DAYS
                || (ANOMALISTIC_MONTH - daysFromPerigee) < PERIGEE_WINDOW_DAYS;
    }

    /**
     * Classifies the astronomical tide type for a given date.
     *
     * @param date the calendar date
     * @return {@link LunarTideType#KING_TIDE} if spring + perigee,
     *         {@link LunarTideType#SPRING_TIDE} if new/full moon,
     *         {@link LunarTideType#REGULAR_TIDE} otherwise
     */
    public LunarTideType classifyTide(LocalDate date) {
        if (!isNewOrFullMoon(date)) {
            return LunarTideType.REGULAR_TIDE;
        }
        return isMoonAtPerigee(date)
                ? LunarTideType.KING_TIDE
                : LunarTideType.SPRING_TIDE;
    }
}
