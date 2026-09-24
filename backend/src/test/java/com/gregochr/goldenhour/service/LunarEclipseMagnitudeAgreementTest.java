package com.gregochr.goldenhour.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.AlmanacEvent;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.service.LunarEclipseCatalog.LunarEclipse;
import com.gregochr.goldenhour.service.LunarEclipseWording.Depth;
import com.gregochr.goldenhour.util.LunarEclipseCalculator;
import com.gregochr.solarutils.LunarCalculator;
import com.gregochr.solarutils.MoonriseMoonsetCalculator;

import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Drives {@link LunarEclipseHotTopicStrategy} and {@link LunarEclipseAlmanacSource} from the SAME
 * real {@link LunarEclipseCalculator} reduction, for every catalogued eclipse, and cross-checks
 * their magnitude and in-shadow-span wording — the regression test for the class of bug Codex
 * review found against PR #913 across three passes (an impossible "125% in shadow" on a total
 * eclipse; deep-partial copy applied to a barely-visible one; a rises-in-shadow eclipse's span
 * starting at {@code u1} instead of when the Moon can actually be seen). Both classes route every
 * magnitude- and span-derived word through {@link LunarEclipseWording}, so if these tests pass it
 * is because that routing held, not because the two happened to agree by coincidence — a regression
 * that reintroduced independent arithmetic in either class would break one of the tests below
 * without either production class needing to be touched.
 *
 * <p>Uses the real {@code solar-utils} calculators at the catalogue's own UK-centre reference point
 * (54.5°N, 2.5°W — {@code LunarEclipseCatalog}'s class javadoc), the same point its own
 * verification tests use, rather than mocking a sight per eclipse: this exercises the real
 * production reduction end to end, and the visibility gate is handled generically (an eclipse
 * invisible from this one point is skipped by both classes identically, which is itself the
 * agreement under test) rather than assumed.
 */
class LunarEclipseMagnitudeAgreementTest {

    private static final double UK_CENTRE_LAT = 54.5;
    private static final double UK_CENTRE_LON = -2.5;

    private static final Pattern PERCENT = Pattern.compile("(\\d+)%");

    private final LunarEclipseCalculator calculator =
            new LunarEclipseCalculator(new LunarCalculator(), new MoonriseMoonsetCalculator());

    private static LocationEntity location() {
        LocationEntity entity = new LocationEntity();
        entity.setName("UK centre");
        entity.setLat(UK_CENTRE_LAT);
        entity.setLon(UK_CENTRE_LON);
        entity.setEnabled(true);
        return entity;
    }

    private LocationRepository repository() {
        LocationRepository repository = mock(LocationRepository.class);
        when(repository.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of(location()));
        return repository;
    }

    private LunarEclipseHotTopicStrategy strategy() {
        SolarService solarService = mock(SolarService.class);
        SolarEventFreshness freshness = mock(SolarEventFreshness.class);
        // Every catalogued date is in the past relative to this test's real clock, so freshness
        // must be forced ahead — this test is about magnitude wording, not withdrawal.
        when(freshness.isAhead(any(LocalDateTime.class))).thenReturn(true);
        return new LunarEclipseHotTopicStrategy(repository(), solarService, freshness, calculator);
    }

    private LunarEclipseAlmanacSource almanacSource() {
        return new LunarEclipseAlmanacSource(repository(), calculator);
    }

    private static void assertNoFigureOverOneHundred(String text, LunarEclipse eclipse) {
        if (text == null) {
            return;
        }
        Matcher matcher = PERCENT.matcher(text);
        while (matcher.find()) {
            int value = Integer.parseInt(matcher.group(1));
            assertThat(value)
                    .as("a percentage figure in '%s' for eclipse %s", text, eclipse.date())
                    .isLessThanOrEqualTo(100);
        }
    }

    @Test
    @DisplayName("no percentage figure above 100 is ever printed, on either surface, for any "
            + "catalogued eclipse")
    void neverPrintsAFigureAboveOneHundred() {
        LunarEclipseHotTopicStrategy strategy = strategy();
        LunarEclipseAlmanacSource almanacSource = almanacSource();

        for (LunarEclipse eclipse : LunarEclipseCatalog.all()) {
            for (HotTopic topic : strategy.detect(eclipse.date(), eclipse.date())) {
                assertNoFigureOverOneHundred(topic.detail(), eclipse);
                assertNoFigureOverOneHundred(topic.description(), eclipse);
                topic.facts().forEach(fact -> assertNoFigureOverOneHundred(fact.value(), eclipse));
            }
            for (AlmanacEvent event : almanacSource.events(eclipse.date(), eclipse.date())) {
                assertNoFigureOverOneHundred(event.detail(), eclipse);
                event.meta().values().forEach(value -> assertNoFigureOverOneHundred(value, eclipse));
            }
        }
    }

    @Test
    @DisplayName("the strategy's fact-chip figure and the almanac source's meta figure agree, for "
            + "every catalogued eclipse the UK-centre reference point can see")
    void strategyAndAlmanacSourceAgreeOnCoverage() {
        LunarEclipseHotTopicStrategy strategy = strategy();
        LunarEclipseAlmanacSource almanacSource = almanacSource();

        for (LunarEclipse eclipse : LunarEclipseCatalog.all()) {
            List<HotTopic> topics = strategy.detect(eclipse.date(), eclipse.date());
            List<AlmanacEvent> events = almanacSource.events(eclipse.date(), eclipse.date());

            // Both classes gate on the SAME calculator.sight(eclipse, lat, lon).visible() call for
            // this one location, so "invisible from here" is not a divergence to tolerate — it is
            // the agreement under test, asserted explicitly rather than skipped past.
            if (topics.isEmpty() || events.isEmpty()) {
                assertThat(topics).as("eclipse %s: strategy", eclipse.date()).isEmpty();
                assertThat(events).as("eclipse %s: almanac source", eclipse.date()).isEmpty();
                continue;
            }

            String factValue = topics.get(0).facts().get(0).value();
            String magnitudeMeta = events.get(0).meta().get("magnitude");

            if (LunarEclipseWording.depthOf(eclipse) == Depth.TOTAL) {
                assertThat(factValue).as("eclipse %s: fact", eclipse.date()).startsWith("total ·");
                assertThat(magnitudeMeta).as("eclipse %s: meta", eclipse.date()).isEqualTo("total");
            } else {
                int pct = LunarEclipseWording.coveragePct(eclipse);
                assertThat(factValue).as("eclipse %s: fact", eclipse.date())
                        .startsWith(pct + "% in shadow ·");
                assertThat(magnitudeMeta).as("eclipse %s: meta", eclipse.date())
                        .isEqualTo(pct + "% in shadow");
            }
        }
    }

    @Test
    @DisplayName("the strategy's 'in shadow' fact and the almanac source's 'shadow' meta agree on "
            + "the visible span, for every catalogued eclipse the UK-centre reference point can see")
    void strategyAndAlmanacSourceAgreeOnTheInShadowSpan() {
        LunarEclipseHotTopicStrategy strategy = strategy();
        LunarEclipseAlmanacSource almanacSource = almanacSource();

        for (LunarEclipse eclipse : LunarEclipseCatalog.all()) {
            List<HotTopic> topics = strategy.detect(eclipse.date(), eclipse.date());
            List<AlmanacEvent> events = almanacSource.events(eclipse.date(), eclipse.date());
            if (topics.isEmpty() || events.isEmpty()) {
                // Already proven identical (both empty, for the same reason) by the coverage test
                // above; nothing further to check here.
                continue;
            }

            String inShadowFact = topics.get(0).facts().stream()
                    .filter(fact -> "in shadow".equals(fact.key()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no 'in shadow' fact for " + eclipse.date()))
                    .value();
            String shadowMeta = events.get(0).meta().get("shadow");

            assertThat(shadowMeta).as("eclipse %s", eclipse.date()).isEqualTo("in shadow " + inShadowFact);
            // Neither surface may print u1's raw contact time as the span's start unless it happens
            // to equal the visible start (i.e. the Moon does not rise already in shadow) — the
            // per-eclipse "rises "/plain distinction is exactly what this loop exercises across the
            // whole catalogue rather than by hand for one date.
        }
    }
}
