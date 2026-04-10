package com.gregochr.goldenhour.service.aurora;

import com.gregochr.goldenhour.model.MoonTransitionData;
import com.gregochr.goldenhour.model.MoonTransitionData.WindowQuality;
import com.gregochr.goldenhour.model.TonightWindow;
import com.gregochr.solarutils.LunarCalculator;
import com.gregochr.solarutils.LunarPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Computes moon transition data across tonight's dark viewing window.
 *
 * <p>Samples the moon position hourly from dusk to dawn (inclusive) and detects
 * rise/set transitions within the window. This replaces the old single-midpoint
 * snapshot, which missed moonrise/moonset events and applied blanket penalties.
 *
 * <p>Pure static utility — no Spring bean, no state.
 */
public final class MoonTransitionCalculator {

    private static final Logger LOG = LoggerFactory.getLogger(MoonTransitionCalculator.class);

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Europe/London"));

    private MoonTransitionCalculator() {
        // static utility
    }

    /**
     * Calculates moon transition data across the given dark window.
     *
     * <p>If {@code window} is {@code null} (real-time path), falls back to a single-point
     * calculation at the current time.
     *
     * @param calc   lunar position calculator
     * @param window tonight's dark period (dusk→dawn), or {@code null} for real-time
     * @param lat    reference latitude (e.g. Durham)
     * @param lon    reference longitude
     * @return transition data, or {@code null} on failure
     */
    public static MoonTransitionData calculate(LunarCalculator calc, TonightWindow window,
            double lat, double lon) {
        try {
            if (window == null) {
                return singlePointFallback(calc, lat, lon);
            }
            return sampleWindow(calc, window, lat, lon);
        } catch (Exception e) {
            LOG.debug("Moon transition calculation failed: {}", e.getMessage());
            return null;
        }
    }

    private static MoonTransitionData singlePointFallback(LunarCalculator calc,
            double lat, double lon) {
        ZonedDateTime now = ZonedDateTime.now(java.time.ZoneOffset.UTC);
        LunarPosition pos = calc.calculate(now, lat, lon);
        WindowQuality quality = pos.isAboveHorizon()
                ? WindowQuality.MOONLIT_ALL_WINDOW
                : WindowQuality.DARK_ALL_WINDOW;
        return new MoonTransitionData(
                pos.phase(),
                pos.illuminationPercent(),
                quality,
                null,
                null,
                pos.isAboveHorizon(),
                pos.isAboveHorizon());
    }

    private static MoonTransitionData sampleWindow(LunarCalculator calc, TonightWindow window,
            double lat, double lon) {
        ZonedDateTime current = window.dusk();
        ZonedDateTime dawn = window.dawn();

        LunarPosition firstPos = calc.calculate(current, lat, lon);
        boolean moonUpAtStart = firstPos.isAboveHorizon();
        boolean previousUp = moonUpAtStart;

        String riseTime = null;
        String setTime = null;
        boolean lastUp = moonUpAtStart;

        current = current.plusHours(1);
        while (!current.isAfter(dawn)) {
            LunarPosition pos = calc.calculate(current, lat, lon);
            boolean nowUp = pos.isAboveHorizon();

            if (!previousUp && nowUp && riseTime == null) {
                riseTime = TIME_FMT.format(current);
            }
            if (previousUp && !nowUp && setTime == null) {
                setTime = TIME_FMT.format(current);
            }

            lastUp = nowUp;
            previousUp = nowUp;
            current = current.plusHours(1);
        }

        boolean moonUpAtEnd = lastUp;
        WindowQuality quality = deriveQuality(moonUpAtStart, moonUpAtEnd);

        return new MoonTransitionData(
                firstPos.phase(),
                firstPos.illuminationPercent(),
                quality,
                riseTime,
                setTime,
                moonUpAtStart,
                moonUpAtEnd);
    }

    private static WindowQuality deriveQuality(boolean upAtStart, boolean upAtEnd) {
        if (!upAtStart && !upAtEnd) {
            return WindowQuality.DARK_ALL_WINDOW;
        }
        if (!upAtStart && upAtEnd) {
            return WindowQuality.DARK_THEN_MOONLIT;
        }
        if (upAtStart && !upAtEnd) {
            return WindowQuality.MOONLIT_THEN_DARK;
        }
        return WindowQuality.MOONLIT_ALL_WINDOW;
    }
}
