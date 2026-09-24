package com.gregochr.goldenhour.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.gregochr.goldenhour.service.LunarEclipseCatalog.Kind;
import com.gregochr.goldenhour.service.LunarEclipseCatalog.LunarEclipse;
import com.gregochr.solarutils.LunarCalculator;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The seeded lunar eclipse table.
 *
 * <p><b>The point of this class is the transcription check</b> — the same discipline
 * {@code EclipseCatalogTest} applies to the solar table. Every contact instant here was copied by
 * hand from NASA's decade tables and eclipsewise.com, and a mistyped digit produces a plausible
 * eclipse at the wrong time. The full-moon illumination check catches a wrong date (illumination
 * falls off fast either side of full moon); the ordering and kind/magnitude checks catch a swapped
 * or mistyped contact even though {@link LunarEclipse}'s compact constructor already enforces them
 * at class-load time, because a constructor failure is an opaque {@code ExceptionInInitializerError}
 * and these give a reader a first-class, per-entry assertion instead.
 */
class LunarEclipseCatalogTest {

    /** UK-centre reference point for the illumination check — no eclipse in the table needs a more
     * precise point, since illumination barely varies with location. */
    private static final double UK_LAT = 54.5;
    private static final double UK_LON = -2.5;

    private static final LunarCalculator LUNAR_CALCULATOR = new LunarCalculator();

    private static double illuminationAtMax(LunarEclipse eclipse) {
        return LUNAR_CALCULATOR
                .calculate(eclipse.max().atZone(ZoneOffset.UTC), UK_LAT, UK_LON)
                .illuminationPercent();
    }

    @Nested
    @DisplayName("Every seeded entry, verified against the ephemeris")
    class Transcription {

        @Test
        @DisplayName("every entry's maximum is (near enough) a full moon — catches a wrong date")
        void everyMaxIsFullMoon() {
            for (LunarEclipse eclipse : LunarEclipseCatalog.all()) {
                assertThat(illuminationAtMax(eclipse))
                        .as("illumination at %s's maximum (%s)", eclipse.date(), eclipse.max())
                        .isGreaterThanOrEqualTo(99.0);
            }
        }

        @Test
        @DisplayName("contacts are in strict order for every entry")
        void contactsAreOrdered() {
            for (LunarEclipse eclipse : LunarEclipseCatalog.all()) {
                assertThat(eclipse.p1()).as("%s p1<u1", eclipse.date()).isBefore(eclipse.u1());
                assertThat(eclipse.u1()).as("%s u1<max", eclipse.date()).isBefore(eclipse.max());
                assertThat(eclipse.max()).as("%s max<u4", eclipse.date()).isBefore(eclipse.u4());
                assertThat(eclipse.u4()).as("%s u4<p4", eclipse.date()).isBefore(eclipse.p4());

                if (eclipse.kind() == Kind.TOTAL) {
                    assertThat(eclipse.u1()).as("%s u1<u2", eclipse.date()).isBefore(eclipse.u2());
                    assertThat(eclipse.u2()).as("%s u2<max", eclipse.date()).isBefore(eclipse.max());
                    assertThat(eclipse.max()).as("%s max<u3", eclipse.date()).isBefore(eclipse.u3());
                    assertThat(eclipse.u3()).as("%s u3<u4", eclipse.date()).isBefore(eclipse.u4());
                }
            }
        }

        @Test
        @DisplayName("kind agrees with magnitude, and u2/u3 presence agrees with kind, for every entry")
        void kindAgreesWithMagnitudeAndContacts() {
            for (LunarEclipse eclipse : LunarEclipseCatalog.all()) {
                boolean isTotal = eclipse.kind() == Kind.TOTAL;
                assertThat(isTotal)
                        .as("%s: kind %s vs magnitude %s", eclipse.date(), eclipse.kind(), eclipse.umbralMagnitude())
                        .isEqualTo(eclipse.umbralMagnitude() >= 1.0);
                assertThat(eclipse.u2() != null)
                        .as("%s: u2 present vs kind %s", eclipse.date(), eclipse.kind())
                        .isEqualTo(isTotal);
                assertThat(eclipse.u3() != null)
                        .as("%s: u3 present vs kind %s", eclipse.date(), eclipse.kind())
                        .isEqualTo(isTotal);
            }
        }

        @Test
        @DisplayName("2026-08-28: the worked example's magnitude and kind")
        void theWorkedExample() {
            LunarEclipse eclipse = LunarEclipseCatalog.on(LocalDate.of(2026, 8, 28)).orElseThrow();
            assertThat(eclipse.kind()).isEqualTo(Kind.PARTIAL);
            assertThat(eclipse.umbralMagnitude()).isCloseTo(0.93187, within(0.0001));
            assertThat(eclipse.u1()).isEqualTo(LocalDateTime.of(2026, 8, 28, 2, 33, 21));
            assertThat(eclipse.max()).isEqualTo(LocalDateTime.of(2026, 8, 28, 4, 12, 52));
            assertThat(eclipse.u4()).isEqualTo(LocalDateTime.of(2026, 8, 28, 5, 52, 9));
        }
    }

    @Nested
    @DisplayName("Each entry's date is derived from its maximum, not transcribed separately")
    class DateDerivation {

        // Note: there is deliberately no test here asserting `eclipse.date()` against
        // `LunarEclipseCatalog.londonCivilDateOfMax(eclipse.max())` for every seeded entry — that
        // would be tautological, since `raw()` computes `date` with exactly that function on exactly
        // that input, so the assertion could never fail regardless of whether the derivation itself
        // is correct. The `Table.isInStrictDateOrder()` test below already pins every entry's `date`
        // against a hand-written, independent list of `LocalDate` literals, which is the real check;
        // this nested class instead exercises `londonCivilDateOfMax()` directly against synthetic
        // instants chosen specifically to cross (and not cross) the BST/GMT date boundary.

        @Test
        @DisplayName("a BST instant after 23:00 UTC lands on the next London civil date")
        void midnightCrossingInBst() {
            // 2026-06-01 23:30 UTC is 2026-06-02 00:30 BST — the exact shape a mistranscribed
            // eclipse straddling this boundary would get wrong. None of the seven seeded entries
            // happens to cross London midnight at its own maximum (verified by inspection of the
            // transcribed contact times), so this synthetic instant is what exercises the rule.
            LocalDateTime lateUtc = LocalDateTime.of(2026, 6, 1, 23, 30);
            assertThat(LunarEclipseCatalog.londonCivilDateOfMax(lateUtc))
                    .isEqualTo(LocalDate.of(2026, 6, 2));
        }

        @Test
        @DisplayName("a GMT instant near midnight stays on the same UTC calendar date")
        void noCrossingInGmt() {
            // Outside BST the two clocks agree, so the same lateness does not cross a date line.
            LocalDateTime lateUtc = LocalDateTime.of(2026, 1, 1, 23, 30);
            assertThat(LunarEclipseCatalog.londonCivilDateOfMax(lateUtc))
                    .isEqualTo(LocalDate.of(2026, 1, 1));
        }
    }

    @Nested
    @DisplayName("The compact constructor's defensive branches")
    class DefensiveValidation {

        private static final LocalDateTime P1 = LocalDateTime.of(2030, 1, 1, 0, 0);
        private static final LocalDateTime U1 = LocalDateTime.of(2030, 1, 1, 1, 0);
        private static final LocalDateTime MAX = LocalDateTime.of(2030, 1, 1, 2, 0);
        private static final LocalDateTime U4 = LocalDateTime.of(2030, 1, 1, 3, 0);
        private static final LocalDateTime P4 = LocalDateTime.of(2030, 1, 1, 4, 0);

        @Test
        @DisplayName("TOTAL with magnitude below 1.0 is rejected")
        void totalRequiresMagnitudeAtLeastOne() {
            assertThatThrownBy(() -> new LunarEclipse(P1.toLocalDate(), Kind.TOTAL, 0.99, P1, U1,
                    LocalDateTime.of(2030, 1, 1, 1, 30), MAX, LocalDateTime.of(2030, 1, 1, 2, 30), U4, P4,
                    null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("disagrees with umbralMagnitude");
        }

        @Test
        @DisplayName("PARTIAL with magnitude at or above 1.0 is rejected")
        void partialRejectsMagnitudeAtLeastOne() {
            assertThatThrownBy(() -> new LunarEclipse(P1.toLocalDate(), Kind.PARTIAL, 1.0, P1, U1, null,
                    MAX, null, U4, P4, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("disagrees with umbralMagnitude");
        }

        @Test
        @DisplayName("TOTAL with a null u2 is rejected")
        void totalRequiresU2() {
            assertThatThrownBy(() -> new LunarEclipse(P1.toLocalDate(), Kind.TOTAL, 1.2, P1, U1, null,
                    MAX, LocalDateTime.of(2030, 1, 1, 2, 30), U4, P4, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("u2/u3 non-null");
        }

        @Test
        @DisplayName("TOTAL with a null u3 is rejected")
        void totalRequiresU3() {
            assertThatThrownBy(() -> new LunarEclipse(P1.toLocalDate(), Kind.TOTAL, 1.2, P1, U1,
                    LocalDateTime.of(2030, 1, 1, 1, 30), MAX, null, U4, P4, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("u2/u3 non-null");
        }

        @Test
        @DisplayName("PARTIAL with a non-null u2 is rejected")
        void partialRejectsU2() {
            assertThatThrownBy(() -> new LunarEclipse(P1.toLocalDate(), Kind.PARTIAL, 0.5, P1, U1,
                    LocalDateTime.of(2030, 1, 1, 1, 30), MAX, null, U4, P4, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("u2/u3 non-null");
        }

        @Test
        @DisplayName("PARTIAL with a non-null u3 (and null u2) is rejected")
        void partialRejectsU3() {
            // u2 is null here, so this is the one case that isolates the SECOND clause
            // (u3 != null) as the one that trips the throw, rather than the first.
            assertThatThrownBy(() -> new LunarEclipse(P1.toLocalDate(), Kind.PARTIAL, 0.5, P1, U1,
                    null, MAX, LocalDateTime.of(2030, 1, 1, 2, 30), U4, P4, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("u2/u3 non-null");
        }

        @Test
        @DisplayName("a nextComparable date with no matching kind is rejected")
        void nextComparablePairingIsRequired() {
            assertThatThrownBy(() -> new LunarEclipse(P1.toLocalDate(), Kind.PARTIAL, 0.5, P1, U1, null,
                    MAX, null, U4, P4, LocalDate.of(2031, 1, 1), null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("both null or both set");
        }

        @Test
        @DisplayName("p1 not before u1 is rejected")
        void p1MustPrecedeU1() {
            assertThatThrownBy(() -> new LunarEclipse(P1.toLocalDate(), Kind.PARTIAL, 0.5, U1, U1, null,
                    MAX, null, U4, P4, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("p1 must be before u1");
        }

        @Test
        @DisplayName("out-of-order partial contacts: u1 not before max")
        void partialContactsMustBeOrdered() {
            assertThatThrownBy(() -> new LunarEclipse(P1.toLocalDate(), Kind.PARTIAL, 0.5, P1, MAX, null,
                    U1, null, U4, P4, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("contacts out of order");
        }

        @Test
        @DisplayName("out-of-order partial contacts: max not before u4")
        void partialContactsRejectsMaxNotBeforeU4() {
            // u1 < max holds (01:00 < 02:00); the second clause (max < u4) is the one that fails,
            // since u4 (01:30) falls before max.
            assertThatThrownBy(() -> new LunarEclipse(P1.toLocalDate(), Kind.PARTIAL, 0.5, P1, U1, null,
                    MAX, null, LocalDateTime.of(2030, 1, 1, 1, 30), P4, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("contacts out of order");
        }

        @Test
        @DisplayName("out-of-order total contacts: u2 not before u1")
        void totalContactsRejectsU2NotAfterU1() {
            assertThatThrownBy(() -> new LunarEclipse(P1.toLocalDate(), Kind.TOTAL, 1.2, P1, U1,
                    LocalDateTime.of(2030, 1, 1, 0, 30), MAX, LocalDateTime.of(2030, 1, 1, 2, 30), U4, P4,
                    null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("contacts out of order");
        }

        @Test
        @DisplayName("out-of-order total contacts: max not before u3")
        void totalContactsRejectsMaxNotBeforeU3() {
            // u1 < u2 < max all hold; u3 (01:45) falls before max (02:00), so this clause is the
            // one that fails.
            assertThatThrownBy(() -> new LunarEclipse(P1.toLocalDate(), Kind.TOTAL, 1.2, P1, U1,
                    LocalDateTime.of(2030, 1, 1, 1, 30), MAX, LocalDateTime.of(2030, 1, 1, 1, 45), U4, P4,
                    null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("contacts out of order");
        }

        @Test
        @DisplayName("out-of-order total contacts: u3 not before u4")
        void totalContactsRejectsU3NotBeforeU4() {
            // u1 < u2 < max < u3 all hold; u4 (02:15) falls before u3 (02:30), so this clause is
            // the one that fails.
            assertThatThrownBy(() -> new LunarEclipse(P1.toLocalDate(), Kind.TOTAL, 1.2, P1, U1,
                    LocalDateTime.of(2030, 1, 1, 1, 30), MAX, LocalDateTime.of(2030, 1, 1, 2, 30),
                    LocalDateTime.of(2030, 1, 1, 2, 15), P4, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("contacts out of order");
        }

        @Test
        @DisplayName("out-of-order total contacts: u2 not before max")
        void totalContactsRejectsU2NotBeforeMax() {
            // u1 < u2 (01:00 < 02:15) holds; u2 (02:15) falls after max (02:00), so this clause is
            // the one that fails.
            assertThatThrownBy(() -> new LunarEclipse(P1.toLocalDate(), Kind.TOTAL, 1.2, P1, U1,
                    LocalDateTime.of(2030, 1, 1, 2, 15), MAX, LocalDateTime.of(2030, 1, 1, 1, 45), U4, P4,
                    null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("contacts out of order");
        }

        @Test
        @DisplayName("u4 not before p4 is rejected")
        void u4MustPrecedeP4() {
            assertThatThrownBy(() -> new LunarEclipse(P1.toLocalDate(), Kind.PARTIAL, 0.5, P1, U1, null,
                    MAX, null, P4, U4, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("u4 must be before p4");
        }
    }

    @Nested
    @DisplayName("The table itself")
    class Table {

        @Test
        @DisplayName("holds every UK-visible umbral eclipse March 2025-2030, in date order")
        void isInStrictDateOrder() {
            List<LocalDate> dates = LunarEclipseCatalog.all().stream().map(LunarEclipse::date).toList();

            assertThat(dates).containsExactly(
                    LocalDate.of(2025, 3, 14),
                    LocalDate.of(2025, 9, 7),
                    LocalDate.of(2026, 8, 28),
                    LocalDate.of(2028, 1, 12),
                    LocalDate.of(2028, 12, 31),
                    LocalDate.of(2029, 6, 26),
                    LocalDate.of(2029, 12, 20));
            assertThat(dates).isSorted();
        }

        @Test
        @DisplayName("excludes eclipses whose umbral phase never rises above the British Isles horizon")
        void excludesEclipsesBelowTheUkHorizon() {
            // 2026-03-03 (not visible from Europe at all), 2028-07-06 and 2030-06-15 (Europe-visible
            // in general, but the umbral phase ends before moonrise anywhere in Britain) were checked
            // and found to have the Moon below the horizon at U1, greatest eclipse and U4 across six
            // reference points spanning the British Isles — see the class javadoc.
            List<LocalDate> dates = LunarEclipseCatalog.all().stream().map(LunarEclipse::date).toList();
            assertThat(dates).doesNotContain(
                    LocalDate.of(2026, 3, 3),
                    LocalDate.of(2028, 7, 6),
                    LocalDate.of(2030, 6, 15));
        }

        @Test
        @DisplayName("excludes every penumbral eclipse in range")
        void excludesPenumbralEclipses() {
            List<LocalDate> dates = LunarEclipseCatalog.all().stream().map(LunarEclipse::date).toList();
            assertThat(dates).doesNotContain(
                    LocalDate.of(2027, 2, 20),
                    LocalDate.of(2027, 7, 18),
                    LocalDate.of(2027, 8, 17),
                    LocalDate.of(2030, 12, 9));
        }

        @Test
        @DisplayName("nextComparable chains to each entry's successor, and the last entry has none")
        void nextComparableChainsForward() {
            List<LunarEclipse> all = LunarEclipseCatalog.all();
            for (int i = 0; i < all.size() - 1; i++) {
                LunarEclipse entry = all.get(i);
                LunarEclipse next = all.get(i + 1);
                assertThat(entry.nextComparable()).as("%s.nextComparable", entry.date()).isEqualTo(next.date());
                assertThat(entry.nextComparableKind())
                        .as("%s.nextComparableKind", entry.date())
                        .isEqualTo(next.kind().name().toLowerCase(java.util.Locale.ROOT));
            }
            LunarEclipse last = all.get(all.size() - 1);
            assertThat(last.nextComparable()).isNull();
            assertThat(last.nextComparableKind()).isNull();
        }

        @Test
        @DisplayName("2028-01-12's nextComparable is the total eclipse of 2028-12-31 (a Sunday)")
        void nextComparableWorkedExample() {
            // Cross-checks the design bundle's own worked rarity-note example
            // ("next from the UK: a total eclipse, Sun 31 Dec 2028") against this table.
            LunarEclipse eclipse = LunarEclipseCatalog.on(LocalDate.of(2028, 1, 12)).orElseThrow();
            assertThat(eclipse.nextComparable()).isEqualTo(LocalDate.of(2028, 12, 31));
            assertThat(eclipse.nextComparableKind()).isEqualTo("total");
            assertThat(LocalDate.of(2028, 12, 31).getDayOfWeek()).isEqualTo(java.time.DayOfWeek.SUNDAY);
        }

        @Test
        @DisplayName("between() finds each entry on its own date and nothing on the days around it")
        void betweenFindsEachEntry() {
            for (LunarEclipse eclipse : LunarEclipseCatalog.all()) {
                LocalDate date = eclipse.date();
                assertThat(LunarEclipseCatalog.between(date, date))
                        .as("between() on %s", date)
                        .extracting(LunarEclipse::date)
                        .containsExactly(date);
                assertThat(LunarEclipseCatalog.between(date.plusDays(1), date.plusDays(30)))
                        .as("the month after %s", date)
                        .noneMatch(other -> other.date().equals(date));
            }
        }

        @Test
        @DisplayName("a range spanning the whole table returns all of it, once each")
        void aWideRangeReturnsEverything() {
            assertThat(LunarEclipseCatalog.between(LocalDate.of(2025, 1, 1), LocalDate.of(2031, 1, 1)))
                    .hasSameSizeAs(LunarEclipseCatalog.all());
        }

        @Test
        @DisplayName("on() finds nothing on a date with no catalogued eclipse")
        void onFindsNothingElsewhere() {
            assertThat(LunarEclipseCatalog.on(LocalDate.of(2027, 1, 1))).isEmpty();
        }
    }
}
