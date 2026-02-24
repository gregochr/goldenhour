package com.gregochr.goldenhour.entity;

/**
 * Classifies a tide extreme event as either a high tide peak or a low tide trough.
 *
 * <p>Used in the {@code tide_extreme} table to distinguish WorldTides API extremes.
 * Separate from {@link TideState}, which also includes {@code MID} for the in-between state.
 */
public enum TideExtremeType {

    /** The tide is at or near its highest point (a peak in the tidal cycle). */
    HIGH,

    /** The tide is at or near its lowest point (a trough in the tidal cycle). */
    LOW
}
