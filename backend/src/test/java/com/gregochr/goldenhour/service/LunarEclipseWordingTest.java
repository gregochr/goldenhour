package com.gregochr.goldenhour.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.gregochr.goldenhour.service.LunarEclipseCatalog.Kind;
import com.gregochr.goldenhour.service.LunarEclipseCatalog.LunarEclipse;
import com.gregochr.goldenhour.service.LunarEclipseWording.Depth;

import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link LunarEclipseWording} — the shared magnitude-to-copy helper both
 * {@link LunarEclipseHotTopicStrategy} and {@link LunarEclipseAlmanacSource} route through, so a
 * band boundary or a capping rule need only be pinned once.
 */
class LunarEclipseWordingTest {

    /** Builds a synthetic eclipse at the given magnitude — only the magnitude and kind matter to
     * the methods under test, so every other field is an arbitrary but internally consistent span. */
    private static LunarEclipse withMagnitude(double magnitude) {
        LocalDateTime u1 = LocalDateTime.of(2030, 1, 1, 1, 0);
        LocalDateTime max = u1.plusMinutes(30);
        LocalDateTime u4 = u1.plusMinutes(60);
        Kind kind = magnitude >= 1.0 ? Kind.TOTAL : Kind.PARTIAL;
        LocalDateTime u2 = kind == Kind.TOTAL ? max.minusMinutes(5) : null;
        LocalDateTime u3 = kind == Kind.TOTAL ? max.plusMinutes(5) : null;
        return new LunarEclipse(u1.toLocalDate(), kind, magnitude,
                u1.minusMinutes(30), u1, u2, max, u3, u4, u4.plusMinutes(30), null, null);
    }

    /** Builds a synthetic eclipse whose greatest-eclipse instant (UTC) is the given value — only
     * {@code max} matters to {@link LunarEclipseWording#eventType} and
     * {@link LunarEclipseWording#toLondonLocal}, so every other field is an arbitrary but
     * internally consistent span around it. */
    private static LunarEclipse withMaxUtc(LocalDateTime maxUtc) {
        LocalDateTime u1 = maxUtc.minusMinutes(30);
        LocalDateTime u4 = maxUtc.plusMinutes(30);
        return new LunarEclipse(LunarEclipseCatalog.londonCivilDateOfMax(maxUtc), Kind.PARTIAL, 0.5,
                u1.minusMinutes(30), u1, null, maxUtc, null, u4, u4.plusMinutes(30), null, null);
    }

    @Nested
    @DisplayName("depthOf — the four bands, pinned at their exact boundaries")
    class DepthBoundaries {

        @Test
        void justBelowPartialIsSlight() {
            assertThat(LunarEclipseWording.depthOf(withMagnitude(0.39))).isEqualTo(Depth.SLIGHT);
        }

        @Test
        void exactlyPartialThresholdIsPartial() {
            assertThat(LunarEclipseWording.depthOf(withMagnitude(0.40))).isEqualTo(Depth.PARTIAL);
        }

        @Test
        void justBelowDeepIsPartial() {
            assertThat(LunarEclipseWording.depthOf(withMagnitude(0.79))).isEqualTo(Depth.PARTIAL);
        }

        @Test
        void exactlyDeepThresholdIsDeep() {
            assertThat(LunarEclipseWording.depthOf(withMagnitude(0.80))).isEqualTo(Depth.DEEP);
        }

        @Test
        void justBelowTotalIsDeep() {
            assertThat(LunarEclipseWording.depthOf(withMagnitude(0.99))).isEqualTo(Depth.DEEP);
        }

        @Test
        void exactlyTotalThresholdIsTotal() {
            assertThat(LunarEclipseWording.depthOf(withMagnitude(1.0))).isEqualTo(Depth.TOTAL);
        }

        @Test
        void wellPastTotalIsStillTotal() {
            // 2029-06-26's real catalogued magnitude — the deepest eclipse in the table.
            assertThat(LunarEclipseWording.depthOf(withMagnitude(1.8452))).isEqualTo(Depth.TOTAL);
        }
    }

    @Nested
    @DisplayName("coveragePct — capped at 100, never an impossible figure")
    class CoveragePctCapping {

        @Test
        void aPartialEclipseRoundsNormally() {
            assertThat(LunarEclipseWording.coveragePct(withMagnitude(0.0679))).isEqualTo(7);
        }

        @Test
        void aDeepPartialEclipseRoundsNormally() {
            assertThat(LunarEclipseWording.coveragePct(withMagnitude(0.93187))).isEqualTo(93);
        }

        @Test
        @DisplayName("a total eclipse's magnitude (which runs past 1.0) is capped at 100, "
                + "never printed as 125 or 185")
        void aTotalEclipseIsCappedAtOneHundred() {
            assertThat(LunarEclipseWording.coveragePct(withMagnitude(1.24785))).isEqualTo(100);
            assertThat(LunarEclipseWording.coveragePct(withMagnitude(1.8452))).isEqualTo(100);
        }
    }

    @Nested
    @DisplayName("coverageWord — 'total' for TOTAL, 'N%' otherwise")
    class CoverageWord {

        @Test
        void totalReadsTheWordTotalNeverAPercentage() {
            assertThat(LunarEclipseWording.coverageWord(withMagnitude(1.24785))).isEqualTo("total");
        }

        @Test
        void deepPartialReadsAPercentage() {
            assertThat(LunarEclipseWording.coverageWord(withMagnitude(0.93187))).isEqualTo("93%");
        }

        @Test
        void slightReadsAPercentage() {
            assertThat(LunarEclipseWording.coverageWord(withMagnitude(0.0679))).isEqualTo("7%");
        }
    }

    @Nested
    @DisplayName("shadowClause — one sentence per band, the real figure substituted where needed")
    class ShadowClause {

        @Test
        void total() {
            assertThat(LunarEclipseWording.shadowClause(withMagnitude(1.24785))).isEqualTo(
                    "Earth's shadow covers the whole moon, and the shadowed disc turns copper.");
        }

        @Test
        void deep() {
            assertThat(LunarEclipseWording.shadowClause(withMagnitude(0.93187))).isEqualTo(
                    "Earth's shadow covers all but a sliver of the full moon, and the shadowed part"
                            + " turns copper.");
        }

        @Test
        @DisplayName("PARTIAL is never catalogued today (no seeded entry falls 0.40-0.80), but the "
                + "band still has its own sentence")
        void partial() {
            assertThat(LunarEclipseWording.shadowClause(withMagnitude(0.55))).isEqualTo(
                    "Earth's shadow covers about 55% of the moon's diameter, and the shadowed part"
                            + " turns copper.");
        }

        @Test
        void slight() {
            assertThat(LunarEclipseWording.shadowClause(withMagnitude(0.0679))).isEqualTo(
                    "Earth's shadow clips 7% of the moon's edge — a darkened bite rather than a"
                            + " copper disc.");
        }
    }

    @Nested
    @DisplayName("Every catalogued entry agrees on its own band and figure")
    class CatalogueAgreement {

        @Test
        @DisplayName("depthOf/coveragePct never disagree with the catalogue's own kind/magnitude, "
                + "for every seeded entry")
        void everySeededEntryBandsCorrectly() {
            for (LunarEclipse eclipse : LunarEclipseCatalog.all()) {
                boolean isTotalByKind = eclipse.kind() == Kind.TOTAL;
                boolean isTotalByDepth = LunarEclipseWording.depthOf(eclipse) == Depth.TOTAL;
                assertThat(isTotalByDepth)
                        .as("eclipse %s: kind=%s magnitude=%s", eclipse.date(), eclipse.kind(),
                                eclipse.umbralMagnitude())
                        .isEqualTo(isTotalByKind);

                int pct = LunarEclipseWording.coveragePct(eclipse);
                assertThat(pct).as("eclipse %s", eclipse.date()).isBetween(0, 100);
            }
        }

        @Test
        void the2026Aug28EclipseIsDeep() {
            LunarEclipse eclipse = LunarEclipseCatalog.on(LocalDate.of(2026, 8, 28)).orElseThrow();
            assertThat(LunarEclipseWording.depthOf(eclipse)).isEqualTo(Depth.DEEP);
            assertThat(LunarEclipseWording.coveragePct(eclipse)).isEqualTo(93);
        }

        @Test
        void the2028Jan12EclipseIsSlight() {
            LunarEclipse eclipse = LunarEclipseCatalog.on(LocalDate.of(2028, 1, 12)).orElseThrow();
            assertThat(LunarEclipseWording.depthOf(eclipse)).isEqualTo(Depth.SLIGHT);
            assertThat(LunarEclipseWording.coveragePct(eclipse)).isEqualTo(7);
        }

        @Test
        void the2028Dec31EclipseIsTotal() {
            LunarEclipse eclipse = LunarEclipseCatalog.on(LocalDate.of(2028, 12, 31)).orElseThrow();
            assertThat(LunarEclipseWording.depthOf(eclipse)).isEqualTo(Depth.TOTAL);
            assertThat(LunarEclipseWording.coverageWord(eclipse)).isEqualTo("total");
        }
    }

    @Nested
    @DisplayName("eventType — the single rule both the topic and the slot sight route through")
    class EventType {

        @Test
        @DisplayName("just before the London-noon boundary reads SUNRISE")
        void justBeforeNoonIsSunrise() {
            // 2026-01-15 is GMT (no BST), so the London hour equals the UTC hour — the boundary
            // is exercised with no clock-conversion arithmetic in the way.
            LunarEclipse eclipse = withMaxUtc(LocalDateTime.of(2026, 1, 15, 11, 59));
            assertThat(LunarEclipseWording.eventType(eclipse)).isEqualTo("SUNRISE");
        }

        @Test
        @DisplayName("exactly the London-noon boundary reads SUNSET — the rule is hour < 12, not <=")
        void exactlyNoonIsSunset() {
            LunarEclipse eclipse = withMaxUtc(LocalDateTime.of(2026, 1, 15, 12, 0));
            assertThat(LunarEclipseWording.eventType(eclipse)).isEqualTo("SUNSET");
        }

        @Test
        @DisplayName("2026-08-28's real pre-dawn maximum (04:12:52 UTC, BST in force) reads SUNRISE "
                + "against its LONDON hour (05), not its UTC one")
        void bstPreDawnEclipseIsSunrise() {
            LunarEclipse eclipse = LunarEclipseCatalog.on(LocalDate.of(2026, 8, 28)).orElseThrow();
            assertThat(eclipse.max().getHour()).as("sanity: UTC hour alone would also read < 12")
                    .isEqualTo(4);
            assertThat(LunarEclipseWording.eventType(eclipse)).isEqualTo("SUNRISE");
        }

        @Test
        @DisplayName("2028-12-31's real afternoon maximum (16:52:01 UTC, GMT — no BST in December) "
                + "reads SUNSET")
        void gmtAfternoonEclipseIsSunset() {
            LunarEclipse eclipse = LunarEclipseCatalog.on(LocalDate.of(2028, 12, 31)).orElseThrow();
            assertThat(LunarEclipseWording.eventType(eclipse)).isEqualTo("SUNSET");
        }

        @Test
        @DisplayName("a late BST evening maximum reads SUNRISE on the LONDON clock, though its own "
                + "UTC hour (23) would read SUNSET on a UTC-only rule")
        void bstLateEveningReadsOnLondonClockNotUtc() {
            // 23:05 UTC in August (BST +1) is 00:05 the NEXT London day — hour 0, which is < 12 and
            // so reads SUNRISE. A rule that tested the UTC hour (23) directly, without converting,
            // would call this SUNSET instead — exactly the divergence eventType exists to avoid.
            LunarEclipse eclipse = withMaxUtc(LocalDateTime.of(2026, 8, 15, 23, 5));
            assertThat(LunarEclipseWording.eventType(eclipse)).isEqualTo("SUNRISE");
        }
    }

    @Nested
    @DisplayName("toLondonLocal — the one UTC-to-London conversion every eclipse time routes through")
    class ToLondonLocal {

        @Test
        @DisplayName("GMT: London local equals the UTC value exactly")
        void gmtIsUnchanged() {
            LocalDateTime utc = LocalDateTime.of(2028, 12, 31, 16, 52, 1);
            assertThat(LunarEclipseWording.toLondonLocal(utc))
                    .isEqualTo(LocalDateTime.of(2028, 12, 31, 16, 52, 1));
        }

        @Test
        @DisplayName("BST: London local is one hour ahead of the UTC value")
        void bstAddsOneHour() {
            LocalDateTime utc = LocalDateTime.of(2026, 8, 28, 4, 12, 52);
            assertThat(LunarEclipseWording.toLondonLocal(utc))
                    .isEqualTo(LocalDateTime.of(2026, 8, 28, 5, 12, 52));
        }
    }
}
