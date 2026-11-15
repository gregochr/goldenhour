package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.BriefingSlot;

/**
 * Reads a slot's cloud from either sampling point: the observer's own hourly layers, or the two
 * horizon points (113 km toward and away from the sun).
 *
 * <p>Shared by the region gloss and the triage clear-sky test so the two cannot drift on what
 * "directional" or "clear" means. It is a static helper rather than methods on
 * {@link BriefingSlot.WeatherConditions} because that record is serialised: a method there would
 * leak onto the wire.
 */
public final class SlotCloud {

    private SlotCloud() {
    }

    /**
     * The three cloud layers one slot contributes from a single sampling point.
     *
     * @param low  low cloud percentage
     * @param mid  mid-level cloud percentage
     * @param high high-level cloud percentage
     */
    public record Layers(int low, int mid, int high) {

        /**
         * True when every layer is below {@link BriefingVerdictEvaluator#CLEAR_ALL_LAYERS_MAX}.
         *
         * @return whether the sky reads as cloudless
         */
        public boolean isClear() {
            return low < BriefingVerdictEvaluator.CLEAR_ALL_LAYERS_MAX
                    && mid < BriefingVerdictEvaluator.CLEAR_ALL_LAYERS_MAX
                    && high < BriefingVerdictEvaluator.CLEAR_ALL_LAYERS_MAX;
        }

        /**
         * The colour canvas: the larger of the mid and high layers.
         *
         * @return max(mid, high)
         */
        public int canvas() {
            return Math.max(mid, high);
        }
    }

    /**
     * A slot is directional when all three sun-side horizon layers were sampled.
     *
     * @param w the slot's weather
     * @return true when sun-side low, mid and high are all present
     */
    public static boolean isDirectional(BriefingSlot.WeatherConditions w) {
        return w.sunSideLowCloudPercent() != null && w.sunSideMidCloudPercent() != null
                && w.sunSideHighCloudPercent() != null;
    }

    /**
     * The observer point's own layers.
     *
     * @param w the slot's weather
     * @return observer low, mid and high
     */
    public static Layers observer(BriefingSlot.WeatherConditions w) {
        return new Layers(w.lowCloudPercent(), w.midCloudPercent(), w.highCloudPercent());
    }

    /**
     * Horizon layers: low cloud on the sun side is the blocker; the larger of the two sides' mid and
     * high cloud is the canvas, the same reading the full evaluation scores by.
     *
     * @param w a directional slot's weather (see {@link #isDirectional})
     * @return the effective horizon layers
     */
    public static Layers horizon(BriefingSlot.WeatherConditions w) {
        int antiMid = w.antiSunSideMidCloudPercent() == null ? 0 : w.antiSunSideMidCloudPercent();
        int antiHigh = w.antiSunSideHighCloudPercent() == null ? 0 : w.antiSunSideHighCloudPercent();
        return new Layers(w.sunSideLowCloudPercent(),
                Math.max(w.sunSideMidCloudPercent(), antiMid),
                Math.max(w.sunSideHighCloudPercent(), antiHigh));
    }
}
