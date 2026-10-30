package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.InversionProfile;
import com.gregochr.goldenhour.model.InversionV2Result;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Builders for the v2 inversion tests: a valley hour with five pressure levels, a profile, and the viewpoint
 * {@link AtmosphericData} that carries it.
 */
final class InversionV2TestSupport {

    /** A sunrise whose slot is {@link #SLOT}. */
    static final LocalDateTime EVENT = LocalDateTime.of(2025, 10, 15, 6, 40);

    /** The sunrise slot hour (the earliest hour at or after {@link #EVENT}). */
    static final LocalDateTime SLOT = LocalDateTime.of(2025, 10, 15, 7, 0);

    /** Geopotential heights of 975, 950, 925, 900 and 850 hPa used unless a test overrides them. */
    static final Double[] HEIGHTS = {450.0, 680.0, 900.0, 1130.0, 1600.0};

    private InversionV2TestSupport() {
    }

    /**
     * A fluent valley hour. Defaults: a saturated calm hour with the sea in place, so a test states only what
     * it varies.
     */
    static final class H {
        private final LocalDateTime time;
        private Double t2 = 8.0;
        private Double dew = 7.8;
        private Integer low = 90;
        private Integer mid;
        private Integer high;
        private Double vis = 10000.0;
        private Double wind = 1.0;
        private Double[] temps = {null, null, null, null, null};
        private Double[] heights = HEIGHTS;
        private Double[] winds = {null, null, null, null, null};

        H(LocalDateTime time) {
            this.time = time;
        }

        static H at(int hourOfDay) {
            return new H(LocalDateTime.of(2025, 10, 15, hourOfDay, 0));
        }

        /** An hour at an explicit time, for the night side and for days other than {@link #EVENT}'s. */
        static H on(LocalDateTime time) {
            return new H(time);
        }

        LocalDateTime time() {
            return time;
        }

        H mid(Integer v) {
            this.mid = v;
            return this;
        }

        H high(Integer v) {
            this.high = v;
            return this;
        }

        H t2(Double v) {
            this.t2 = v;
            return this;
        }

        H dew(Double v) {
            this.dew = v;
            return this;
        }

        H low(Integer v) {
            this.low = v;
            return this;
        }

        H vis(Double v) {
            this.vis = v;
            return this;
        }

        H wind(Double v) {
            this.wind = v;
            return this;
        }

        /** The 975, 950, 925, 900 and 850 hPa temperatures; null for a missing level. */
        H temps(Double... v) {
            this.temps = v;
            return this;
        }

        /** The 975, 950, 925, 900 and 850 hPa wind speeds in m/s; null for a missing level. */
        H winds(Double... v) {
            this.winds = v;
            return this;
        }

        H heights(Double... v) {
            this.heights = v;
            return this;
        }

        InversionProfile.Hour build() {
            int[] hpa = {975, 950, 925, 900, 850};
            List<InversionProfile.Level> levels = new ArrayList<>();
            for (int i = 0; i < hpa.length; i++) {
                levels.add(new InversionProfile.Level(hpa[i], temps[i], heights[i], winds[i]));
            }
            return new InversionProfile.Hour(time, t2, dew, low, mid, high, vis, wind, List.copyOf(levels));
        }
    }

    /** A sliced profile with {@link #SLOT} as the slot. */
    static InversionProfile profile(int valleyFloor, H... hours) {
        return new InversionProfile(54.5, -3.1, (double) valleyFloor, valleyFloor, SLOT,
                java.util.Arrays.stream(hours).map(H::build).toList());
    }

    /** A sliced profile with {@link #SLOT} as the slot and a night side. */
    static InversionProfile profile(int valleyFloor, List<InversionProfile.Hour> nightHours, H... hours) {
        return new InversionProfile(54.5, -3.1, (double) valleyFloor, valleyFloor, SLOT,
                java.util.Arrays.stream(hours).map(H::build).toList(), nightHours);
    }

    /** A sunrise {@link AtmosphericData} for the viewpoint cell, carrying {@code profile}. */
    static AtmosphericData data(InversionProfile profile, int viewpointLowCloud, int viewpointVisibility) {
        AtmosphericData base = TestAtmosphericData.builder()
                .locationName("Test Fell")
                .solarEventTime(EVENT)
                .targetType(TargetType.SUNRISE)
                .lowCloud(viewpointLowCloud)
                .visibility(viewpointVisibility)
                .temperature(3.0)
                .dewPoint(2.0)
                .build();
        return profile == null ? base : base.withInversionV2(InversionV2Result.ofProfile(profile));
    }

    /** A sunrise {@link AtmosphericData} whose viewpoint cell is clear and visible. */
    static AtmosphericData data(InversionProfile profile) {
        return data(profile, 0, 20000);
    }
}
